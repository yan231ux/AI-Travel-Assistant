package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 预算分解
 */
@Data
public class BudgetBreakdown {
    
    @JsonProperty("transport")
    private Double transport = 0.0;
    
    @JsonProperty("hotel")
    private Double hotel = 0.0;
    
    @JsonProperty("meals")
    private Double meals = 0.0;
    
    @JsonProperty("tickets")
    private Double tickets = 0.0;
    
    @JsonProperty("other")
    private Double other = 0.0;
    
    @JsonProperty("total")
    private Double total = 0.0;
}