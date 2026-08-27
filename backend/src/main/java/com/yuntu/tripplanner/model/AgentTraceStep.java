package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Agent轨迹步骤
 */
@Data
public class AgentTraceStep {
    
    @JsonProperty("step")
    private Integer step;
    
    @JsonProperty("thought")
    private String thought;
    
    @JsonProperty("action")
    private String action;
    
    @JsonProperty("observation")
    private String observation;
    
    @JsonProperty("tool_calls")
    private List<Map<String, Object>> toolCalls;
}