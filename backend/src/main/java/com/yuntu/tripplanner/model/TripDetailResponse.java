package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 行程详情响应
 */
@Data
public class TripDetailResponse {

    @JsonProperty("trip_id")
    private String tripId;

    @JsonProperty("itinerary")
    private Itinerary itinerary;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;

    /** Agent 推理轨迹（历史行程回放用） */
    @JsonProperty("trace")
    private List<AgentTraceStep> trace = new ArrayList<>();
}