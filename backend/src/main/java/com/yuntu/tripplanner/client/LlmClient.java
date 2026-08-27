package com.yuntu.tripplanner.client;

import com.yuntu.tripplanner.config.LLMConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * LLM 统一调用客户端
 *
 * 封装对 DashScope OpenAI 兼容接口的 chat/completions 调用，
 * 返回内容与真实 token 消耗。TravelAgent / ItineraryGenerator 共用，
 * 消除原先两处重复的裸 RestTemplate 代码。
 */
@Slf4j
@Component
public class LlmClient {

    private final LLMConfig llmConfig;
    private final RestTemplate restTemplate;

    /** 一次 LLM 调用最多额外重试次数（网络异常 / 超时 / 5xx 才重试） */
    private static final int MAX_RETRIES = 2;

    /** 重试退避起始延迟（ms），依次 500、1000 */
    private static final long RETRY_BASE_DELAY_MS = 500;

    /**
     * 一次 LLM 调用的结果
     */
    public record LlmResult(String content, int promptTokens, int completionTokens) {}

    public LlmClient(LLMConfig llmConfig) {
        this.llmConfig = llmConfig;
        this.restTemplate = new RestTemplate();
        // 读超时放宽到 120s：生成完整行程 JSON 的 prompt 很长，qwen-plus 偶尔需要较久
        int timeout = llmConfig.getTimeoutSeconds() != null ? llmConfig.getTimeoutSeconds() : 120;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(timeout));
        this.restTemplate.setRequestFactory(factory);
    }

    /**
     * 调用 LLM，带失败重试。失败或响应不可解析时返回 null（由调用方决定降级策略）。
     *
     * <p>LLM 偶发慢响应/限流是常态，一次失败不应让整个行程生成失败。
     * 只对可恢复错误重试：网络异常（{@link ResourceAccessException}，含读/连接超时）、
     * 服务端 5xx（{@link HttpServerErrorException}）；4xx 业务错误（key 无效、请求非法）
     * 重试无意义，直接返回 null。
     */
    public LlmResult chat(String prompt) {
        if (!StringUtils.hasText(llmConfig.getApiKey())) {
            log.warn("LLM_API_KEY 未配置，跳过调用");
            return null;
        }
        RuntimeException retriable = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                long delay = RETRY_BASE_DELAY_MS * (1L << (attempt - 1));
                log.warn("LLM 调用失败，{}ms 后进行第 {} 次重试（共 {} 次）", delay, attempt, MAX_RETRIES);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            try {
                return chatOnce(prompt);
            } catch (HttpServerErrorException e) {
                retriable = e;
                log.warn("LLM 服务端错误 {}（可重试）", e.getStatusCode());
            } catch (ResourceAccessException e) {
                retriable = e;
                log.warn("LLM 网络异常（可重试）: {}", e.getMessage());
            }
        }
        log.error("调用LLM失败（已重试 {} 次仍失败）", MAX_RETRIES, retriable);
        return null;
    }

    /** 单次 LLM 调用：网络异常/5xx 向上抛（外层重试），4xx 业务错误吞掉返回 null（不重试） */
    private LlmResult chatOnce(String prompt) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(llmConfig.getApiKey());

            Map<String, Object> body = new java.util.HashMap<>();
            body.put("model", llmConfig.getModel());
            body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            body.put("temperature", 0.7);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            String url = llmConfig.getBaseUrl() + "/chat/completions";
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(url, entity, Map.class);

            if (response == null) {
                log.warn("LLM 响应为空");
                return null;
            }

            String content = extractContent(response);
            if (content == null) {
                log.warn("LLM 响应缺少 content");
                return null;
            }

            int[] usage = extractUsage(response);
            return new LlmResult(content, usage[0], usage[1]);
        } catch (HttpServerErrorException | ResourceAccessException e) {
            throw e;                       // 交给外层重试
        } catch (RestClientException e) {  // 4xx 等业务错误，重试无意义
            log.error("调用LLM失败（业务错误，不重试）", e);
            return null;
        }
    }

    /**
     * 提取 choices[0].message.content
     */
    private String extractContent(Map<String, Object> response) {
        if (response.get("choices") instanceof List<?> choices && !choices.isEmpty()) {
            if (choices.get(0) instanceof Map<?, ?> choice) {
                Object message = choice.get("message");
                if (message instanceof Map<?, ?> messageMap && messageMap.get("content") instanceof String content) {
                    return content;
                }
            }
        }
        return null;
    }

    /**
     * 提取 usage 中的 prompt/completion tokens，缺省为 0
     */
    private int[] extractUsage(Map<String, Object> response) {
        int prompt = 0;
        int completion = 0;
        if (response.get("usage") instanceof Map<?, ?> usage) {
            prompt = toInt(usage.get("prompt_tokens"));
            completion = toInt(usage.get("completion_tokens"));
        }
        return new int[]{prompt, completion};
    }

    private int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
