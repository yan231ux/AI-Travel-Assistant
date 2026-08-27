package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Token使用情况
 */
@Data
public class TokenUsage {
    
    @JsonProperty("prompt_tokens")
    private Integer promptTokens = 0;
    
    @JsonProperty("completion_tokens")
    private Integer completionTokens = 0;
    
    @JsonProperty("rewrite_prompt_tokens")
    private Integer rewritePromptTokens = 0;
    
    @JsonProperty("rewrite_completion_tokens")
    private Integer rewriteCompletionTokens = 0;
    
    @JsonProperty("embedding_prompt_tokens")
    private Integer embeddingPromptTokens = 0;
    
    @JsonProperty("embedding_completion_tokens")
    private Integer embeddingCompletionTokens = 0;
    
    @JsonProperty("planner_prompt_tokens")
    private Integer plannerPromptTokens = 0;
    
    @JsonProperty("planner_completion_tokens")
    private Integer plannerCompletionTokens = 0;
    
    @JsonProperty("rerank_prompt_tokens")
    private Integer rerankPromptTokens = 0;
    
    @JsonProperty("rerank_completion_tokens")
    private Integer rerankCompletionTokens = 0;
}