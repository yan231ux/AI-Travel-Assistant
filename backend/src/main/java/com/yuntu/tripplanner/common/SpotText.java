package com.yuntu.tripplanner.common;

import com.yuntu.tripplanner.model.Spot;

/**
 * 景点展示文案统一兜底（Review P2-7 / 复查 #2）。
 *
 * <p>约定：无真实资料来源（未命中本地攻略、或行程去重中被降级为复用者）时，
 * 各输出场景必须使用同一条兜底文案 —— 行程校验层（ItineraryValidator）、
 * 推荐卡片（RecommendationFeedService）、景点详情与相关推荐（SpotService）全部引用
 * {@link #NO_GUIDE_DESC} 这一个常量，避免不同页面文案漂移。
 * 同时保留 data_quality=POI_ONLY 供前端继续展示可信度说明；只作用于输出层，不写回 spot 表。
 */
public final class SpotText {

    /** 无真实资料来源的统一兜底文案（产品约定文案，与行程去重兜底一致） */
    public static final String NO_GUIDE_DESC =
            "（该景点简介暂未匹配到真实资料，请参考官方介绍）";

    private SpotText() {
    }

    /** 返回可信简介；spot 无简介时给出统一兜底文案 */
    public static String safeDescription(Spot spot) {
        if (spot != null && spot.getDescription() != null && !spot.getDescription().isBlank()) {
            return spot.getDescription();
        }
        return NO_GUIDE_DESC;
    }
}
