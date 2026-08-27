package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 行程保存请求
 */
@Data
public class TripSaveRequest {

    @JsonProperty("trip_id")
    private String tripId;

    @JsonProperty("itinerary")
    private Itinerary itinerary;

    @JsonProperty("user_id")
    private String userId;

    /**
     * Agent 推理轨迹（可选，前端生成后保存时带上，落库到 agent_trace 表）
     */
    @JsonProperty("trace")
    private List<AgentTraceStep> trace;
}