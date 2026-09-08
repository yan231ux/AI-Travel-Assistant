package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 景点项目
 */
@Data
public class SpotItem {
    
    @JsonProperty("name")
    private String name;
    
    @JsonProperty("start_time")
    private String startTime;
    
    @JsonProperty("end_time")
    private String endTime;
    
    @JsonProperty("description")
    private String description;
    
    @JsonProperty("estimated_cost")
    private Double estimatedCost;
    
    @JsonProperty("location")
    private String location;
    
    @JsonProperty("image_url")
    private String imageUrl;
    
    @JsonProperty("address")
    private String address;
    
    @JsonProperty("latitude")
    private Double latitude;
    
    @JsonProperty("longitude")
    private Double longitude;
    
    @JsonProperty("poi_id")
    private String poiId;

    /** 高德业态 type（如「风景名胜;风景名胜;…」「购物服务;专卖店;…」「商务住宅;住宅区;…」），
     *  由地图补全写入，供校验层判定该地点是否为可游览景点（拦截商铺/公寓被当景点） */
    @JsonProperty("poi_type")
    private String poiType;

    /** 数据来源：高德POI / 本地攻略 / 联网搜索 / LLM建议（需核实），由校验层填充 */
    @JsonProperty("source")
    private String source;

    /** 个性化推荐理由（阶段四：生成收尾由 RecommendationService 回填，如"匹配你的偏好「历史文化」"；
     *  无画像命中时不写，前端据此展示 🎯 理由徽标） */
    @JsonProperty("personal_note")
    private String personalNote;
}