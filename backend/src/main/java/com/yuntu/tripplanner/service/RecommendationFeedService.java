package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuntu.tripplanner.client.AmapClient;
import com.yuntu.tripplanner.common.AbBucket;
import com.yuntu.tripplanner.common.SpotNameUtil;
import com.yuntu.tripplanner.common.SpotTagMapper;
import com.yuntu.tripplanner.common.SpotVisibility;
import com.yuntu.tripplanner.model.*;
import com.yuntu.tripplanner.repository.SpotFavoriteRepository;
import com.yuntu.tripplanner.repository.SpotFeedLogRepository;
import com.yuntu.tripplanner.repository.SpotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 推荐景点流服务（产品化阶段一，PRODUCT_EVOLUTION_PLAN §5）。
 *
 * <p>与 {@link RecommendationService}（行程生成后的推荐日志/理由）职责分离：
 * 本服务只回答「首页/发现页的推荐景点从哪来、怎么排」——
 * <ol>
 *   <li><b>数据底座</b>：城市 spot 缓存不足时，调高德 POI 按需同步落 spot 表
 *       （本轮不批量抓取全城 POI，只同步用户访问/生成的城市，每城首批约 30~60 个）；</li>
 *   <li><b>内容增强</b>：对每个高德 POI 尝试匹配本地 RAG 攻略卡片，命中则回填
 *       可信简介并把 data_quality 升为 GUIDE_MATCHED（无命中诚实保持 POI_ONLY，不编简介）；</li>
 *   <li><b>个性化排序</b>：复用统一评分器 {@link PersonalizedScoreCalculator}
 *       （偏好匹配/回避硬约束/历史重复降权同一口径），无画像时降级为热度排序；</li>
 *   <li><b>反馈闭环</b>：排序实时基于最新画像（每次请求现算，不设长缓存），
 *       收藏/不感兴趣 → 画像版本变化 → 下次请求排序自然改变（§17.5）。</li>
 * </ol>
 */
@Slf4j
@Service
public class RecommendationFeedService {

    /** 单城市 spot 缓存阈值：不足则触发按需同步（每城市首批 30~60 个，避免每次访问都打高德） */
    private static final long CITY_CACHE_MIN = 20;

    private static final int SYNC_BATCH = 60;

    /** 数量已足时，仅当全城数据超过该天数未刷新才重新同步（行级自然冷却） */
    private static final int REFRESH_DAYS = 7;

    /** A/B 处理组候选池过滤下限：攻略候选不足该数时整体回退全量（数据稀疏城市不空流） */
    private static final int MIN_QUALITY_POOL = 6;

    /** 城市级在途锁：同城并发请求只放行一个真正打高德，其余直接读现有快照 */
    private final Map<String, Boolean> citySyncing = new ConcurrentHashMap<>();

    private final SpotRepository spotRepository;
    private final SpotFavoriteRepository spotFavoriteRepository;
    private final SpotFeedLogRepository spotFeedLogRepository;
    private final AmapClient amapClient;
    private final RagService ragService;
    private final UserProfileService userProfileService;
    private final TripRecordService tripRecordService;
    private final AbExperimentService abExperimentService;
    private final RecommendationInterventionService interventionService;
    /** 城市闸门：推荐/同步入口的前置校验，防止乱输入落库（2026-09-13 数据污染修复） */
    private final CityValidator cityValidator;

    public RecommendationFeedService(SpotRepository spotRepository,
                                     SpotFavoriteRepository spotFavoriteRepository,
                                     SpotFeedLogRepository spotFeedLogRepository,
                                     AmapClient amapClient,
                                     RagService ragService,
                                     UserProfileService userProfileService,
                                     TripRecordService tripRecordService,
                                     AbExperimentService abExperimentService,
                                     RecommendationInterventionService interventionService,
                                     CityValidator cityValidator) {
        this.spotRepository = spotRepository;
        this.spotFavoriteRepository = spotFavoriteRepository;
        this.spotFeedLogRepository = spotFeedLogRepository;
        this.amapClient = amapClient;
        this.ragService = ragService;
        this.userProfileService = userProfileService;
        this.tripRecordService = tripRecordService;
        this.abExperimentService = abExperimentService;
        this.interventionService = interventionService;
        this.cityValidator = cityValidator;
    }

    /**
     * 推荐流（对齐 PRODUCT_EVOLUTION_PLAN §5.2 GET /recommendations/spots）。
     *
     * @param userId   当前登录用户（可为 null → 直接走热门降级）
     * @param city     城市（如"上海"；解析不到规范城市时按原串查询）
     * @param page     页码（1 起）
     * @param pageSize 每页条数（默认 12，上限 50）
     * @param sort     personalized（默认，有画像才真正个性化）/ popular / latest
     * @return 分页推荐结果；城市无任何数据时 items 为空（不高德报错即 500）
     */
    public RecommendationFeed feed(String userId, String city, int page, int pageSize, String sort) {
        return feed(userId, city, page, pageSize, sort, null);
    }

    /** 推荐景点流（P1-5 审查报告：feedTraceId=前端页面会话幂等键，仅个性化曝光写入用） */
    public RecommendationFeed feed(String userId, String city, int page, int pageSize,
                                   String sort, String feedTraceId) {
        RecommendationFeed result = new RecommendationFeed();
        if (city == null || city.isBlank()) {
            result.setItems(List.of());
            result.setPage(page);
            result.setPageSize(pageSize);
            result.setTotal(0L);
            result.setPersonalized(false);
            result.setProfileVersion(userId == null ? 0 : userProfileService.getProfileVersion(userId));
            return result;
        }
        int size = Math.max(1, Math.min(pageSize <= 0 ? 12 : pageSize, 50));
        int pageNo = Math.max(1, page);
        String cityKey = city.trim();

        // 【2026-09-13 数据污染修复】先「归一化 + 校验」，再进入同步/读取流程（fail closed）。
        // 此前只判"城市为空"，于是「火星」「1」「北就」会被原样交给高德 POI 搜索，
        // 返回结果再以该字符串为 city 落 spot 表（实测已污染 80 行：北京景点被贴上假城市标签）。
        // 这里既不调高德也不写库，items 全空并回传 invalid_city + 形近纠错建议供前端提示。
        //
        // 归一化（canonicalCity）与校验是同一步：返回 null 即"不认识"，拒绝；
        // 返回非 null 则**此后一律用规范名读写** —— 否则"北京市""魔都"这类能通过校验的输入
        // 会以原始串另起一套 city 键（实测一次访问即新增 15 行 '北京市' 平行数据）。
        String canonicalCity = cityValidator.canonicalCity(cityKey);
        if (canonicalCity == null) {
            log.warn("非法城市被拒（不触发高德、不写库）: {}", cityKey);
            result.setItems(List.of());
            result.setPage(pageNo);
            result.setPageSize(size);
            result.setTotal(0L);
            result.setPersonalized(false);
            result.setProfileVersion(userId == null ? 0 : userProfileService.getProfileVersion(userId));
            result.setInterventions(List.of());
            result.setInvalidCity(true);
            result.setInvalidCitySuggestion(cityValidator.suggestCity(cityKey));
            return result;
        }
        if (!canonicalCity.equals(cityKey)) {
            log.info("城市名归一化: {} → {}（读写统一用规范名）", cityKey, canonicalCity);
            cityKey = canonicalCity;
        }

        try {
            syncCityIfStale(cityKey);
        } catch (Exception e) {
            log.warn("城市景点同步失败（用已有快照兜底）: {} - {}", cityKey, e.getMessage());
        }

        List<Spot> spots = spotRepository.selectList(
                new LambdaQueryWrapper<Spot>()
                        .eq(Spot::getCity, cityKey)
                        .orderByDesc(Spot::getUpdatedAt));
        // B3 景点治理：下线/合并别名/异常标记（NON_SPOT/CLOSED/ERROR_POI）的景点不进入推荐候选池
        spots = spots.stream().filter(SpotVisibility::isActive).collect(Collectors.toList());

        // §6.3 推荐人工干预（只读消费，运营与算法分层）：加载窗口内生效干预，
        // 黑名单从候选池剔除（total 之前）；置顶/降权在排序后做稳定搬移；
        // featured_city 与 interventions meta 独立输出，不触碰任何算法分字段。
        List<RecommendationIntervention> activeActs = interventionService.activeNow();
        Set<String> blacklistIds = spotIdsOf(activeActs, RecommendationIntervention.ACTION_BLACKLIST);
        Map<String, RecommendationIntervention> pinActs = spotActsOf(activeActs,
                RecommendationIntervention.ACTION_PIN);
        Map<String, RecommendationIntervention> demoteActs = spotActsOf(activeActs,
                RecommendationIntervention.ACTION_DEMOTE);
        if (!blacklistIds.isEmpty()) {
            spots = spots.stream()
                    .filter(s -> !blacklistIds.contains(s.getSpotId()))
                    .collect(Collectors.toList());
        }
        if (spots.isEmpty()) {
            result.setItems(List.of());
            result.setPage(pageNo);
            result.setPageSize(size);
            result.setTotal(0L);
            result.setPersonalized(false);
            result.setProfileVersion(userId == null ? 0 : userProfileService.getProfileVersion(userId));
            result.setFeaturedCity(null);
            result.setFeaturedReason(null);
            result.setInterventions(List.of());
            return result;
        }

        // A/B 实验（阶段四任务 6，攻略质量门）：命中处理组 → "为你推荐"的候选池
        // 只保留有真实攻略内容的景点（GUIDE_MATCHED/VERIFIED，剔除纯高德 POI_ONLY）。
        // 过滤后不足下限则整体回退全量（数据稀疏城市不空流）；热门/最新 Tab 是用户显式浏览，
        // 不参与该实验。变体由粘性分桶决定（AbExperimentService.resolveVariant），无实验/匿名 → null（基线）。
        // 实验名取自「当前 ACTIVE 的景点流实验」而非写死常量——管理面可自建任意名，写死会与库内 exp_name 对不上，
        // 导致实验建了永不生效（对照/处理组恒 0）。
        AbExperiment spotExp = wantPersonalized(sort) && userId != null && !userId.isBlank()
                ? abExperimentService.activeOf(AbExperiment.FEED_SPOT) : null;
        String abVariant = spotExp == null
                ? null : abExperimentService.resolveVariant(userId, spotExp.getExpName());
        if (AbBucket.VARIANT_TREATMENT.equals(abVariant)) {
            List<Spot> guided = spots.stream()
                    .filter(s -> s.getDataQuality() != null
                            && !Spot.QUALITY_POI_ONLY.equals(s.getDataQuality()))
                    .collect(Collectors.toList());
            if (guided.size() >= MIN_QUALITY_POOL) {
                log.info("A/B[{}] 处理组生效: user={} city={} 候选池 {} → {}",
                        spotExp.getExpName(), userId, cityKey,
                        spots.size(), guided.size());
                spots = guided;
            }
        }
        long total = spots.size();

        List<UserPreference> prefs = userId == null || userId.isBlank()
                ? List.of()
                : userProfileService.listPreferences(userId);
        Set<String> visitedNames = collectVisitedNames(userId);
        Map<String, Boolean> collectedMap = collectedSpotIds(userId);

        // 景点流个性化口径（P1-1 澄清）：只有 travel_style 域正偏好（权重≥阈值）真实参与排序
        // 才叫"为你推荐"；food/pace/city 等域不影响景点流排序，前端不得因拥有任意画像就说"已按画像排序"
        boolean hasStyle = prefs.stream().anyMatch(p -> p != null
                && UserPreference.CATEGORY_TRAVEL_STYLE.equals(p.getCategory())
                && p.getWeight() != null
                && p.getWeight() >= UserProfileService.POSITIVE_WEIGHT_MIN);
        boolean personalized = wantPersonalized(sort) && hasStyle;

        List<RecommendationItem> ranked = spots.stream()
                .map(s -> toItem(s, prefs, visitedNames, collectedMap, personalized))
                .sorted(comparator(sort, personalized))
                .collect(Collectors.toList());
        // §6.3 运营置顶/降权：在算法排序后做稳定搬移（置顶前置、降权沉底），
        // 同一景点同时存在两动作时以降权为准（保守：宁可压后也不夸大推荐）
        ranked = orderByIntervention(ranked, pinActs.keySet(), demoteActs.keySet());

        // 内存分页（单城候选量 30~60，规模小无需 SQL 分页）
        int from = Math.min((pageNo - 1) * size, ranked.size());
        int to = Math.min(from + size, ranked.size());
        result.setItems(ranked.subList(from, to));
        result.setPage(pageNo);
        result.setPageSize(size);
        result.setTotal(total);
        result.setPersonalized(personalized);
        result.setProfileVersion(userId == null ? 0 : userProfileService.getProfileVersion(userId));
        // §6.3 运营元信息（与算法分分离）：本页卡片命中的干预 + 城市精选标记
        result.setInterventions(pageInterventionMeta(result.getItems(), pinActs, demoteActs));
        RecommendationIntervention featured = featuredOf(activeActs, cityKey);
        if (featured != null) {
            result.setFeaturedCity(Boolean.TRUE);
            result.setFeaturedReason(featured.getReason());
        } else {
            result.setFeaturedCity(null);
            result.setFeaturedReason(null);
        }

        // 曝光日志（仅真正个性化分支；热门/最新不写，与帖子推荐流口径一致），
        // 供推荐流监控（任务 7）按变体/攻略质量对照命中率与反馈漏斗
        if (personalized && userId != null && !userId.isBlank()) {
            logSpotExposures(userId, result.getProfileVersion(), abVariant, cityKey,
                    result.getItems(), feedTraceId);
        }
        return result;
    }

    /** 是否命中"为你推荐"语义（personalized 或默认）——A/B 质量门只作用于它 */
    private static boolean wantPersonalized(String sort) {
        return "personalized".equalsIgnoreCase(sort) || sort == null || sort.isBlank();
    }

    /* ================= §6.3 人工干预消费（运营与算法分层，全部只读） ================= */

    /** 干预列表中某动作命中的 spot_id 集合（SPOT 对象） */
    private static Set<String> spotIdsOf(List<RecommendationIntervention> acts, String action) {
        return acts.stream()
                .filter(a -> RecommendationIntervention.TARGET_SPOT.equals(a.getTargetType()))
                .filter(a -> action.equals(a.getAction()))
                .map(RecommendationIntervention::getTargetId)
                .collect(Collectors.toSet());
    }

    /** 干预列表中某动作命中的 spot_id → 干预行（同动作唯一，直接 toMap） */
    private static Map<String, RecommendationIntervention> spotActsOf(
            List<RecommendationIntervention> acts, String action) {
        return acts.stream()
                .filter(a -> RecommendationIntervention.TARGET_SPOT.equals(a.getTargetType()))
                .filter(a -> action.equals(a.getAction()))
                .collect(Collectors.toMap(RecommendationIntervention::getTargetId, a -> a, (x, y) -> x));
    }

    /** 当前城市的"城市精选"干预（CITY 对象，target_id=城市名） */
    private static RecommendationIntervention featuredOf(List<RecommendationIntervention> acts, String city) {
        return acts.stream()
                .filter(a -> RecommendationIntervention.TARGET_CITY.equals(a.getTargetType()))
                .filter(a -> RecommendationIntervention.ACTION_FEATURED.equals(a.getAction()))
                .filter(a -> city.equals(a.getTargetId()))
                .findFirst().orElse(null);
    }

    /**
     * 稳定搬移：置顶景点整体前置、降权景点整体沉底（各自保持原相对顺序）。
     * 降权优先于置顶（同景点双动作时按降权处理，保守语义）。
     */
    private static List<RecommendationItem> orderByIntervention(List<RecommendationItem> ranked,
                                                                Set<String> pinIds,
                                                                Set<String> demoteIds) {
        if (pinIds.isEmpty() && demoteIds.isEmpty()) {
            return ranked;
        }
        List<RecommendationItem> head = new ArrayList<>();
        List<RecommendationItem> mid = new ArrayList<>();
        List<RecommendationItem> tail = new ArrayList<>();
        for (RecommendationItem it : ranked) {
            String sid = it.getSpotId();
            if (demoteIds.contains(sid)) {
                tail.add(it);
            } else if (pinIds.contains(sid)) {
                head.add(it);
            } else {
                mid.add(it);
            }
        }
        if (head.isEmpty() && tail.isEmpty()) {
            return ranked;
        }
        List<RecommendationItem> merged = new ArrayList<>(head.size() + mid.size() + tail.size());
        merged.addAll(head);
        merged.addAll(mid);
        merged.addAll(tail);
        return merged;
    }

    /** 本页卡片命中的运营干预 meta（供前端打「运营置顶/人工降权」标识；独立于算法分） */
    private static List<RecommendationFeed.RecommendationInterventionMeta> pageInterventionMeta(
            List<RecommendationItem> items,
            Map<String, RecommendationIntervention> pinActs,
            Map<String, RecommendationIntervention> demoteActs) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<RecommendationFeed.RecommendationInterventionMeta> metas = new ArrayList<>();
        for (RecommendationItem it : items) {
            String sid = it.getSpotId();
            RecommendationIntervention act = demoteActs.getOrDefault(sid, pinActs.get(sid));
            if (act == null) {
                continue;
            }
            RecommendationFeed.RecommendationInterventionMeta meta =
                    new RecommendationFeed.RecommendationInterventionMeta();
            meta.setSpotId(sid);
            meta.setAction(act.getAction());
            meta.setReason(act.getReason());
            metas.add(meta);
        }
        return metas;
    }

    /**
     * 景点推荐流曝光日志（阶段四任务 7）：为页内每条卡片写 spot_feed_log。
     * 命中偏好 = 推荐理由含"匹配你的偏好"（与统一评分器语义一致）；写入失败不影响推荐返回。
     * feedTraceId（P1-5 审查报告）为页面会话幂等键：同 (user, trace, spot) 判重后跳过，
     * 防页面重试/重复渲染双写稀释曝光分母；null=旧客户端按原逻辑落一条。
     */
    private void logSpotExposures(String userId, int profileVersion, String abVariant,
                                  String city, List<RecommendationItem> items, String feedTraceId) {
        if (items == null || items.isEmpty()) {
            return;
        }
        int position = 0;
        for (RecommendationItem item : items) {
            if (item == null || item.getSpotId() == null) {
                continue;
            }
            if (feedTraceId != null && !feedTraceId.isBlank()
                    && alreadyExposed(userId, feedTraceId, item.getSpotId())) {
                continue; // 同一次页面会话已曝光过 → 幂等跳过
            }
            try {
                SpotFeedLog row = new SpotFeedLog();
                row.setUserId(userId);
                row.setSpotId(item.getSpotId());
                row.setPoiId(item.getPoiId());
                row.setCity(city);
                row.setSort("recommended");
                row.setPosition(position++);
                row.setFinalScore(item.getScore());
                String reason = item.getRecommendReason();
                row.setHitPreference(reason != null && reason.contains("匹配你的偏好") ? 1 : 0);
                row.setDataQuality(item.getDataQuality());
                row.setRecommendReason(truncate(reason, 300));
                row.setProfileVersion(profileVersion);
                row.setRankingVersion(PersonalizedScoreCalculator.RANKING_VERSION);
                row.setAbVariant(abVariant);
                row.setFeedTraceId(feedTraceId);
                spotFeedLogRepository.insert(row);
            } catch (Exception e) {
                log.debug("景点曝光日志写入失败（不影响推荐返回）: {}", e.getMessage());
            }
        }
    }

    /** 同一 (user, trace, spot_id) 是否已曝光（P1-5 幂等键去重） */
    private boolean alreadyExposed(String userId, String traceId, String spotId) {
        try {
            Long n = spotFeedLogRepository.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SpotFeedLog>()
                            .eq(SpotFeedLog::getUserId, userId)
                            .eq(SpotFeedLog::getFeedTraceId, traceId)
                            .eq(SpotFeedLog::getSpotId, spotId));
            return n != null && n > 0;
        } catch (Exception e) {
            log.debug("景点曝光判重查询失败（放行写入）: {}", e.getMessage());
            return false;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 城市景点按需同步（Review 复查 #4：数量 + 时间双重判断，避免"满 20 条就永不更新"）。
     *
     * <p>触发条件：该城市不足 {@link #CITY_CACHE_MIN} 条，或全城最近一次同步时间已超过
     * {@link #REFRESH_DAYS} 天（数据量足也按 7 天周期刷新图片/地址/坐标/RAG 简介）。
     * 高德调用由城市级在途锁收敛：并发请求只放行一个，其余直接使用现有快照；同步成功后
     * last_synced_at 被更新 → 行级自然冷却，不会每次用户访问都打高德。
     */
    private void syncCityIfStale(String city) {
        if (!syncDue(city)) {
            return;
        }
        if (citySyncing.putIfAbsent(city, Boolean.TRUE) != null) {
            log.debug("城市同步进行中，跳过（用现有快照）：{}", city);
            return;
        }
        try {
            doSyncCity(city);
        } finally {
            citySyncing.remove(city);
        }
    }

    /** 是否需要同步：数量不足 / 或全城数据超过 REFRESH_DAYS 未刷新 */
    private boolean syncDue(String city) {
        try {
            long count = spotRepository.selectCount(
                    new LambdaQueryWrapper<Spot>().eq(Spot::getCity, city));
            if (count < CITY_CACHE_MIN) {
                return true;
            }
            // 数量已足：仅当全城最近同步时间过期才重同步（行级冷却，避免每次访问打高德）
            List<Spot> newest = spotRepository.selectList(
                    new LambdaQueryWrapper<Spot>()
                            .eq(Spot::getCity, city)
                            .orderByDesc(Spot::getLastSyncedAt)
                            .last("LIMIT 1"));
            if (newest.isEmpty()) {
                return true;
            }
            LocalDateTime ts = newest.get(0).getLastSyncedAt();
            return ts == null || ts.isBefore(LocalDateTime.now().minusDays(REFRESH_DAYS));
        } catch (Exception e) {
            log.warn("城市同步判断失败（走同步兜底）: {} - {}", city, e.getMessage());
            return true;
        }
    }

    /** 实际执行高德景点搜索并 upsert spot 表（数量不足或过期时才被 syncCityIfStale 调用） */
    private void doSyncCity(String city) {
        // 兜底闸门（第二道防线）：绝不把非已知城市交给高德搜索，更不会以其为 city 写 spot 表。
        // 同时在此**再归一化一次**（幂等）：本方法是 spot 表的唯一自动写入口，
        // 只要上游任何一处漏了归一化，"北京市""魔都"就会另起一套 city 键 —— 写库路径必须收敛到规范名。
        String canonical = cityValidator.canonicalCity(city);
        if (canonical == null) {
            log.warn("跳过非已知城市的景点同步（防止脏数据入库）: {}", city);
            return;
        }
        if (!canonical.equals(city)) {
            log.info("同步入口城市名归一化: {} → {}", city, canonical);
            city = canonical;
        }
        List<Map<String, Object>> pois;
        try {
            pois = amapClient.searchPoi(city, "景点");
        } catch (Exception e) {
            log.warn("高德景点搜索失败: {} - {}", city, e.getMessage());
            return;
        }
        if (pois == null || pois.isEmpty()) {
            return;
        }
        int upserted = 0;
        int loop = 0;
        for (Map<String, Object> poi : pois) {
            if (loop++ >= SYNC_BATCH) {
                break;
            }
            String name = str(poi.get("name"));
            String poiId = str(poi.get("poi_id"));
            if (name == null || name.isBlank() || poiId == null || poiId.isBlank()) {
                continue;
            }
            Spot existing = spotRepository.selectOne(
                    new LambdaQueryWrapper<Spot>()
                            .eq(Spot::getCity, city)
                            .eq(Spot::getPoiId, poiId)
                            .last("LIMIT 1"));
            if (existing != null) {
                // B3 治理保护：下线/合并别名/已标记异常（NON_SPOT/CLOSED/ERROR_POI）的行保持冻结，
                // 自动同步绝不复活、不覆盖 —— 管理员修正（人工锁定字段）也不被无条件覆盖。
                if (!SpotVisibility.isActive(existing)) {
                    log.debug("同步跳过治理对象（冻结）：spot={} status={} flag={} merged={}",
                            existing.getSpotId(), existing.getStatus(),
                            existing.getFlag(), existing.getMergedInto());
                    continue;
                }
                Set<String> locked = lockedFields(existing);
                applyPoiToSpot(existing, poi, locked);
                existing.setLastSyncedAt(LocalDateTime.now());
                if (Spot.FLAG_OUTDATED.equals(existing.getFlag())) {
                    // 自动同步成功 = 一次成功的核验刷新：清除"过时待核验"标记
                    existing.setFlag(null);
                    existing.setFlagReason(null);
                }
                spotRepository.updateById(existing);
                upserted++;
                continue;
            }
            Spot spot = new Spot();
            spot.setSpotId(spotIdOf(city, poiId));
            spot.setPoiId(poiId);
            spot.setName(name);
            spot.setNormalizedName(normalize(name));
            spot.setCity(city);
            spot.setAddress(str(poi.get("address")));
            spot.setCategory(str(poi.get("type")));
            spot.setImageUrl(str(poi.get("image_url")));
            spot.setLongitude(num(poi.get("longitude")));
            spot.setLatitude(num(poi.get("latitude")));
            spot.setSource(Spot.SOURCE_AMAP);
            spot.setDataQuality(Spot.QUALITY_POI_ONLY);
            spot.setLastSyncedAt(LocalDateTime.now());
            enrichFromGuide(spot, city);
            try {
                spotRepository.insert(spot);
                upserted++;
            } catch (Exception e) {
                log.debug("spot 落库冲突（并发同步，忽略）: {}", e.getMessage());
            }
        }
        log.info("城市景点按需同步：{}（候选 {}，落库/更新 {}）", city, pois.size(), upserted);
    }

    /* ============ 景点数据治理支持（B组 设计方案 §5，供 AdminSpotService 调用） ============ */

    /** 单点重同步结果：已更新 */
    public static final int SYNC_UPDATED = 1;
    /** 单点重同步结果：同名搜索未命中相同 poi_id（可能已下线/改名/挪走） */
    public static final int SYNC_NO_MATCH = 2;
    /** 单点重同步结果：spot 不存在 */
    public static final int SYNC_NOT_FOUND = 3;

    /**
     * 单点强制重同步（管理员「手动触发重新同步」）。
     *
     * <p>与 {@link #doSyncCity} 全城批量不同：按景点名在城市内做定向关键词搜索，
     * 只对「poi_id 与库内一致」的结果落库 —— 命中同名的其他 POI 不会污染本行。
     * 更新范围 = 未被人工锁定的字段（manual_override_fields），治理状态
     * （status/flag/merged_into 等）一律不动 —— 管理员手工修正不被自动同步覆盖（§5 注意）。
     * 同步成功且原标记为 OUTDATED（过时待核验）时自动清除该标记（核验动作闭环）。
     *
     * @return {@link #SYNC_UPDATED} / {@link #SYNC_NO_MATCH} / {@link #SYNC_NOT_FOUND}
     */
    public int resyncSpot(String spotId) {
        Spot spot = spotRepository.selectOne(new LambdaQueryWrapper<Spot>()
                .eq(Spot::getSpotId, spotId).last("LIMIT 1"));
        if (spot == null || spot.getCity() == null || spot.getName() == null
                || spot.getCity().isBlank() || spot.getName().isBlank()) {
            return SYNC_NOT_FOUND;
        }
        Set<String> locked = lockedFields(spot);
        List<Map<String, Object>> pois = amapClient.searchPoiFresh(spot.getCity(), spot.getName());
        Map<String, Object> hit = null;
        for (Map<String, Object> poi : pois) {
            if (spot.getPoiId() != null && spot.getPoiId().equals(str(poi.get("poi_id")))) {
                hit = poi;
                break;
            }
        }
        if (hit == null) {
            log.info("单点重同步未命中同名 POI（可能已下线/改名）：spot={} city={} 候选 {} 个",
                    spotId, spot.getCity(), pois.size());
            return SYNC_NO_MATCH;
        }
        applyPoiToSpot(spot, hit, locked);
        spot.setLastSyncedAt(LocalDateTime.now());
        if (Spot.FLAG_OUTDATED.equals(spot.getFlag())) {
            spot.setFlag(null);
            spot.setFlagReason(null);
        }
        spotRepository.updateById(spot);
        return SYNC_UPDATED;
    }

    /**
     * 单点「重新匹配攻略」（管理员）：用当前名称重跑 RAG 攻略卡片匹配，
     * 命中则回填可信简介并升级 data_quality（GUIDE_MATCHED）。描述/标签被人工锁定则跳过
     * （管理员手工修正优先于攻略回填）；命中且原标记 OUTDATED 时同步清标记。
     *
     * @return true=命中攻略卡片并完成增强；false=未命中/被人工锁定/景点不存在
     */
    public boolean rematchGuide(String spotId) {
        Spot spot = spotRepository.selectOne(new LambdaQueryWrapper<Spot>()
                .eq(Spot::getSpotId, spotId).last("LIMIT 1"));
        if (spot == null) {
            return false;
        }
        Set<String> locked = lockedFields(spot);
        if (locked.contains("description") || locked.contains("tags")) {
            log.info("攻略重匹配跳过（描述/标签人工锁定）：spot={}", spotId);
            return false;
        }
        enrichFromGuide(spot, spot.getCity());
        if (Spot.FLAG_OUTDATED.equals(spot.getFlag())) {
            spot.setFlag(null);
            spot.setFlagReason(null);
        }
        spotRepository.updateById(spot);
        return spot.getSource() != null && spot.getSource().contains("RAG");
    }

    /**
     * 解析人工锁定字段集合（manual_override_fields 逗号分隔；未开启人工修正 → 空集）。
     * 字段名与 {@link #applyPoiToSpot} 判断一致：name/address/category/imageUrl/longitude/
     * description/tags（longitude 同时代表经纬坐标组）。
     */
    public static Set<String> lockedFields(Spot spot) {
        Set<String> locked = new HashSet<>();
        if (spot != null && Boolean.TRUE.equals(spot.getManualOverride())
                && spot.getManualOverrideFields() != null && !spot.getManualOverrideFields().isBlank()) {
            for (String f : spot.getManualOverrideFields().split(",")) {
                if (f != null && !f.isBlank()) {
                    locked.add(f.trim());
                }
            }
        }
        return locked;
    }

    /**
     * 把高德 POI 字段应用到 spot 行（治理后的唯一更新入口）：
     * 只覆盖未锁定字段；治理状态（status/flag/merged_into/manual_override）永不触碰；
     * 描述/标签均未锁定时才做 RAG 攻略增强（攻略可能改写描述/地址）。
     */
    private void applyPoiToSpot(Spot spot, Map<String, Object> poi, Set<String> locked) {
        String name = str(poi.get("name"));
        if (name != null && !name.isBlank() && !locked.contains("name")) {
            spot.setName(name);
            spot.setNormalizedName(normalize(name));
        }
        if (!locked.contains("address")) {
            spot.setAddress(str(poi.get("address")));
        }
        if (!locked.contains("category")) {
            spot.setCategory(str(poi.get("type")));
        }
        if (!locked.contains("imageUrl")) {
            spot.setImageUrl(str(poi.get("image_url")));
        }
        if (!locked.contains("longitude")) {
            spot.setLongitude(num(poi.get("longitude")));
            spot.setLatitude(num(poi.get("latitude")));
        }
        if (!locked.contains("description") && !locked.contains("tags")) {
            enrichFromGuide(spot, spot.getCity());
        } else if (locked.contains("description") || locked.contains("tags")) {
            log.debug("RAG 增强跳过（描述/标签人工锁定）：spot={}", spot.getSpotId());
        }
    }

    /**
     * RAG 攻略卡片增强：命中该城市的攻略景点卡片 → 回填可信简介与标签，
     * data_quality POI_ONLY → GUIDE_MATCHED。未命中保持 POI_ONLY（诚实兜底，不编简介）。
     *
     * <p>幂等约束（Review P2-4 重增强场景）：命中时按攻略卡片整段重建简介
     * （intro + 门票等补充），而不是往已有 description 上追加 —— 同一景点被重复
     * 同步（新插入 / 已有记录再同步）不会产生「门票：…；门票：…」式重复拼接。
     */
    private void enrichFromGuide(Spot spot, String city) {
        try {
            Map<String, String> card = ragService.findSpotCard(city, spot.getName());
            if (card == null || card.isEmpty()) {
                return;
            }
            List<String> parts = new ArrayList<>();
            String intro = card.get("intro");
            if (intro != null && !intro.isBlank()) {
                parts.add(intro);
            }
            String ticket = card.get("ticket");
            if (ticket != null && !ticket.isBlank()) {
                parts.add("门票：" + ticket);
            }
            if (!parts.isEmpty()) {
                spot.setDescription(String.join("；", parts));
            }
            String location = card.get("location");
            if (location != null && !location.isBlank()) {
                spot.setAddress(location);
            }
            spot.setDataQuality(Spot.QUALITY_GUIDE_MATCHED);
            spot.setSource(Spot.SOURCE_AMAP_AND_RAG);
        } catch (Exception e) {
            log.debug("RAG 卡片匹配失败（保持 POI_ONLY）: {} - {}", city, spot.getName());
        }
    }

    /**
     * 组装推荐卡片项：确定性推荐理由（命中偏好/未去过/数据可信），分数由统一评分器计算。
     *
     * @param personalizedFeed 本条流是否真正个性化排序（personalized & hasStyle）：
     *                         仅在该分支才暴露"命中偏好"字段/文案；popular/latest/无画像降级
     *                         一律中性理由 + 不置 personalized/match 字段（前端不显示匹配度%）。
     */
    private RecommendationItem toItem(Spot spot, List<UserPreference> prefs,
                                      Set<String> visitedNames, Map<String, Boolean> collectedMap,
                                      boolean personalizedFeed) {
        RecommendationItem item = new RecommendationItem();
        item.setSpotId(spot.getSpotId());
        item.setPoiId(spot.getPoiId());
        item.setName(spot.getName());
        item.setCity(spot.getCity());
        item.setCategory(spot.getCategory());
        item.setImageUrl(spot.getImageUrl());
        // 列表卡片：无真实简介就不展示（前端 v-if 隐藏该行）。
        // 之前用 safeDescription 兜底，实测深圳等未覆盖攻略的城市"全城每张卡都显示
        // 同一句（该景点简介暂未匹配到真实资料…）"，观感像数据坏了；免责声明留给
        // 详情页（SpotService）才说得通——用户点进来时解释一句是诚实，列表上刷屏是噪音。
        item.setDescription(spot.getDescription() != null && !spot.getDescription().isBlank()
                ? spot.getDescription() : null);
        // 标签与评分器同源（PersonalizedScoreCalculator 内部用同一映射）：type+名称 → 旅行风格标签
        item.setTags(SpotTagMapper.styleTags(spot.getCategory(), spot.getName()));
        item.setSource(spot.getSource());
        item.setDataQuality(spot.getDataQuality());
        item.setCollected(Boolean.TRUE.equals(collectedMap.get(spot.getSpotId())));

        ScoreDetail d = PersonalizedScoreCalculator.evaluate(
                spot.getName(), spot.getCategory(), false, prefs, visitedNames, Set.of());
        item.setScore(d.finalScore());
        // P0-1：真实命中（无硬回避 + 有正偏好标签）才暴露"个性化/匹配度"字段；
        // score 是含基础分 0.5 的综合排序分，禁止前端当匹配度展示。
        // 2026-09-09 粒度修正：可见匹配度 = 偏好强度 × 候选覆盖度（见 visibleMatchScore），
        // 避免"同一画像条目被所有候选命中 → 全城清一色同分"的观感失真（同分≠造假，但无区分度）。
        if (personalizedFeed && d.hit()) {
            item.setPersonalized(Boolean.TRUE);
            item.setMatchScore(visibleMatchScore(d, item.getTags()));
            item.setMatchedPreferences(d.matchedTags());
        }
        item.setRecommendReason(reasonOf(d, spot.getDataQuality(), personalizedFeed));
        return item;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /**
     * 用户可见匹配度（2026-09-09 粒度修正，纯函数便于单测）：
     * 在偏好强度（命中画像 Σ权重×置信度，见 {@link ScoreDetail#preferenceScore()}）基础上，
     * 再按「候选内容中偏好特征的实际占比」折算 —— coverage = 命中的偏好标签去重数 / 候选标签去重数。
     *
     * <p>语义：候选若同时身兼多种风格（如既属自然风景又是城市漫游），对"只偏好自然风景"的用户，
     * 贴合度应低于"身份纯粹"的候选，而不是所有候选都亮同一个百分比。
     * 候选标签 ≤1（或映射缺失）时不做折算，保持原强度；命中标签数受 hit() 保证 ≥1。
     * 排序分/画像分（evaluate/finalScore）不动 —— 只精化"给用户看的匹配度"这一可见字段。
     *
     * <p>示例：候选仅 [自然风景]、画像仅命中自然风景 → 1.0 → 原值；候选 [自然风景,城市漫游]、
     * 画像仅命中自然风景 → 0.5 折算；画像同时命中两者 → 1.0 不减。
     */
    static double visibleMatchScore(ScoreDetail d, List<String> candidateTags) {
        double strength = d.preferenceScore();
        int n = candidateTags == null ? 0 : (int) candidateTags.stream().distinct().count();
        if (n <= 1) {
            return clamp01(strength);
        }
        int m = d.matchedTags() == null ? 0 : (int) d.matchedTags().stream().distinct().count();
        double coverage = Math.min(1.0, (double) Math.max(m, 0) / n);
        return clamp01(strength * coverage);
    }

    /**
     * 排序比较器（Review P2-6 语义收敛）：
     * latest → 保序（DB 已按 updated_at 倒序）；真正个性化（有画像）→ 按统一评分器分；
     * 其余（无画像的 personalized / popular / 空 sort）→ 一律按攻略质量降级排序，
     * 避免「显式传 sort=personalized 却没画像」时仍按评分排的语义矛盾。 */
    private Comparator<RecommendationItem> comparator(String sort, boolean personalized) {
        if ("latest".equalsIgnoreCase(sort)) {
            return Comparator.comparing((RecommendationItem i) -> 0);
        }
        if (personalized) {
            return Comparator.comparing(RecommendationItem::getScore).reversed();
        }
        // 无画像降级：GUIDE_MATCHED/VERIFIED（有真实攻略内容）优先于 POI_ONLY
        return Comparator.comparing((RecommendationItem i) -> qualityRank(i.getDataQuality())).reversed();
    }

    /** 数据可信度等级（VERIFIED > GUIDE_MATCHED > POI_ONLY，热门/最新排序的兜底键） */
    private static int qualityRank(String quality) {
        if (Spot.QUALITY_VERIFIED.equals(quality)) {
            return 2;
        }
        if (Spot.QUALITY_GUIDE_MATCHED.equals(quality)) {
            return 1;
        }
        return 0;
    }

    /**
     * 确定性推荐理由（P0-1/P1-2/P2-2 语义收敛）：
     * <ul>
     *   <li>非个性化流（popular/latest/无画像降级）：一律中性理由，不提"匹配你的偏好/曾去过"——
     *       攻略质量优先 ≠ 热门（P1-2），已去过提示只在个性化语境有意义；</li>
     *   <li>个性化流：硬约束 → 命中偏好（含曾去过后缀） → 曾去过提示 → 数据可信/城市精选。</li>
     * </ul>
     */
    private String reasonOf(ScoreDetail d, String dataQuality, boolean personalizedFeed) {
        String qualityReason = (Spot.QUALITY_GUIDE_MATCHED.equals(dataQuality)
                || Spot.QUALITY_VERIFIED.equals(dataQuality))
                ? "本地攻略收录的真实景点"
                : "城市精选";
        if (!personalizedFeed) {
            return qualityReason;
        }
        if (d.hardAvoid()) {
            return "你近期对「" + d.avoidTag() + "」不感兴趣";
        }
        if (d.hit()) {
            String base = "匹配你的偏好：" + String.join("、", d.matchedTags());
            return d.visited() ? base + "（你曾去过，为你保留熟悉选项）" : base;
        }
        if (d.visited()) {
            return "你曾去过，为你保留熟悉选项";
        }
        return qualityReason;
    }

    /** 用户历史行程中出现过的景点名（新颖性/重复降权输入） */
    public Set<String> collectVisitedNames(String userId) {
        if (userId == null || userId.isBlank()) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        try {
            for (TripRecord r : tripRecordService.getRecentTrips(userId, 20)) {
                Itinerary it = r.getItinerary();
                if (it == null || it.getDays() == null) {
                    continue;
                }
                for (DayPlan d : it.getDays()) {
                    if (d == null || d.getSpots() == null) {
                        continue;
                    }
                    for (SpotItem s : d.getSpots()) {
                        if (s != null && s.getName() != null && !s.getName().isBlank()) {
                            names.add(s.getName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取历史景点名失败: {}", e.getMessage());
        }
        return names;
    }

    /** 当前用户收藏过的 spot_id 集合（isCollected 查询） */
    private Map<String, Boolean> collectedSpotIds(String userId) {
        if (userId == null || userId.isBlank()) {
            return Map.of();
        }
        try {
            List<SpotFavorite> favs = spotFavoriteRepository.selectList(
                    new LambdaQueryWrapper<SpotFavorite>()
                            .eq(SpotFavorite::getUserId, userId));
            return favs.stream().collect(Collectors.toMap(
                    SpotFavorite::getSpotId, f -> Boolean.TRUE, (a, b) -> a));
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 系统内部稳定 ID：spot_{city}_{poiId}（名称只做兼容兜底） */
    public static String spotIdOf(String city, String poiId) {
        return "spot_" + city + "_" + poiId;
    }

    /** 名称规范化（仅匹配用）：共享口径见 {@link SpotNameUtil}（攻略后台景点匹配同源） */
    private static String normalize(String name) {
        return SpotNameUtil.normalize(name);
    }

    private static String str(Object o) {
        return o == null ? null : (o.toString().isBlank() ? null : o.toString());
    }

    private static Double num(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
