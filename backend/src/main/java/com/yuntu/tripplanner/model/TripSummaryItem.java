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
}