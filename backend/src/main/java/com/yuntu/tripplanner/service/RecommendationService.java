package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuntu.tripplanner.model.CandidateEvidence;
import com.yuntu.tripplanner.model.DayPlan;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.MealItem;
import com.yuntu.tripplanner.model.PostFeedLog;
import com.yuntu.tripplanner.model.RecommendationLog;
import com.yuntu.tripplanner.model.SpotItem;
import com.yuntu.tripplanner.model.UserBehavior;
import com.yuntu.tripplanner.model.UserPreference;
import com.yuntu.tripplanner.repository.PostFeedLogRepository;
import com.yuntu.tripplanner.repository.RecommendationLogRepository;
import com.yuntu.tripplanner.repository.UserBehaviorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 推荐日志与推荐理由服务（个性化阶段四：可解释、可追溯）。
 *
 * <p>生成收尾为最终行程里的景点/餐厅写一条 {@link RecommendationLog}，记录它被推荐时
 * 的画像依据（preferenceScore / noveltyScore / finalScore / explanation / 画像版本），
 * 并把可读理由回填到 personalNote 供结果页展示（"为什么推荐你"）。
 *
 * <p><b>口径统一（PLAN §8.1 / 问题二）</b>：打分一律走
 * {@link PersonalizedScoreCalculator}，与候选排序（PersonalizedRankingService）完全同源；
 * 若本次生成有候选证据（{@link CandidateEvidence}，即候选阶段真实算过分），最终入选项
 * 按 名称+桶 匹配后<b>直接复用候选阶段的真实得分</b>（含已体验降权），不再事后重算成
 * "1.0/0.0 的标签"。无证据/非候选池成员才现场用统一计算器补算（无历史依据时新颖性=1）。
 * 景点按 travel_style 域、餐厅按 food 域（名称→口味标签），各自仅在有对应域画像时写入，
 * 无 userId / 无相关画像 → 直接返回（不写日志不打扰）。
 */
@Slf4j
@Service
public class RecommendationService {

    private final UserProfileService userProfileService;
    private final RecommendationLogRepository recommendationLogRepository;
    private final UserBehaviorRepository userBehaviorRepository;
    /** 帖子推荐流曝光日志（阶段三：帖子推荐效果统计的曝光分母） */
    private final PostFeedLogRepository postFeedLogRepository;

    public RecommendationService(UserProfileService userProfileService,
                                 RecommendationLogRepository recommendationLogRepository,
                                 UserBehaviorRepository userBehaviorRepository,
                                 PostFeedLogRepository postFeedLogRepository) {
        this.userProfileService = userProfileService;
        this.recommendationLogRepository = recommendationLogRepository;
        this.userBehaviorRepository = userBehaviorRepository;
        this.postFeedLogRepository = postFeedLogRepository;
    }

    /**
     * 为一次已生成的行程写推荐日志并回填个性化推荐理由（兼容入口，无候选证据）。
     * 等价于 {@code logTripRecommendations(userId, itinerary, null)}。
     *
     * @return 实际写入的推荐日志条数
     */
    public int logTripRecommendations(String userId, Itinerary itinerary) {
        return logTripRecommendations(userId, itinerary, null);
    }

    /**
     * 为一次已生成的行程写推荐日志并回填个性化推荐理由（阶段四主入口）。
     *
     * <p>幂等性由调用方保证（同一行程只调一次）；任一行失败抛异常由事务回滚，
     * 调用方（TripGenerationFinalizer）负责捕获，不影响生成响应。
     *
     * @param evidence 本次生成的候选阶段证据（可为 null/空 = 无候选排序数据，退化为现场打分）
     * @return 实际写入的推荐日志条数
     */
    @Transactional
    public int logTripRecommendations(String userId, Itinerary itinerary,
                                      List<CandidateEvidence> evidence) {
        if (userId == null || userId.isBlank() || itinerary == null || itinerary.getDays() == null) {
            return 0;
        }
        List<UserPreference> prefs;
        try {
            prefs = userProfileService.listPreferences(userId);
        } catch (Exception e) {
            log.warn("读取用户偏好失败（跳过推荐日志）: {}", e.getMessage());
            return 0;
        }
        if (prefs == null || prefs.isEmpty()) {
            return 0;
        }
        // 只处理"与本次可个性化对象相关"的域：景点↔travel_style、餐厅↔food；
        // 仅有 pace/hotel 等无关画像 → 无推荐理由可写，跳过（与个性化前行为一致）
        boolean hasStyle = prefs.stream().anyMatch(
                p -> p != null && UserPreference.CATEGORY_TRAVEL_STYLE.equals(p.getCategory()));
        boolean hasFood = prefs.stream().anyMatch(
                p -> p != null && UserPreference.CATEGORY_FOOD.equals(p.getCategory()));
        if (!hasStyle && !hasFood) {
            return 0;
        }

        Map<String, CandidateEvidence> spotEvidence = indexEvidence(evidence, CandidateEvidence.BUCKET_SPOT);
        Map<String, CandidateEvidence> spotEvidenceByPoi = indexEvidenceByPoi(evidence, CandidateEvidence.BUCKET_SPOT);
        Map<String, CandidateEvidence> mealEvidence = indexEvidence(evidence, CandidateEvidence.BUCKET_RESTAURANT);
        int profileVersion = userProfileService.getProfileVersion(userId);
        int rankingVersion = PersonalizedScoreCalculator.RANKING_VERSION;
        String tripId = itinerary.getTripId();

        int count = 0;
        for (DayPlan day : itinerary.getDays()) {
            if (day == null) {
                continue;
            }
            // 景点桶：仅在有 travel_style 画像时处理
            if (hasStyle && day.getSpots() != null) {
                for (SpotItem spot : day.getSpots()) {
                    if (spot.getName() == null || spot.getName().isBlank()) {
                        continue;
                    }
                    ScoreDetail d = detailOf(spot.getName(), spot.getPoiId(), spot.getPoiType(),
                            false, prefs, spotEvidence, spotEvidenceByPoi);
                    if (d.hit()) {
                        spot.setPersonalNote(d.explanation());
                    }
                    count += writeRow(userId, tripId, spot.getName(),
                            RecommendationLog.ITEM_TYPE_SPOT, d, profileVersion, rankingVersion);
                }
            }
            // 餐厅桶：仅在有 food 画像时处理（口径统一轮起支持，结果页餐饮卡展示理由）
            if (hasFood && day.getMeals() != null) {
                for (MealItem meal : day.getMeals()) {
                    if (meal.getName() == null || meal.getName().isBlank()) {
                        continue;
                    }
                    ScoreDetail d = detailOf(meal.getName(), null, null,
                            true, prefs, mealEvidence, null);
                    if (d.hit()) {
                        meal.setPersonalNote(d.explanation());
                    }
                    count += writeRow(userId, tripId, meal.getName(),
                            RecommendationLog.ITEM_TYPE_RESTAURANT, d, profileVersion, rankingVersion);
                }
            }
        }
        if (count > 0) {
            log.info("推荐日志写入：{}（行程 {}，{} 条，画像 v{}）", userId, tripId, count, profileVersion);
        }
        return count;
    }

    /**
     * 单对象最终打分（P0② OPTIMIZATION_TODO：poi_id 精确关联优先）：
     * 有高德 id 时按 id 命中候选证据 → 复用候选阶段真实得分；
     * 无 id 或未命中再按 名称 匹配；仍无 → 统一计算器现场补算（visited 视为未体验）。
     */
    private ScoreDetail detailOf(String name, String poiId, String poiType, boolean restaurant,
                                 List<UserPreference> prefs,
                                 Map<String, CandidateEvidence> evidenceByName,
                                 Map<String, CandidateEvidence> evidenceByPoi) {
        CandidateEvidence ev = null;
        if (poiId != null && !poiId.isBlank() && evidenceByPoi != null) {
            ev = evidenceByPoi.get(poiId); // 候选证据已记录高德 id（排序阶段从候选 Map 透传）
        }
        if (ev == null) {
            ev = evidenceByName == null ? null : evidenceByName.get(name);
        }
        if (ev != null) {
            boolean hardAvoid = ev.getHardAvoid() != null && ev.getHardAvoid() == 1;
            List<String> matched = splitTags(ev.getMatchedTags());
            String explanation = hardAvoid
                    ? String.format("你近期对「%s」不感兴趣，本次已降低优先级", ev.getAvoidTag())
                    : matched.isEmpty() ? null : "匹配你的偏好：" + String.join("、", matched);
            return new ScoreDetail(
                    num(ev.getPreferenceScore(), 0.0),
                    num(ev.getNoveltyScore(), 1.0),
                    num(ev.getDistancePenalty(), 0.0),
                    num(ev.getFinalScore(), 0.0),
                    matched, ev.getAvoidTag(), hardAvoid,
                    ev.getVisited() != null && ev.getVisited() == 1, explanation);
        }
        // 无候选证据：统一口径现场打分（无历史依据 → visited 恒 false，行为与个性化前一致）
        return PersonalizedScoreCalculator.evaluate(name, poiType, restaurant,
                prefs, Set.of(), Set.of());
    }

    /** 落一行推荐日志 */
    private int writeRow(String userId, String tripId, String itemName, String itemType,
                         ScoreDetail d, int profileVersion, int rankingVersion) {
        RecommendationLog row = new RecommendationLog();
        row.setUserId(userId);
        row.setTripId(tripId);
        row.setItemName(itemName);
        row.setItemType(itemType);
        row.setPreferenceScore(d.preferenceScore());
        row.setNoveltyScore(d.noveltyScore());
        row.setDistancePenalty(d.distancePenalty());
        row.setFinalScore(d.finalScore());
        row.setHitPreference(d.hit() ? 1 : 0);
        row.setExplanation(d.explanation());
        row.setProfileVersion(profileVersion);
        row.setRankingVersion(rankingVersion);
        recommendationLogRepository.insert(row);
        return 1;
    }

    /** 候选证据按 桶+名称 索引（同名只取首条；null 安全） */
    private Map<String, CandidateEvidence> indexEvidence(List<CandidateEvidence> evidence, String bucket) {
        Map<String, CandidateEvidence> index = new HashMap<>();
        if (evidence == null || evidence.isEmpty()) {
            return index;
        }
        for (CandidateEvidence ev : evidence) {
            if (ev == null || !bucket.equals(ev.getBucket()) || ev.getItemName() == null
                    || ev.getItemName().isBlank()) {
                continue;
            }
            index.putIfAbsent(ev.getItemName(), ev);
        }
        return index;
    }

    /** 候选证据按 桶+高德 poi_id 索引（P0②：替换/别名场景下用 id 精确关联真实得分，null 安全） */
    private Map<String, CandidateEvidence> indexEvidenceByPoi(List<CandidateEvidence> evidence, String bucket) {
        Map<String, CandidateEvidence> index = new HashMap<>();
        if (evidence == null || evidence.isEmpty()) {
            return index;
        }
        for (CandidateEvidence ev : evidence) {
            if (ev == null || !bucket.equals(ev.getBucket()) || ev.getPoiId() == null
                    || ev.getPoiId().isBlank()) {
                continue;
            }
            index.putIfAbsent(ev.getPoiId(), ev);
        }
        return index;
    }

    /** "/" 分隔的命中标签拆成列表（空串→空表） */
    private List<String> splitTags(String matchedTags) {
        List<String> tags = new ArrayList<>();
        if (matchedTags == null || matchedTags.isBlank()) {
            return tags;
        }
        for (String t : matchedTags.split("/")) {
            if (t != null && !t.isBlank()) {
                tags.add(t.trim());
            }
        }
        return tags;
    }

    private static double num(Double v, double dft) {
        return v == null ? dft : v;
    }

    /**
     * 个性化效果统计（实验评估数据来源，PLAN 11.2）：
     * 偏好命中率 = hit 推荐日志 / 全部推荐日志；
     * 负反馈率 = (DISLIKE+REPLACE) 行为 / 全部行为；
     * 满意度均值 = RATE 行为评分均值；
     * 帖子推荐效果（阶段三任务 8）= 帖子推荐流曝光为分母，user_behavior(item_type=POST) 的
     * 点击/收藏/不感兴趣为分子 → 点击率/收藏率/负反馈率。
     * 失败静默返回零值，不影响主流程。
     */
    public ProfileStats collectStats(String userId) {
        ProfileStats stats = new ProfileStats();
        if (userId == null || userId.isBlank()) {
            return stats;
        }
        try {
            // 1) 偏好命中率：基于 recommendation_log（每次生成的景点都有日志）
            Long total = recommendationLogRepository.selectCount(
                    new LambdaQueryWrapper<RecommendationLog>()
                            .eq(RecommendationLog::getUserId, userId));
            Long hit = recommendationLogRepository.selectCount(
                    new LambdaQueryWrapper<RecommendationLog>()
                            .eq(RecommendationLog::getUserId, userId)
                            .eq(RecommendationLog::getHitPreference, 1));
            stats.setRecommendationCount(total == null ? 0 : total);
            if (total != null && total > 0) {
                stats.setPreferenceHitRate(Math.round((hit == null ? 0 : hit) * 1000.0 / total) / 10.0);
            }

            // 2) 负反馈率与满意度均值：基于 user_behavior
            List<UserBehavior> behaviors = userBehaviorRepository.selectList(
                    new LambdaQueryWrapper<UserBehavior>().eq(UserBehavior::getUserId, userId));
            if (behaviors != null && !behaviors.isEmpty()) {
                long negative = behaviors.stream()
                        .filter(b -> UserBehavior.ACTION_DISLIKE.equals(b.getActionType())
                                || UserBehavior.ACTION_REPLACE.equals(b.getActionType()))
                        .count();
                stats.setDislikeRate(Math.round(negative * 1000.0 / behaviors.size()) / 10.0);
                double ratingSum = behaviors.stream()
                        .filter(b -> UserBehavior.ACTION_RATE.equals(b.getActionType()) && b.getRating() != null)
                        .mapToInt(UserBehavior::getRating)
                        .sum();
                long ratingCount = behaviors.stream()
                        .filter(b -> UserBehavior.ACTION_RATE.equals(b.getActionType()) && b.getRating() != null)
                        .count();
                if (ratingCount > 0) {
                    stats.setAvgRating(Math.round(ratingSum * 10.0 / ratingCount) / 10.0);
                }
            }
            // 3) 帖子推荐流效果（阶段三）：曝光分母 + POST 行为分子（行为列表可空 → 计数为 0）
            long postClicks = 0, postFavorites = 0, postDislikes = 0;
            if (behaviors != null && !behaviors.isEmpty()) {
                postClicks = behaviors.stream()
                        .filter(b -> UserBehavior.ITEM_TYPE_POST.equals(b.getItemType())
                                && UserBehavior.ACTION_CLICK.equals(b.getActionType()))
                        .count();
                postFavorites = behaviors.stream()
                        .filter(b -> UserBehavior.ITEM_TYPE_POST.equals(b.getItemType())
                                && UserBehavior.ACTION_SAVE.equals(b.getActionType()))
                        .count();
                postDislikes = behaviors.stream()
                        .filter(b -> UserBehavior.ITEM_TYPE_POST.equals(b.getItemType())
                                && UserBehavior.ACTION_DISLIKE.equals(b.getActionType()))
                        .count();
            }
            collectPostFeedStats(userId, stats, postClicks, postFavorites, postDislikes);
        } catch (Exception e) {
            log.warn("统计个性化效果失败: {}", e.getMessage());
        }
        return stats;
    }

    /** 帖子推荐曝光统计（独立 try，防曝光表缺失/未初始化拖垮整体统计） */
    private void collectPostFeedStats(String userId, ProfileStats stats,
                                      long postClicks, long postFavorites, long postDislikes) {
        try {
            Long exposures = postFeedLogRepository.selectCount(
                    new LambdaQueryWrapper<PostFeedLog>()
                            .eq(PostFeedLog::getUserId, userId));
            long exposureCount = exposures == null ? 0 : exposures;
            stats.setPostExposureCount(exposureCount);
            if (exposureCount > 0) {
                stats.setPostClickRate(Math.round(postClicks * 1000.0 / exposureCount) / 10.0);
                stats.setPostFavoriteRate(Math.round(postFavorites * 1000.0 / exposureCount) / 10.0);
                stats.setPostDislikeRate(Math.round(postDislikes * 1000.0 / exposureCount) / 10.0);
            }
        } catch (Exception e) {
            log.warn("帖子推荐效果统计失败: {}", e.getMessage());
        }
    }

    /** 阶段四统计结果载体（JSON 序列化给前端展示） */
    public static class ProfileStats {
        private long recommendationCount = 0;
        private double preferenceHitRate = 0;
        private double dislikeRate = 0;
        private double avgRating = 0;
        /** 帖子推荐曝光条数（阶段三：post_feed_log 曝光分母） */
        private long postExposureCount = 0;
        /** 帖子推荐点击率（0~100，%）：POST CLICK / 曝光 */
        private double postClickRate = 0;
        /** 帖子推荐收藏率（0~100，%）：POST SAVE / 曝光 */
        private double postFavoriteRate = 0;
        /** 帖子推荐负反馈率（0~100，%）：POST DISLIKE / 曝光 */
        private double postDislikeRate = 0;

        public long getRecommendationCount() {
            return recommendationCount;
        }

        public void setRecommendationCount(long recommendationCount) {
            this.recommendationCount = recommendationCount;
        }

        public double getPreferenceHitRate() {
            return preferenceHitRate;
        }

        public void setPreferenceHitRate(double preferenceHitRate) {
            this.preferenceHitRate = preferenceHitRate;
        }

        public double getDislikeRate() {
            return dislikeRate;
        }

        public void setDislikeRate(double dislikeRate) {
            this.dislikeRate = dislikeRate;
        }

        public double getAvgRating() {
            return avgRating;
        }

        public void setAvgRating(double avgRating) {
            this.avgRating = avgRating;
        }

        public long getPostExposureCount() {
            return postExposureCount;
        }

        public void setPostExposureCount(long postExposureCount) {
            this.postExposureCount = postExposureCount;
        }

        public double getPostClickRate() {
            return postClickRate;
        }

        public void setPostClickRate(double postClickRate) {
            this.postClickRate = postClickRate;
        }

        public double getPostFavoriteRate() {
            return postFavoriteRate;
        }

        public void setPostFavoriteRate(double postFavoriteRate) {
            this.postFavoriteRate = postFavoriteRate;
        }

        public double getPostDislikeRate() {
            return postDislikeRate;
        }

        public void setPostDislikeRate(double postDislikeRate) {
            this.postDislikeRate = postDislikeRate;
        }
    }
}
