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

    /** 数据来源：高德POI / 本地攻略 / 联网搜索 / LLM建议（需核实），由校验层填充 */
    @JsonProperty("source")
    private String source;
}