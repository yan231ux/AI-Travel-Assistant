package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 单日行程规划
 */
@Data
public class DayPlan {
    
    @JsonProperty("day_index")
    private Integer dayIndex;
    
    @JsonProperty("date")
    private String date;
    
    @JsonProperty("theme")
    private String theme;
    
    @JsonProperty("spots")
    private List<SpotItem> spots;
    
    @JsonProperty("meals")
    private List<MealItem> meals;
    
    @JsonProperty("hotel")
    private HotelItem hotel;
    
    @JsonProperty("transport")
    private List<TransportItem> transport;
    
    @JsonProperty("notes")
    private List<String> notes;
}