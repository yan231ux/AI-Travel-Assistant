package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 推荐景点卡片项（产品化阶段一，GET /recommendations/spots 返回元素）。
 *
 * <p>纯 DTO：不要直接把高德原始 Map 暴露给前端（PRODUCT_EVOLUTION_PLAN §5.2）。
 * 字段与 spot 表解耦——spot 表是内部底座，本对象是给前端看的稳定契约；
 * recommend_reason 由后端确定性生成（命中偏好/未去过/数据可信），LLM 不参与编造。
 */
@Data
public class RecommendationItem {

    /** 系统景点 ID（spot_城市_poiId） */
    @JsonProperty("spot_id")
    private String spotId;

    /** 高德 POI ID */
    @JsonProperty("poi_id")
    private String poiId;

    @JsonProperty("name")
    private String name;

    @JsonProperty("city")
    private String city;

    /** 高德业态 type（含分号分段，如「风景名胜;公园广场」；行为反馈映射偏好标签用） */
    @JsonProperty("category")
    private String category;

    /** 图片 URL（高德；仅展示增强，不标官方） */
    @JsonProperty("image_url")
    private String imageUrl;

    @JsonProperty("description")
    private String description;

    /** 标签列表 */
    @JsonProperty("tags")
    private List<String> tags;

    /** 推荐分 0~1（PersonalizedScoreCalculator 体系扩展；无画像时=热度排序值） */
    @JsonProperty("score")
    private Double score;

    /** 确定性推荐理由（人读："匹配你的自然风景偏好，且你还没有去过"） */
    @JsonProperty("recommend_reason")
    private String recommendReason;

    /** 数据来源：AMAP / RAG / AMAP_AND_RAG */
    @JsonProperty("source")
    private String source;

    /** 数据可信度：POI_ONLY / GUIDE_MATCHED / VERIFIED */
    @JsonProperty("data_quality")
    private String dataQuality;

    /** 当前用户是否已收藏 */
    @JsonProperty("is_collected")
    private Boolean collected;
}
