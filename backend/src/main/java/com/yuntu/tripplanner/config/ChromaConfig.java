package com.yuntu.tripplanner.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Chroma 向量库配置（可选加速层）
 *
 * 默认关闭：未配置的部署不做任何连接尝试，RAG 走内存余弦检索。
 * 演示/生产开启后，Chroma 不可达时自动降级回内存余弦（失败软降级）。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "chroma")
public class ChromaConfig {

    /**
     * 是否启用 Chroma（需先启动 chroma server：pip install chromadb && chroma run）
     */
    private boolean enabled = false;

    /**
     * Chroma server 地址
     */
    private String baseUrl = "http://localhost:8000";

    /**
     * 集合名前缀，最终名如 guide_chengdu（每城市一个集合）
     */
    private String collectionPrefix = "guide";

    /**
     * 距离函数：cosine | l2 | ip。仅 cosine 时启用（保证与内存余弦排名一致）
     */
    private String distanceFunc = "cosine";

    /**
     * HTTP 超时（毫秒）
     */
    private int timeoutMs = 5000;
}
