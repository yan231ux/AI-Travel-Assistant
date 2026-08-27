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
     * 模型名称
     */
    private String model = "qwen-plus";
    
    /**
     * 超时时间（秒）
     */
    private Integer timeoutSeconds = 60;
    
    /**
     * 最大迭代次数
     */
    private Integer maxIterations = 3;

    /**
     * 文本向量化模型（RAG 用）
     */
    private String embeddingModel = "text-embedding-v3";
}