package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.client.EmbeddingClient;
import com.yuntu.tripplanner.model.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * RagService 单元测试：关键词检索降级、向量检索、未知城市。
 */
@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    @Mock
    private CacheService cacheService;

    @Mock
    private EmbeddingClient embeddingClient;

    private RagService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RagService(cacheService, embeddingClient, "北京,大理,成都,三亚,厦门,西安", 3600);
    }

    @Test
    void unknownCityReturnsEmpty() {
        assertTrue(ragService.search("惠州", "美食", 5, null).isEmpty());
    }

    @Test
    void fallsBackToKeywordWhenEmbeddingFails() {
        // embedding 失败 → 降级关键词检索
        when(embeddingClient.embedAll(anyList(), any())).thenReturn(null);

        List<String> results = ragService.search("成都", "冯校长老火锅", 5, null);

        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(r -> r.contains("冯校长老火锅")));
    }

    @Test
    void usesVectorSearchAndRecordsTokenUsage() {
        // 所有片段向量取同向，query 向量同向 → 全部相关度 1.000
        when(embeddingClient.embedAll(anyList(), any())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[]{1, 0, 0}).toList();
        });
        when(embeddingClient.embed(anyString(), any())).thenAnswer(inv -> {
            // 模拟真实 EmbeddingClient 把 token 消耗写进 sink
            TokenUsage sink = inv.getArgument(1);
            if (sink != null) {
                sink.setEmbeddingPromptTokens(123);
                sink.setEmbeddingCompletionTokens(0);
            }
            return new float[]{1, 0, 0};
        });

        TokenUsage usage = new TokenUsage();
        List<String> results = ragService.search("成都", "美食", 2, usage);

        assertFalse(results.isEmpty());
        // 向量检索结果带"相关度"标记，关键词结果不带
        assertTrue(results.get(0).contains("相关度"));
        assertEquals(123, usage.getEmbeddingPromptTokens());
    }
}
