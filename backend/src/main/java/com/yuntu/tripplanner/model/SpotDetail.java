package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 景点详情响应（GET /spots/{id} 返回体，产品化阶段一）。
 *
 * <p>与推荐卡片的差异：详情页承载完整信息——地址/坐标/可信度三档/推荐理由/是否去过/
 * 当前用户收藏状态/同城相关推荐。简介同样遵守「攻略命中才写真实简介，否则诚实兜底」，
 * 不因新增页面绕开数据可信度治理（PRODUCT_EVOLUTION_PLAN §6）。
 */
@Data
public class SpotDetail {

    @JsonProperty("spot_id")
    private String spotId;

    @JsonProperty("poi_id")
    private String poiId;

    @JsonProperty("name")
    private String name;

    @JsonProperty("city")
    private String city;

    @JsonProperty("address")
    private String address;

    @JsonProperty("longitude")
    private Double longitude;

    @JsonProperty("latitude")
    private Double latitude;

    @JsonProperty("category")
    private String category;

    @JsonProperty("image_url")
    private String imageUrl;

    @JsonProperty("description")
    private String description;

    @JsonProperty("tags")
    private List<String> tags;

    /** 数据来源：AMAP / RAG / AMAP_AND_RAG */
    @JsonProperty("source")
    private String source;

    /** 可信度：POI_ONLY（诚实兜底文案）/ GUIDE_MATCHED / VERIFIED */
    @JsonProperty("data_quality")
    private String dataQuality;

    /** 是否在历史行程中出现过（visited → 详情页提示"你曾去过"） */
    @JsonProperty("visited")
    private Boolean visited;

    /** 当前用户是否已收藏 */
    @JsonProperty("is_collected")
    private Boolean collected;

    /** 同城相关推荐（简单取同城高分前 6，供详情页底部横向卡） */
    @JsonProperty("related_spots")
    private List<RecommendationItem> relatedSpots;
}
