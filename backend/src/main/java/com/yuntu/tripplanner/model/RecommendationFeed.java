package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 推荐景点分页响应（GET /recommendations/spots 返回体，对齐 PRODUCT_EVOLUTION_PLAN §5.2）。
 *
 * <p>personalized 标记本次是否用了画像（false=无画像降级热门）；profile_version
 * 供前端判断"反馈后推荐是否刷新"（画像版本变化 → 相同请求结果应不同）。
 */
@Data
public class RecommendationFeed {

    @JsonProperty("items")
    private List<RecommendationItem> items;

    @JsonProperty("personalized")
    private Boolean personalized;

    @JsonProperty("profile_version")
    private Integer profileVersion;

    @JsonProperty("page")
    private Integer page;

    @JsonProperty("page_size")
    private Integer pageSize;

    @JsonProperty("total")
    private Long total;
}
