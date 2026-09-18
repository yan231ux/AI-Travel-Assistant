package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.client.EmbeddingClient;
import com.yuntu.tripplanner.model.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

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

    @Test
    void findsSunPalaceCardFromDaliGuide() {
        // 大理实测修复：LLM 曾把「杨丽萍太阳宫」地址错配成西岸"才村路"——攻略库原无该卡片，
        // 事实锚定落空、无法纠正。新增 2.14 卡片后必须能命中，并把真实位置
        //（双廊镇玉几岛，洱海东岸）回写给行程。
        Map<String, String> card = ragService.findSpotCard("大理", "杨丽萍太阳宫");
        assertNotNull(card, "攻略库须含「杨丽萍太阳宫」卡片");
        assertEquals("大理市双廊镇玉几岛", card.get("location"),
                "太阳宫真实位置在双廊镇玉几岛（东岸），必须事实回写");
        assertTrue(card.containsKey("intro") && !card.get("intro").isBlank(),
                "卡片应含可回写的简介");
    }

    @Test
    void matchesOfficialCardByPopularNameInParens() {
        // Q4 实测复现：卡片标题「秦始皇帝陵博物院（兵马俑）」，LLM 写的是俗名"兵马俑博物馆"。
        // 修复前：完整标题与俗名互不包含 → 不命中 → 门票/地址只能标"LLM建议（需核实）"。
        // 修复后：括号别名"兵马俑"参与匹配，"兵马俑" ⊂ "兵马俑博物馆" → 命中并回写真实票价。
        Map<String, String> card = ragService.findSpotCard("西安", "兵马俑博物馆");
        assertNotNull(card, "官方名（俗名）卡片必须能被俗名命中");
        assertEquals("120", stripNonDigits(card.get("ticket")),
                "命中的是兵马俑卡片，门票须回写真实价 120 元");
    }

    @Test
    void officialNameStillMatchesItsOwnCard() {
        // 反向也要通：用官方名"秦始皇帝陵博物院"同样能命中（完整标题包含它）
        assertNotNull(ragService.findSpotCard("西安", "秦始皇帝陵博物院"),
                "官方名必须命中自己的卡片");
        // 俗名"兵马俑"（比官方名短）也命中
        assertNotNull(ragService.findSpotCard("西安", "兵马俑"), "短俗名必须命中");
    }

    @Test
    void unrelatedNameDoesNotMatchViaShortAlias() {
        // 防回归：别名放宽不能引入误命中——"钟楼"与兵马俑卡片毫无包含关系，不得返回兵马俑卡片
        Map<String, String> card = ragService.findSpotCard("西安", "钟楼");
        if (card != null) {
            assertFalse(String.valueOf(card.get("location")).contains("临潼区秦陵北路"),
                    "钟楼不得误命中兵马俑（临潼）卡片");
        }
    }

    private static String stripNonDigits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }
}
