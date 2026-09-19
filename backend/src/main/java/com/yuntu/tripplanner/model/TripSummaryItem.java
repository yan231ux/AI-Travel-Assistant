package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 行程摘要项
 */
@Data
public class TripSummaryItem {
    
    @JsonProperty("trip_id")
    private String tripId;
    
    @JsonProperty("destination")
    private String destination;
    
    @JsonProperty("summary")
    private String summary;
    
    @JsonProperty("created_at")
    private LocalDateTime createdAt;
    
    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;

    @JsonProperty("confirmed_visited")
    private Boolean confirmedVisited;

    /** 行程最后一天是否在将来（用于前端禁用"确认去过"，权威拦截仍在后端） */
    @JsonProperty("future")
    private Boolean future;
}