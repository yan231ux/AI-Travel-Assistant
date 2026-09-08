package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.common.SpotTagMapper;
import com.yuntu.tripplanner.common.PostTagResolver;
import com.yuntu.tripplanner.model.UserPreference;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 个性化统一评分计算器（PLAN §8.1：候选排序与推荐日志共用同一打分口径，防两套算法漂移）。
 *
 * <p>输入（候选名/高德 type/是否餐厅/画像偏好/已体验集合/点名豁免集）→ 输出
 * {@link ScoreDetail}：偏好匹配分（Σ 权重×置信度）、新颖性、最终分、命中标签、回避标签等。
 * 纯静态、无 IO，方便单测；规则与阶段三 {@link PersonalizedRankingService} 一致：
 * <ul>
 *   <li>正偏好：同域标签命中 → boost += 权重×置信度；</li>
 *   <li>硬约束：非问卷来源且权重跌破 {@link UserProfileService#POSITIVE_WEIGHT_MIN} 的
 *       回避标签命中 → 沉底（点名景点豁免）；</li>
 *   <li>新颖性：候选名在历史行程出现过 → novelty=0.75，最终分减 0.25 再乘 0.6；</li>
 *   <li>最终分：hardAvoid ? 0 : clamp01(0.5 + (boost − visited惩罚) × 0.6)。</li>
 * </ul>
 */
public final class PersonalizedScoreCalculator {

    /** 排序算法版本（统一口径版本号：候选证据与推荐日志共用，规则变更后 bump 以隔离旧数据） */
    public static final int RANKING_VERSION = 1;

    /** 已体验候选的新颖性分 */
    public static final double VISITED_NOVELTY_SCORE = 0.75;

    /** 已体验候选的最终分惩罚（0.25，与阶段三排序实现保持一致） */
    public static final double VISITED_PENALTY = 0.25;

    private PersonalizedScoreCalculator() {
    }

    /**
     * 对单个候选打分（统一口径入口）。
     *
     * @param name        候选名称（景点/餐厅）
     * @param poiType     高德业态 type（仅景点用；餐厅由名称映射口味）
     * @param restaurant  是否餐厅桶（true=按 food 域与名称口味映射，false=按 travel_style 域）
     * @param prefs       用户画像偏好全量（内部按域过滤）
     * @param visitedNames 历史行程出现过的景点名（可为空集）
     * @param exempt      点名景点豁免集（命中回避标签时不沉底）
     */
    public static ScoreDetail evaluate(String name, String poiType, boolean restaurant,
                                       List<UserPreference> prefs,
                                       Set<String> visitedNames, Set<String> exempt) {
        List<String> tags = restaurant
                ? SpotTagMapper.foodTags(name)
                : SpotTagMapper.styleTags(poiType, name);
        String category = restaurant
                ? UserPreference.CATEGORY_FOOD
                : UserPreference.CATEGORY_TRAVEL_STYLE;
        double posMin = UserProfileService.POSITIVE_WEIGHT_MIN;

        double boost = 0;
        List<String> matched = new ArrayList<>();
        String avoidTag = null;
        boolean hardAvoid = false;
        if (prefs != null && !tags.isEmpty()) {
            for (UserPreference p : prefs) {
                if (p == null || !category.equals(p.getCategory())
                        || p.getTag() == null || !tags.contains(p.getTag())) {
                    continue;
                }
                double weight = p.getWeight() == null ? 0.5 : p.getWeight();
                if (weight < posMin && !UserPreference.SOURCE_QUESTIONNAIRE.equals(p.getSource())) {
                    // 硬约束：非问卷来源、权重跌破阈值的回避标签（用户近期不感兴趣）
                    if (exempt == null || !exempt.contains(name)) {
                        hardAvoid = true;
                        avoidTag = p.getTag();
                    }
                } else {
                    double confidence = p.getConfidence() == null ? 0.5 : p.getConfidence();
                    boost += weight * confidence;
                    matched.add(p.getTag());
                }
            }
        }
        boolean visited = visitedNames != null && visitedNames.contains(name);
        double finalScore = hardAvoid
                ? 0.0
                : clamp01(0.5 + (boost - (visited ? VISITED_PENALTY : 0)) * 0.6);
        String explanation = null;
        if (hardAvoid) {
            explanation = String.format("你近期对「%s」不感兴趣，本次已降低优先级", avoidTag);
        } else if (!matched.isEmpty()) {
            explanation = "匹配你的偏好：" + String.join("、", matched);
        }
        return new ScoreDetail(boost, visited ? VISITED_NOVELTY_SCORE : 1.0, 0.0,
                finalScore, List.copyOf(matched), avoidTag, hardAvoid, visited, explanation);
    }

    /** 帖子推荐流算法版本（阶段三任务 7：与景点/行程的 RANKING_VERSION=1 区分，
     *  帖子推荐流规则变更后 bump 以隔离旧曝光日志与新画像行为的统计口径） */
    public static final int POST_FEED_RANKING_VERSION = 2;

    /** 帖子可命中的正偏好域（帖子标签跨 travel_style/food/pace/city 多域；PostFeedEngine 判定个性化也用） */
    static final Set<String> POST_POSITIVE_DOMAINS = Set.of(
            UserPreference.CATEGORY_TRAVEL_STYLE, UserPreference.CATEGORY_FOOD,
            UserPreference.CATEGORY_PACE, UserPreference.CATEGORY_CITY);
    /** 可触发回避硬约束的域（city 域只做正向加权，不做"不喜欢这个城市"的负向语义） */
    private static final Set<String> POST_AVOID_DOMAINS = Set.of(
            UserPreference.CATEGORY_TRAVEL_STYLE, UserPreference.CATEGORY_FOOD,
            UserPreference.CATEGORY_PACE);

    /**
     * 对一篇帖子打分（阶段三：帖子推荐流与帖子行为闭环的统一口径入口）。
     *
     * <p>帖子标签跨多个偏好域（风格/口味/节奏/城市，见 {@link PostTagResolver}），
     * 与景点单域评估不同：只要画像里任一域的行（category, tag）命中帖子标签即累计加权；
     * 回避语义沿用统一阈值（非问卷来源且权重跌破 {@link UserProfileService#POSITIVE_WEIGHT_MIN}）
     * —— 用户连续对同类帖子"不感兴趣"后，命中该回避标签的帖子直接沉底（0 分），
     * 与景点负反馈共用同一套画像行，保证「帖子不感兴趣 → 景点/帖子同类内容同时降权」。
     * 最终分公式与景点口径一致：clamp01(0.5 + boost × 0.6)，防两套算法漂移。
     *
     * @param postTags 帖子的画像标签名集合（跨域，如 {历史文化, 轻松, 大理}）
     * @param prefs    用户画像偏好全量
     * @return 打分详情（matched=命中正偏好标签；hardAvoid=命中回避标签沉底）
     */
    public static ScoreDetail evaluatePost(Collection<String> postTags, List<UserPreference> prefs) {
        Set<String> tags = new LinkedHashSet<>();
        if (postTags != null) {
            tags.addAll(postTags);
        }
        double posMin = UserProfileService.POSITIVE_WEIGHT_MIN;
        double boost = 0;
        List<String> matched = new ArrayList<>();
        String avoidTag = null;
        boolean hardAvoid = false;
        if (prefs != null && !tags.isEmpty()) {
            for (UserPreference p : prefs) {
                if (p == null || p.getTag() == null || !tags.contains(p.getTag())
                        || !POST_POSITIVE_DOMAINS.contains(p.getCategory())) {
                    continue;
                }
                double weight = p.getWeight() == null ? 0.5 : p.getWeight();
                boolean avoidable = POST_AVOID_DOMAINS.contains(p.getCategory());
                if (avoidable && weight < posMin && !UserPreference.SOURCE_QUESTIONNAIRE.equals(p.getSource())) {
                    hardAvoid = true;
                    avoidTag = p.getTag();
                } else {
                    double confidence = p.getConfidence() == null ? 0.5 : p.getConfidence();
                    boost += weight * confidence;
                    if (!matched.contains(p.getTag()) && matched.size() < 4) {
                        matched.add(p.getTag());
                    }
                }
            }
        }
        double finalScore = hardAvoid ? 0.0 : clamp01(0.5 + boost * 0.6);
        String explanation = null;
        if (hardAvoid) {
            explanation = String.format("你近期对「%s」不感兴趣，同类内容已降低优先级", avoidTag);
        } else if (!matched.isEmpty()) {
            explanation = "匹配你的偏好：" + String.join("、", matched);
        }
        return new ScoreDetail(boost, 1.0, 0.0, finalScore, List.copyOf(matched),
                avoidTag, hardAvoid, false, explanation);
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
