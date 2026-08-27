package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 行程列表响应
 */
@Data
public class TripListResponse {
    
    @JsonProperty("total")
    private Integer total;
    
    @JsonProperty("items")
    private List<TripSummaryItem> items;
}