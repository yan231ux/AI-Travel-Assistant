package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuntu.tripplanner.client.AmapClient;
import com.yuntu.tripplanner.common.AbBucket;
import com.yuntu.tripplanner.common.SpotTagMapper;
import com.yuntu.tripplanner.common.SpotText;
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

    public RecommendationFeedService(SpotRepository spotRepository,
                                     SpotFavoriteRepository spotFavoriteRepository,
                                     SpotFeedLogRepository spotFeedLogRepository,
                                     AmapClient amapClient,
                                     RagService ragService,
                                     UserProfileService userProfileService,
                                     TripRecordService tripRecordService,
                                     AbExperimentService abExperimentService) {
        this.spotRepository = spotRepository;
        this.spotFavoriteRepository = spotFavoriteRepository;
        this.spotFeedLogRepository = spotFeedLogRepository;
        this.amapClient = amapClient;
        this.ragService = ragService;
        this.userProfileService = userProfileService;
        this.tripRecordService = tripRecordService;
        this.abExperimentService = abExperimentService;
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

        try {
            syncCityIfStale(cityKey);
        } catch (Exception e) {
            log.warn("城市景点同步失败（用已有快照兜底）: {} - {}", cityKey, e.getMessage());
        }

        List<Spot> spots = spotRepository.selectList(
                new LambdaQueryWrapper<Spot>()
                        .eq(Spot::getCity, cityKey)
                        .orderByDesc(Spot::getUpdatedAt));
        if (spots.isEmpty()) {
            result.setItems(List.of());
            result.setPage(pageNo);
            result.setPageSize(size);
            result.setTotal(0L);
            result.setPersonalized(false);
            result.setProfileVersion(userId == null ? 0 : userProfileService.getProfileVersion(userId));
            return result;
        }

        // A/B 实验（阶段四任务 6，spot_feed_quality_gate）：命中处理组 → "为你推荐"的候选池
        // 只保留有真实攻略内容的景点（GUIDE_MATCHED/VERIFIED，剔除纯高德 POI_ONLY）。
        // 过滤后不足下限则整体回退全量（数据稀疏城市不空流）；热门/最新 Tab 是用户显式浏览，
        // 不参与该实验。变体由粘性分桶决定（AbExperimentService.resolveVariant），无实验/匿名 → null（基线）。
        String abVariant = wantPersonalized(sort) && userId != null && !userId.isBlank()
                ? abExperimentService.resolveVariant(userId, AbExperimentService.EXP_SPOT_QUALITY_GATE)
                : null;
        if (AbBucket.VARIANT_TREATMENT.equals(abVariant)) {
            List<Spot> guided = spots.stream()
                    .filter(s -> s.getDataQuality() != null
                            && !Spot.QUALITY_POI_ONLY.equals(s.getDataQuality()))
                    .collect(Collectors.toList());
            if (guided.size() >= MIN_QUALITY_POOL) {
                log.info("A/B[{}] 处理组生效: user={} city={} 候选池 {} → {}",
                        AbExperimentService.EXP_SPOT_QUALITY_GATE, userId, cityKey,
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

        // 内存分页（单城候选量 30~60，规模小无需 SQL 分页）
        int from = Math.min((pageNo - 1) * size, ranked.size());
        int to = Math.min(from + size, ranked.size());
        result.setItems(ranked.subList(from, to));
        result.setPage(pageNo);
        result.setPageSize(size);
        result.setTotal(total);
        result.setPersonalized(personalized);
        result.setProfileVersion(userId == null ? 0 : userProfileService.getProfileVersion(userId));

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
                existing.setName(name);
                existing.setAddress(str(poi.get("address")));
                existing.setCategory(str(poi.get("type")));
                existing.setImageUrl(str(poi.get("image_url")));
                existing.setLongitude(num(poi.get("longitude")));
                existing.setLatitude(num(poi.get("latitude")));
                existing.setLastSyncedAt(LocalDateTime.now());
                // Review P2-4：已有 POI 也重新做一次 RAG 增强——该景点后续命中攻略卡片时，
                // 只会在命中时覆盖简介/地址并升级 data_quality（POI_ONLY → GUIDE_MATCHED）
                enrichFromGuide(existing, city);
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
        item.setDescription(SpotText.safeDescription(spot));
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
        if (personalizedFeed && d.hit()) {
            item.setPersonalized(Boolean.TRUE);
            item.setMatchScore(clamp01(d.preferenceScore()));
            item.setMatchedPreferences(d.matchedTags());
        }
        item.setRecommendReason(reasonOf(d, spot.getDataQuality(), personalizedFeed));
        return item;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
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

    /** 名称规范化（仅匹配用）：去空白/全半角括号/行政区划后缀 */
    private static String normalize(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("[\\s\\u3000（）()]", "")
                .replaceAll("(风景区|景区|公园|古镇|老街|景点)$", "");
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
