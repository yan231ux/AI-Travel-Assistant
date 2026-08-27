package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 酒店项目
 */
@Data
public class HotelItem {
    
    @JsonProperty("name")
    private String name;
    
    @JsonProperty("level")
    private String level;
    
    @JsonProperty("estimated_cost")
    private Double estimatedCost;
    
    @JsonProperty("location")
    private String location;
    
    @JsonProperty("address")
    private String address;
}