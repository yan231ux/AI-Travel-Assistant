package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuntu.tripplanner.common.SpotNotFoundException;
import com.yuntu.tripplanner.common.SpotTagMapper;
import com.yuntu.tripplanner.common.SpotText;
import com.yuntu.tripplanner.common.SpotVisibility;
import com.yuntu.tripplanner.model.*;
import com.yuntu.tripplanner.repository.SpotFavoriteRepository;
import com.yuntu.tripplanner.repository.SpotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 景点详情与收藏服务（产品化阶段一，PRODUCT_EVOLUTION_PLAN §6）。
 *
 * <p>详情页数据来自 spot 底座（高德同步 + RAG 攻略增强），visited 由历史行程
 * 实时判定（展示"你曾去过"），收藏走独立 {@code user_spot_favorite} 表（幂等）。
 *
 * <p>收藏与画像行为的事务边界（Review P1-1）：收藏行写入、SAVE 行为留痕、画像权重
 * 增量更新收进同一个 {@link #favorite(String, String)} @Transactional 方法内 ——
 * 任一环节失败整体回滚，杜绝「已收藏但画像没学到 / 画像变了但没收藏上」的不一致；
 * Controller 只负责把结果映射成 HTTP。
 *
 * <p>幂等与资源校验：景点必须真实存在（不存在抛 {@link SpotNotFoundException} → 404，
 * Review P1-2）；重复收藏靠 (user_id, spot_id) 唯一键 + 重复键异常捕获（并发场景同样
 * 返回"已收藏"，不 500，Review P1-3）。
 */
@Slf4j
@Service
public class SpotService {

    private final SpotRepository spotRepository;
    private final SpotFavoriteRepository spotFavoriteRepository;
    private final TripRecordService tripRecordService;
    private final UserProfileService userProfileService;
    private final TravelEventService travelEventService;

    public SpotService(SpotRepository spotRepository,
                       SpotFavoriteRepository spotFavoriteRepository,
                       TripRecordService tripRecordService,
                       UserProfileService userProfileService,
                       TravelEventService travelEventService) {
        this.spotRepository = spotRepository;
        this.spotFavoriteRepository = spotFavoriteRepository;
        this.tripRecordService = tripRecordService;
        this.userProfileService = userProfileService;
        this.travelEventService = travelEventService;
    }

    /**
     * 景点详情（GET /spots/{spotId}）。
     *
     * @return 详情；spotId 不存在返回 null（404 由 controller 处理）
     */
    public SpotDetail detail(String userId, String spotId) {
        if (spotId == null || spotId.isBlank()) {
            return null;
        }
        Spot spot = spotRepository.selectOne(
                new LambdaQueryWrapper<Spot>().eq(Spot::getSpotId, spotId).last("LIMIT 1"));
        if (spot == null) {
            return null;
        }
        // B3 景点治理：已下线/合并别名/异常标记的景点对外不可见（详情按不存在处理，收藏已被合并重定向）
        if (!SpotVisibility.isActive(spot)) {
            return null;
        }
        SpotDetail d = new SpotDetail();
        d.setSpotId(spot.getSpotId());
        d.setPoiId(spot.getPoiId());
        d.setName(spot.getName());
        d.setCity(spot.getCity());
        d.setAddress(spot.getAddress());
        d.setLongitude(spot.getLongitude());
        d.setLatitude(spot.getLatitude());
        d.setCategory(spot.getCategory());
        d.setImageUrl(spot.getImageUrl());
        d.setDescription(SpotText.safeDescription(spot));
        d.setTags(SpotTagMapper.styleTags(spot.getCategory(), spot.getName()));
        d.setSource(spot.getSource());
        d.setDataQuality(spot.getDataQuality());
        d.setVisited(isVisited(userId, spot));
        d.setCollected(isCollected(userId, spot.getSpotId()));
        // 相关推荐：同城其余景点，简单取同城 GUIDE_MATCHED 优先前 6
        d.setRelatedSpots(relatedSpots(userId, spot));
        return d;
    }

    /** 是否在历史行程中出现过（名称命中；null/空 userId 恒 false） */
    private boolean isVisited(String userId, Spot spot) {
        if (userId == null || userId.isBlank() || spot == null || spot.getName() == null) {
            return false;
        }
        try {
            for (TripRecord r : tripRecordService.getRecentTrips(userId, 30)) {
                Itinerary it = r.getItinerary();
                if (it == null || it.getDays() == null) {
                    continue;
                }
                for (DayPlan day : it.getDays()) {
                    if (day == null || day.getSpots() == null) {
                        continue;
                    }
                    for (SpotItem s : day.getSpots()) {
                        if (s != null && spot.getName().equals(s.getName())) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("visited 判定失败: {}", e.getMessage());
        }
        return false;
    }

    private boolean isCollected(String userId, String spotId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        return spotFavoriteRepository.selectCount(
                new LambdaQueryWrapper<SpotFavorite>()
                        .eq(SpotFavorite::getUserId, userId)
                        .eq(SpotFavorite::getSpotId, spotId)) > 0;
    }

    /** 同城相关推荐（最多 6；同城攻略质量优先，再按更新时间） */
    private List<RecommendationItem> relatedSpots(String userId, Spot current) {
        List<Spot> sameCity = spotRepository.selectList(
                new LambdaQueryWrapper<Spot>()
                        .eq(Spot::getCity, current.getCity())
                        .ne(Spot::getSpotId, current.getSpotId())
                        .orderByDesc(Spot::getUpdatedAt)
                        .last("LIMIT 30"));
        if (sameCity.isEmpty()) {
            return List.of();
        }
        // B3 景点治理：同城相关推荐只取正常运营中的景点
        sameCity = sameCity.stream().filter(SpotVisibility::isActive).collect(Collectors.toList());
        if (sameCity.isEmpty()) {
            return List.of();
        }
        Set<String> visitedNames = collectVisitedNames(userId);
        // Review 复查 #5：画像查询只做一次（此前在遍历中每候选都查 → 相关推荐 N+1）
        List<UserPreference> prefs = (userId == null || userId.isBlank())
                ? List.of()
                : userProfileService.listPreferences(userId);
        List<Spot> byQuality = sameCity.stream()
                .sorted(Comparator
                        .comparing((Spot s) -> qualityRank(s.getDataQuality())).reversed()
                        .thenComparing(Spot::getSpotId))
                .limit(6)
                .collect(Collectors.toList());
        Map<String, Boolean> collected = collectedMap(userId);
        return byQuality.stream().map(s -> {
            RecommendationItem item = new RecommendationItem();
            item.setSpotId(s.getSpotId());
            item.setPoiId(s.getPoiId());
            item.setName(s.getName());
            item.setCity(s.getCity());
            item.setCategory(s.getCategory());
            item.setImageUrl(s.getImageUrl());
            item.setDescription(truncate(SpotText.safeDescription(s), 80));
            item.setTags(SpotTagMapper.styleTags(s.getCategory(), s.getName()));
            item.setSource(s.getSource());
            item.setDataQuality(s.getDataQuality());
            item.setCollected(Boolean.TRUE.equals(collected.get(s.getSpotId())));
            ScoreDetail d = PersonalizedScoreCalculator.evaluate(
                    s.getName(), s.getCategory(), false, prefs, visitedNames, Set.of());
            item.setScore(d.finalScore());
            // 相关推荐 = 同城精选（攻略质量优先），不是"为你推荐"流：
            // 不暴露 personalized/match 字段（前端不显示"匹配度%"），理由只讲数据可信，不说成热门
            item.setRecommendReason(Spot.QUALITY_POI_ONLY.equals(s.getDataQuality())
                    ? "城市精选" : "本地攻略收录的真实景点");
            return item;
        }).collect(Collectors.toList());
    }

    /** 历史行程景点名集合（供相关推荐做"是否去过"标记/排序辅助） */
    private Set<String> collectVisitedNames(String userId) {
        Set<String> names = new HashSet<>();
        if (userId == null || userId.isBlank()) {
            return names;
        }
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
            log.debug("读取历史景点名失败: {}", e.getMessage());
        }
        return names;
    }

    /* ================= 收藏（独立表，幂等；与画像行为同事务） ================= */

    /**
     * 收藏景点（幂等 + 资源校验 + 与画像更新同事务，Review P1-1/2/3）。
     *
     * <ol>
     *   <li>景点不存在 → {@link SpotNotFoundException}（Controller 映射 404）；</li>
     *   <li>已收藏 → 直接返回 existed（不再重复 SAVE 升权）；</li>
     *   <li>写收藏行 → 触发 SAVE 行为 → 画像权重增量，全程同一事务：
     *       中途失败整体回滚，不会留下「已收藏但画像未学习」的脏状态；</li>
     *   <li>并发重复收藏：唯一键冲突被捕获，视为"已收藏"幂等返回，不抛 500。</li>
     * </ol>
     *
     * @return 收藏结果（是否本次新增 + 画像实际调整，供 Controller 组装响应）
     */
    @Transactional
    public FavoriteResult favorite(String userId, String spotId) {
        if (userId == null || userId.isBlank() || spotId == null || spotId.isBlank()) {
            throw new IllegalArgumentException("缺少用户或景点标识");
        }
        Spot spot = requireSpot(spotId);
        if (isCollected(userId, spotId)) {
            return FavoriteResult.existed();
        }

        SpotFavorite fav = new SpotFavorite();
        fav.setUserId(userId);
        fav.setSpotId(spotId);
        fav.setPoiId(spot.getPoiId());
        fav.setName(spot.getName());
        fav.setCity(spot.getCity());
        fav.setImageUrl(spot.getImageUrl());
        try {
            spotFavoriteRepository.insert(fav);
        } catch (DuplicateKeyException e) {
            // 并发收藏：另一请求先插入 (user_id, spot_id) → 幂等视为已收藏
            log.debug("并发收藏命中唯一键，幂等返回：user={} spot={}", userId, spotId);
            return FavoriteResult.existed();
        }

        // 新收藏 → 同一事务内 SAVE 行为 + 画像增量（失败整体回滚，收藏行一并消失）
        List<PreferenceAdjustment> adjustments = trackSave(userId, spot);
        // 行程事件埋点（阶段二数据地基）：SPOT_FAVORITED；旁路埋点失败不影响收藏事务
        try {
            travelEventService.recordSpotFavorited(userId, spot);
        } catch (Exception e) {
            log.warn("收藏事件埋点失败（不影响收藏）: {}", e.getMessage());
        }
        return FavoriteResult.created(adjustments);
    }

    /** SAVE 行为留痕 + 画像增量（与收藏写库同事务，由调用方保证） */
    private List<PreferenceAdjustment> trackSave(String userId, Spot spot) {
        return userProfileService.recordBehavior(userId, behaviorRequest(spot, UserBehavior.ACTION_SAVE));
    }

    /**
     * 取消收藏（幂等：未收藏也不报错）。景点不存在 → 404。
     *
     * <p>画像可撤销（PERSONALIZATION_PLAN §5.4）：收藏按 SAVE +0.15 提权，取消时按 UNSAVE -0.10
     * 回退 —— 幅度严格小于增加（回退 < 增加，避免"收藏→取消"反复刷高权重），且不会把权重
     * 压进「近期不感兴趣」区间（细则见 {@code UserProfileService.REVOKE_ACTIONS}）。
     * 只有确实删掉了收藏行才回退；重复调用（幂等空删）不扣分，避免刷接口反复扣权重。
     */
    @Transactional
    public List<PreferenceAdjustment> unfavorite(String userId, String spotId) {
        if (userId == null || userId.isBlank() || spotId == null || spotId.isBlank()) {
            throw new IllegalArgumentException("缺少用户或景点标识");
        }
        Spot spot = requireSpot(spotId);
        int removed = spotFavoriteRepository.delete(
                new LambdaQueryWrapper<SpotFavorite>()
                        .eq(SpotFavorite::getUserId, userId)
                        .eq(SpotFavorite::getSpotId, spotId));
        if (removed <= 0) {
            return List.of(); // 本来就没收藏：无正向贡献可回退
        }
        return userProfileService.recordBehavior(userId, behaviorRequest(spot, UserBehavior.ACTION_UNSAVE));
    }

    /** 行为请求组装（SAVE/UNSAVE 共用；item_id 优先系统稳定 spot_id，缺失时退回 poi_id） */
    private BehaviorRequest behaviorRequest(Spot spot, String actionType) {
        BehaviorRequest req = new BehaviorRequest();
        req.setItemType(UserBehavior.ITEM_TYPE_SPOT);
        // 审查报告 P1-6：行为主键用系统稳定 spot_id（缺失时退回 poi_id），
        // 保证 poi_id 为空的景点收藏也能进入推荐监控反馈漏斗。
        req.setItemId(spot.getSpotId() != null && !spot.getSpotId().isBlank()
                ? spot.getSpotId() : spot.getPoiId());
        req.setItemName(spot.getName());
        req.setPoiType(spot.getCategory());
        req.setActionType(actionType);
        return req;
    }

    /** 收藏/取消收藏结果载体（服务内聚结果，Controller 负责转 HTTP） */
    public static class FavoriteResult {
        private final boolean newly;
        private final List<PreferenceAdjustment> adjustments;

        private FavoriteResult(boolean newly, List<PreferenceAdjustment> adjustments) {
            this.newly = newly;
            this.adjustments = adjustments == null ? List.of() : adjustments;
        }

        /** 已收藏过 / 并发幂等命中 */
        public static FavoriteResult existed() {
            return new FavoriteResult(false, List.of());
        }

        /** 本次新增收藏（含画像调整结果） */
        public static FavoriteResult created(List<PreferenceAdjustment> adjustments) {
            return new FavoriteResult(true, adjustments);
        }

        public boolean isNewly() {
            return newly;
        }

        public List<PreferenceAdjustment> getAdjustments() {
            return adjustments;
        }
    }

    /** 景点必须存在：查无 → 抛 SpotNotFoundException（防伪造 spot_id 产生无效收藏） */
    private Spot requireSpot(String spotId) {
        Spot spot = spotRepository.selectOne(
                new LambdaQueryWrapper<Spot>().eq(Spot::getSpotId, spotId).last("LIMIT 1"));
        if (spot == null) {
            throw new SpotNotFoundException(spotId);
        }
        return spot;
    }

    /** 我的收藏列表（GET /user/spot-favorites；快照字段直出，分页） */
    public List<SpotFavorite> listFavorites(String userId, int page, int pageSize) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        int size = Math.max(1, Math.min(pageSize <= 0 ? 12 : pageSize, 50));
        int pageNo = Math.max(1, page);
        return spotFavoriteRepository.selectList(
                new LambdaQueryWrapper<SpotFavorite>()
                        .eq(SpotFavorite::getUserId, userId)
                        .orderByDesc(SpotFavorite::getCreatedAt)
                        .last("LIMIT " + size + " OFFSET " + (pageNo - 1) * size));
    }

    public long countFavorites(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        return spotFavoriteRepository.selectCount(
                new LambdaQueryWrapper<SpotFavorite>().eq(SpotFavorite::getUserId, userId));
    }

    private Map<String, Boolean> collectedMap(String userId) {
        if (userId == null || userId.isBlank()) {
            return Map.of();
        }
        try {
            return spotFavoriteRepository.selectList(
                            new LambdaQueryWrapper<SpotFavorite>().eq(SpotFavorite::getUserId, userId))
                    .stream().collect(Collectors.toMap(
                            SpotFavorite::getSpotId, f -> Boolean.TRUE, (a, b) -> a));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static int qualityRank(String quality) {
        if (Spot.QUALITY_VERIFIED.equals(quality)) {
            return 2;
        }
        if (Spot.QUALITY_GUIDE_MATCHED.equals(quality)) {
            return 1;
        }
        return 0;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
