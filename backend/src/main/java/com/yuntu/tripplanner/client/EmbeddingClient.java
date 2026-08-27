package com.yuntu.tripplanner.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.config.LLMConfig;
import com.yuntu.tripplanner.model.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文本向量化客户端（DashScope OpenAI 兼容 /embeddings 接口）
 *
 * model 默认 text-embedding-v3，可通过 llm.embedding-model 配置。
 * 失败返回 null / 空列表，由调用方降级（RAG 走关键词检索）。
 */
@Slf4j
@Component
public class EmbeddingClient {

    private final LLMConfig llmConfig;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public EmbeddingClient(LLMConfig llmConfig, ObjectMapper objectMapper) {
        this.llmConfig = llmConfig;
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
        int timeout = llmConfig.getTimeoutSeconds() != null ? llmConfig.getTimeoutSeconds() : 60;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(timeout));
        this.restTemplate.setRequestFactory(factory);
    }

    /**
     * 单次请求的最大批量（text-embedding-v3 限制 10 条/批）
     */
    private static final int EMBED_BATCH_SIZE = 10;

    /**
     * 批量向量化。失败返回 null（调用方降级）。
     *
     * @param texts  待向量化文本
     * @param usageSink 非空时累加 embedding 调用的 token 消耗
     * @return 与 texts 一一对应的向量列表；失败为 null
     */
    public List<float[]> embedAll(List<String> texts, TokenUsage usageSink) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (!StringUtils.hasText(llmConfig.getApiKey())) {
            log.warn("LLM_API_KEY 未配置，跳过 embedding 调用");
            return null;
        }

        List<float[]> allVectors = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += EMBED_BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + EMBED_BATCH_SIZE, texts.size()));
            List<float[]> batchVectors = embedBatch(batch, usageSink);
            if (batchVectors == null) {
                return null;
            }
            allVectors.addAll(batchVectors);
        }
        return allVectors;
    }

    /**
     * 单批向量化（每批 ≤ 10 条），失败返回 null
     */
    private List<float[]> embedBatch(List<String> texts, TokenUsage usageSink) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(llmConfig.getApiKey());

            Map<String, Object> body = new HashMap<>();
            body.put("model", llmConfig.getEmbeddingModel());
            body.put("input", texts);
            body.put("encoding_format", "float");

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            String url = llmConfig.getBaseUrl() + "/embeddings";
            String responseBody = restTemplate.postForObject(url, entity, String.class);

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.path("data");
            List<float[]> vectors = new ArrayList<>(data.size());
            for (JsonNode item : data) {
                JsonNode embedding = item.path("embedding");
                float[] vec = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vec[i] = (float) embedding.get(i).asDouble();
                }
                vectors.add(vec);
            }

            if (usageSink != null && root.path("usage").isObject()) {
                JsonNode usage = root.path("usage");
                usageSink.setEmbeddingPromptTokens(usageSink.getEmbeddingPromptTokens()
                        + usage.path("prompt_tokens").asInt(0));
                usageSink.setEmbeddingCompletionTokens(usageSink.getEmbeddingCompletionTokens()
                        + usage.path("completion_tokens").asInt(0));
            }

            log.info("embedding 批量调用完成，{} 条文本 -> {} 维", vectors.size(),
                    vectors.isEmpty() ? 0 : vectors.get(0).length);
            return vectors;
        } catch (Exception e) {
            log.error("embedding 调用失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 单条文本向量化
     */
    public float[] embed(String text, TokenUsage usageSink) {
        List<float[]> result = embedAll(List.of(text), usageSink);
        return (result == null || result.isEmpty()) ? null : result.get(0);
    }

    /**
     * 当前使用的 embedding 模型名（持久化向量缓存的标识，换模型后旧向量失效）
     */
    public String getModel() {
        return llmConfig.getEmbeddingModel();
    }
}
