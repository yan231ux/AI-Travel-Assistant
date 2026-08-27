package com.yuntu.tripplanner.client;

import com.yuntu.tripplanner.config.LLMConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LlmClient 单元测试：未配置 key 直接返回 null；网络异常/服务端 5xx 自动重试；
 * 4xx 业务错误不重试（重试无意义）。
 */
@ExtendWith(MockitoExtension.class)
class LlmClientTest {

    private static LlmClient newClient() {
        LLMConfig config = new LLMConfig();
        config.setApiKey("test-key");
        config.setBaseUrl("http://localhost");
        config.setModel("qwen-plus");
        return new LlmClient(config);
    }

    /** 注入 mock RestTemplate，替代构造器里的真实实例 */
    private static RestTemplate mockRestTemplate(LlmClient client) {
        RestTemplate mock = mock(RestTemplate.class);
        ReflectionTestUtils.setField(client, "restTemplate", mock);
        return mock;
    }

    private static Map<String, Object> okResponse() {
        Map<String, Object> response = new HashMap<>();
        response.put("choices", List.of(Map.of("message", Map.of("content", "你好"))));
        response.put("usage", Map.of("prompt_tokens", 5, "completion_tokens", 3));
        return response;
    }

    @Test
    void chatReturnsNullWhenApiKeyMissing() {
        LLMConfig config = new LLMConfig();
        config.setApiKey("");

        LlmClient client = new LlmClient(config);

        assertNull(client.chat("你好"));
    }

    @Test
    void retriesOnResourceAccessExceptionThenSucceeds() {
        LlmClient client = newClient();
        RestTemplate restTemplate = mockRestTemplate(client);
        // 第一次网络超时 → 重试第二次成功
        when(restTemplate.postForObject(anyString(), any(), any(Class.class)))
                .thenThrow(new ResourceAccessException("Read timed out"))
                .thenReturn(okResponse());

        LlmClient.LlmResult result = client.chat("hi");

        assertNotNull(result, "网络超时后应重试并成功");
        assertEquals("你好", result.content());
        assertEquals(5, result.promptTokens());
        assertEquals(3, result.completionTokens());
        verify(restTemplate, times(2)).postForObject(anyString(), any(), any(Class.class));
    }

    @Test
    void givesUpAfterMaxRetriesWhenNetworkKeepsFailing() {
        LlmClient client = newClient();
        RestTemplate restTemplate = mockRestTemplate(client);
        when(restTemplate.postForObject(anyString(), any(), any(Class.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        assertNull(client.chat("hi"));

        // 1 次原始调用 + 2 次重试 = 3 次
        verify(restTemplate, times(3)).postForObject(anyString(), any(), any(Class.class));
    }

    @Test
    void doesNotRetryOnClientError4xx() {
        LlmClient client = newClient();
        RestTemplate restTemplate = mockRestTemplate(client);
        when(restTemplate.postForObject(anyString(), any(), any(Class.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

        assertNull(client.chat("hi"));

        // 4xx 业务错误（如 key 无效）重试无意义，只调用 1 次
        verify(restTemplate, times(1)).postForObject(anyString(), any(), any(Class.class));
    }
}
