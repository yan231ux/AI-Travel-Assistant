package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 推荐景点分页响应（GET /recommendations/spots 返回体，对齐 PRODUCT_EVOLUTION_PLAN §5.2）。
 *
 * <p>personalized 标记本次是否用了画像（false=无画像降级热门）；profile_version
 * 供前端判断"反馈后推荐是否刷新"（画像版本变化 → 相同请求结果应不同）。
 *
 * <p>§6.3 人工干预与算法严格分层：interventions/featured_city 是运营层元信息，
 * 独立于 items 内每个卡片的算法分数与匹配度——运营置顶/降权只改变 item 顺序与标识，
 * 绝不改写任何 score/quality 字段（避免"运营规则和算法分数混在一起"）。
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

    /** 本页卡片命中的运营干预（{spot_id, action: PIN|DEMOTE, reason}），无干预则为空列表 */
    @JsonProperty("interventions")
    private List<RecommendationInterventionMeta> interventions;

    /** 当前城市是否有"城市精选"运营标记 */
    @JsonProperty("featured_city")
    private Boolean featuredCity;

    /** 城市精选的运营原因 */
    @JsonProperty("featured_reason")
    private String featuredReason;

    /**
     * 城市名未通过白名单校验（假城市/乱输入）。
     *
     * <p>此时 items 必为空且<b>不会触发任何高德调用或景点写库</b>，前端据此提示"城市非法"
     * 而不是展示一份被误标成该城市的异地景点。
     */
    @JsonProperty("invalid_city")
    private Boolean invalidCity;

    /** 非法城市时的形近纠错建议（如输入"北就"→"北京"），无建议为 null */
    @JsonProperty("invalid_city_suggestion")
    private String invalidCitySuggestion;

    /** 运营干预的载荷载体（与算法分完全分离的独立 meta） */
    @Data
    public static class RecommendationInterventionMeta {
        @JsonProperty("spot_id")
        private String spotId;
        @JsonProperty("action")
        private String action;
        @JsonProperty("reason")
        private String reason;
    }
}
