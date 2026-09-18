package com.yuntu.tripplanner.client;

import com.yuntu.tripplanner.config.LLMConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * 一次原生工具调用（OpenAI 兼容协议的 tool_calls 结构）
     *
     * @param id        调用 id（协议要求回传 message 时用；本系统单轮调用，仅透传）
     * @param name      工具名
     * @param arguments 参数字符串（JSON，由调用方按各工具 schema 解析）
     */
    public record ToolCall(String id, String name, String arguments) {}

    /**
     * 原生工具调用的结果。
     *
     * @param toolCalls       模型给出的结构化调用（可能为空：模型选择只用文字回答）
     * @param content         模型同时返回的文本内容（部分模型会附带说明）
     * @param rejectedTools   true = 该模型/接口**不接受 tools 参数**（4xx）。调用方据此永久降级为文本计划
     * @param promptTokens    token 统计
     * @param completionTokens token 统计
     */
    public record ToolChatResult(List<ToolCall> toolCalls, String content, boolean rejectedTools,
                                int promptTokens, int completionTokens) {}

    /**
     * 每个模型名记住一次"是否支持原生 tools"。
     * 换模型（LLM_MODEL）时会自动按新模型名重新探测，不会把上一个模型的结论带过去。
     */
    private final Map<String, Boolean> nativeToolRejected = new ConcurrentHashMap<>();

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

    // ------------------------------------------------------------------
    // 原生工具调用（function calling）
    //
    // 为什么要有"降级"这一整套：使用者会频繁更换模型（常换免费模型），
    // 而**并非所有模型都支持 tools 参数**（有的直接 4xx，有的收下但只用文字回答）。
    // 因此这里把"不支持"识别出来并**按模型名记住**，让上层改用文本 JSON 计划，
    // 避免每次请求都白试一轮，也避免系统被绑死在某一个模型上。
    // ------------------------------------------------------------------

    /** 该模型是否已被确认不支持原生 tools（未知 = false，仍会尝试一次） */
    public boolean isNativeToolRejected(String model) {
        return Boolean.TRUE.equals(nativeToolRejected.get(model));
    }

    /** 记下"该模型不支持原生 tools"，后续同模型请求直接走文本计划 */
    public void markNativeToolRejected(String model) {
        if (model != null && !model.isBlank()) {
            nativeToolRejected.put(model, Boolean.TRUE);
        }
    }

    /**
     * 带工具定义调用 LLM，取结构化 tool_calls。
     *
     * @return null = 网络异常/5xx 重试后仍失败（可降级但**不代表**模型不支持 tools）；
     *         结果中 {@code rejectedTools=true} 表示模型不接受 tools 参数，调用方应换文本计划并调
     *         {@link #markNativeToolRejected(String)} 记住
     */
    public ToolChatResult chatWithTools(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        if (!StringUtils.hasText(llmConfig.getApiKey())) {
            log.warn("LLM_API_KEY 未配置，跳过工具调用");
            return null;
        }
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                long delay = RETRY_BASE_DELAY_MS * (1L << (attempt - 1));
                log.warn("LLM 工具调用失败，{}ms 后进行第 {} 次重试（共 {} 次）", delay, attempt, MAX_RETRIES);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            try {
                return chatWithToolsOnce(messages, tools);
            } catch (HttpServerErrorException e) {
                log.warn("LLM 工具调用服务端错误 {}（可重试）", e.getStatusCode());
            } catch (ResourceAccessException e) {
                log.warn("LLM 工具调用网络异常（可重试）: {}", e.getMessage());
            }
        }
        log.error("LLM 工具调用失败（已重试 {} 次仍失败）", MAX_RETRIES);
        return null;
    }

    /** 单次带 tools 调用：网络异常/5xx 向上抛（外层重试），4xx 按"是否 tools 相关"分流 */
    private ToolChatResult chatWithToolsOnce(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(llmConfig.getApiKey());

            Map<String, Object> body = new java.util.HashMap<>();
            body.put("model", llmConfig.getModel());
            body.put("messages", messages);
            body.put("tools", tools);
            body.put("temperature", 0.7);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            String url = llmConfig.getBaseUrl() + "/chat/completions";
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(url, entity, Map.class);

            if (response == null) {
                log.warn("LLM 工具调用响应为空");
                return null;
            }
            int[] usage = extractUsage(response);
            return new ToolChatResult(extractToolCalls(response), extractContent(response), false,
                    usage[0], usage[1]);
        } catch (HttpServerErrorException | ResourceAccessException e) {
            throw e;                       // 交给外层重试
        } catch (HttpClientErrorException e) {
            if (looksLikeToolsUnsupported(e)) {
                // 由客户端自己记住结论（调用方忘调也不会每次白试一轮）
                markNativeToolRejected(llmConfig.getModel());
                log.warn("模型 {} 不接受 tools 参数（{}），后续改用文本 JSON 计划",
                        llmConfig.getModel(), e.getStatusCode());
                return new ToolChatResult(List.of(), null, true, 0, 0);
            }
            log.error("LLM 工具调用失败（业务错误，不重试）", e);
            return null;
        } catch (RestClientException e) {
            log.error("LLM 工具调用失败", e);
            return null;
        }
    }

    /** 提取 choices[0].message.tool_calls（OpenAI 兼容结构） */
    private List<ToolCall> extractToolCalls(Map<String, Object> response) {
        List<ToolCall> calls = new ArrayList<>();
        if (!(response.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
            return calls;
        }
        if (!(choices.get(0) instanceof Map<?, ?> choice)) {
            return calls;
        }
        if (!(choice.get("message") instanceof Map<?, ?> message)) {
            return calls;
        }
        if (!(message.get("tool_calls") instanceof List<?> rawCalls)) {
            return calls;
        }
        for (Object raw : rawCalls) {
            if (!(raw instanceof Map<?, ?> call) || !(call.get("function") instanceof Map<?, ?> fn)) {
                continue;
            }
            Object name = fn.get("name");
            if (name == null || name.toString().isBlank()) {
                continue;
            }
            Object args = fn.get("arguments");
            Object id = call.get("id");
            calls.add(new ToolCall(id == null ? null : id.toString(), name.toString(),
                    args == null ? "{}" : args.toString()));
        }
        return calls;
    }

    /**
     * 判断 4xx 是否由"模型不支持 tools"引起。
     * 各厂商错误码/文案不一（qwen 常写 InvalidParameter，国外的常见 "tools is not supported"），
     * 这里按关键词宽松匹配：误判的代价只是改用文本计划（功能不受影响），比死守原生调用安全。
     */
    private boolean looksLikeToolsUnsupported(HttpClientErrorException e) {
        String raw = e.getResponseBodyAsString();
        if (!StringUtils.hasText(raw)) {
            return false;
        }
        String body = raw.toLowerCase();
        return body.contains("tool") || body.contains("function call") || body.contains("function_call")
                || body.contains("unsupported") || body.contains("not support");
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
