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

    /**
     * 候选阶段排序证据（个性化口径统一轮：TravelAgent 收尾时从 CollectedData 透传，
     * 供 TripGenerationFinalizer 在写缓存前补 tripId/selected 后落库 candidate_evidence；
     * 仅内存传递用，历史/响应序列化不依赖该字段）。
     */
    @JsonProperty("candidate_evidence")
    private List<CandidateEvidence> candidateEvidence = new ArrayList<>();
}