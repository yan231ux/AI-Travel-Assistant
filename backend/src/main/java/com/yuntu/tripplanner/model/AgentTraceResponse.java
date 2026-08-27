package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent轨迹响应
 */
@Data
public class AgentTraceResponse {
    
    @JsonProperty("success")
    private Boolean success = true;
    
    @JsonProperty("itinerary")
    private Itinerary itinerary;
    
    @JsonProperty("trace")
    private List<AgentTraceStep> trace = new ArrayList<>();
    
    @JsonProperty("collected_data")
    private Map<String, Object> collectedData = new HashMap<>();
    
    @JsonProperty("token_usage")
    private TokenUsage tokenUsage = new TokenUsage();
    
    @JsonProperty("errors")
    private List<String> errors = new ArrayList<>();
}