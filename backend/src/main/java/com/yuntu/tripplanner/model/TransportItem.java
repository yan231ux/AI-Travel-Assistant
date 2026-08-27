package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 交通项目
 */
@Data
public class TransportItem {
    
    @JsonProperty("mode")
    private String mode;
    
    @JsonProperty("from_place")
    private String fromPlace;
    
    @JsonProperty("to_place")
    private String toPlace;
    
    @JsonProperty("estimated_cost")
    private Double estimatedCost;
    
    @JsonProperty("duration")
    private String duration;
    
    @JsonProperty("distance_km")
    private Double distanceKm;
    
    @JsonProperty("estimated_minutes")
    private Integer estimatedMinutes;

    /** 数据来源：高德路线估算 / 估算（LLM），由交通补全/校验层填充 */
    @JsonProperty("source")
    private String source;
}