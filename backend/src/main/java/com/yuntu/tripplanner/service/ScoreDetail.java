package com.yuntu.tripplanner.service;

import java.util.List;

/**
 * 单候选个性化打分结果（统一口径载体，PLAN §8.1）。
 *
 * <p>由 {@link PersonalizedScoreCalculator#evaluate} 产出，候选排序
 * （{@link PersonalizedRankingService}）与推荐日志（{@link RecommendationService}）
 * 共同消费，保证"排序依据"与"落库依据"完全一致、可追溯。
 *
 * @param preferenceScore  偏好匹配分（命中画像 权重×置信度 累加）
 * @param noveltyScore     新颖性分（历史行程出现过则 0.75，否则 1.0）
 * @param distancePenalty  距离惩罚（预留，恒 0）
 * @param finalScore       最终分（hardAvoid=0；否则 clamp01(0.5+(boost−visited惩罚)×0.6)）
 * @param matchedTags      命中的正偏好标签（无则空表）
 * @param avoidTag         命中的回避标签（无则 null）
 * @param hardAvoid        是否硬约束沉底
 * @param visited          是否历史行程出现过
 * @param explanation      人读理由（"匹配你的偏好：…"/"你近期对「…」不感兴趣…"，未命中无则 null）
 */
public record ScoreDetail(double preferenceScore, double noveltyScore, double distancePenalty,
                          double finalScore, List<String> matchedTags, String avoidTag,
                          boolean hardAvoid, boolean visited, String explanation) {

    /** 是否正命中（有匹配标签且未触发回避硬约束）；命中才亮 🎯 徽标并计入命中率分子 */
    public boolean hit() {
        return !hardAvoid && !matchedTags.isEmpty();
    }
}
