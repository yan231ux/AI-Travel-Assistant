package com.yuntu.tripplanner.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * LLM配置类
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "llm")
public class LLMConfig {
    
    /**
     * LLM Base URL
     */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    
    /**
     * API Key
     */
    private String apiKey;
    
    /**
     * 模型名称（qwen-plus 免费额度用完后切到 qwen3.7-plus：35B 稠密 / 100万上下文，兼容 OpenAI 协议）
     */
    private String model = "qwen-turbo";
    
    /**
     * 超时时间（秒）
     */
    private Integer timeoutSeconds = 60;
    
    /**
     * 最大迭代次数
     */
    private Integer maxIterations = 3;

    /**
     * Agent 决策模式：
     * - legacy（默认）：首轮 LLM 决策 + 后续轮规则按缺口补轮（改造前行为，保持可复现与成本可控）
     * - autonomous：每轮都由 LLM 看已获数据决定下一步，并把失败调用回灌让模型自纠
     *
     * 默认 legacy：既有 612 个用例与线上行为完全不变；切 autonomous 后新链路单独用例覆盖。
     */
    private String agentMode = "legacy";

    /**
     * 文本向量化模型（RAG 用）
     */
    private String embeddingModel = "text-embedding-v3";
}