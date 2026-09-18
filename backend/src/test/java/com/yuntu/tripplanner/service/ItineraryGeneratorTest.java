package com.yuntu.tripplanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.agent.CollectedData;
import com.yuntu.tripplanner.client.LlmClient;
import com.yuntu.tripplanner.exception.TripGenerationException;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.TripRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ItineraryGenerator 单元测试：JSON 解析（含代码围栏）、预算统计、
 * RAG 来源说明、token 统计、解析失败抛异常。
 */
@ExtendWith(MockitoExtension.class)
class ItineraryGeneratorTest {

    @Mock
    private LlmClient llmClient;

    @Mock
    private MapEnrichmentService mapEnrichmentService;

    @Mock
    private ItineraryValidator itineraryValidator;

    @Mock
    private PersonalizedRankingService personalizedRankingService;

    private ItineraryGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new ItineraryGenerator(llmClient, new ObjectMapper(), mapEnrichmentService,
                itineraryValidator, personalizedRankingService);
    }

    private TripRequest tripRequest() {
        TripRequest req = new TripRequest();
        req.setDestination("成都");
        req.setStartDate(LocalDate.of(2026, 5, 1));
        req.setEndDate(LocalDate.of(2026, 5, 3));
        req.setTravelers(2);
        req.setBudget(4000.0);
        return req;
    }

    private String validItineraryJson() {
        return """
                ```json
                {
                  "trip_id": "trip_成都_2026-05-01",
                  "destination": "成都",
                  "summary": "三天成都之行",
                  "days": [
                    {
                      "day_index": 1, "date": "2026-05-01", "theme": "文化",
                      "spots": [{"name": "武侯祠", "description": "三国文化", "estimated_cost": 50.0}],
                      "meals": [{"name": "马旺子", "meal_type": "午餐", "estimated_cost": 90.0}],
                      "hotel": {"name": "参考酒店", "level": "舒适型", "estimated_cost": 300.0},
                      "transport": [{"mode": "打车", "estimated_cost": 20.0}],
                      "notes": ["带伞"]
                    }
                  ],
                  "tips": ["建议提前订票"]
                }
                ```
                """;
    }

    @Test
    void parsesJsonWithCodeFenceAndComputesBudget() {
        when(llmClient.chat(anyString())).thenReturn(new LlmClient.LlmResult(validItineraryJson(), 100, 50));

        Itinerary itinerary = generator.generate(tripRequest(), new CollectedData());

        assertNotNull(itinerary);
        assertEquals("成都", itinerary.getDestination());
        assertEquals("武侯祠", itinerary.getDays().get(0).getSpots().get(0).getName());
        // 预算 = 门票 50 + 餐饮 90 + 酒店 300 + 交通 20
        assertEquals(460.0, itinerary.getEstimatedBudget(), 0.001);
        // planner token 统计
        assertEquals(100, itinerary.getTokenUsage().getPlannerPromptTokens());
        assertEquals(50, itinerary.getTokenUsage().getPlannerCompletionTokens());
    }

    @Test
    void addsRagSourceNote() {
        when(llmClient.chat(anyString())).thenReturn(new LlmClient.LlmResult(validItineraryJson(), 10, 5));

        CollectedData collected = new CollectedData();
        collected.getRagData().put("guide",
                "[来源: chengdu_test.md | 标题: 蓉城限定火锅店]\n火锅\n\n"
                        + "[来源: chengdu_test.md | 标题: 交通贴士]\n打车\n");

        Itinerary itinerary = generator.generate(tripRequest(), collected);

        assertTrue(itinerary.getSourceNotes().stream().anyMatch(n -> n.contains("本地攻略库命中 2 条（RAG）")));
    }

    @Test
    void mergesEmbeddingTokenUsage() {
        when(llmClient.chat(anyString())).thenReturn(new LlmClient.LlmResult(validItineraryJson(), 10, 5));

        CollectedData collected = new CollectedData();
        collected.getTokenUsage().setEmbeddingPromptTokens(88);
        collected.getTokenUsage().setEmbeddingCompletionTokens(0);

        Itinerary itinerary = generator.generate(tripRequest(), collected);

        assertEquals(88, itinerary.getTokenUsage().getEmbeddingPromptTokens());
    }

    @Test
    void addsPersonalizationNote_whenRankingProducedNotes() {
        when(llmClient.chat(anyString())).thenReturn(new LlmClient.LlmResult(validItineraryJson(), 10, 5));

        CollectedData collected = new CollectedData();
        collected.setPersonalizedNotes(List.of(
                "[景点] 故宫博物院：匹配你的偏好「历史文化」",
                "[景点] 星光购物中心：你近期对「购物」不感兴趣，本次已降低优先级"));

        Itinerary itinerary = generator.generate(tripRequest(), collected);

        assertTrue(itinerary.getSourceNotes().stream().anyMatch(n -> n.contains("已按你的偏好与历史对候选")),
                "结果页来源说明需展示个性化排序依据");
        assertTrue(itinerary.getSourceNotes().stream().anyMatch(n -> n.contains("故宫博物院")),
                "需透出代表性匹配说明");
    }

    @Test
    void repairOnFirstParseFailureThenSucceeds() {
        // 第一次（planner）输出坏 JSON，第二次（修正）输出合法 JSON
        when(llmClient.chat(anyString())).thenReturn(
                new LlmClient.LlmResult("{\"days\": \"broken", 5, 2),
                new LlmClient.LlmResult(validItineraryJson(), 30, 15));

        Itinerary itinerary = generator.generate(tripRequest(), new CollectedData());

        assertNotNull(itinerary);
        assertEquals("成都", itinerary.getDestination());
        // rewrite token 统计进 itinerary
        assertEquals(30, itinerary.getTokenUsage().getRewritePromptTokens());
    }

    @Test
    void recoversFromRawNewlineInsideStringValueWithoutLlmRepair() {
        // 回归 §35：模型在字符串值里塞了裸换行（Jackson 报 CTRL-CHAR code 10），
        // 旧行为是回传 LLM 修正 → 20s 超时 → 整个请求失败（主生成其实已成功）。
        // 期望：本地转义即可解析，且**只有 1 次 LLM 调用**（不触发修正步）。
        String jsonWithRawNewline = "{\n"
                + "  \"trip_id\": \"trip_成都_2026-05-01\",\n"
                + "  \"destination\": \"成都\",\n"
                + "  \"summary\": \"第一天：逛锦里\n第二天：都江堰\",\n"
                + "  \"days\": [{\n"
                + "    \"day_index\": 1, \"date\": \"2026-05-01\", \"theme\": \"文化\",\n"
                + "    \"spots\": [{\"name\": \"武侯祠\", \"description\": \"三国\t文化\", \"estimated_cost\": 50.0}]\n"
                + "  }]\n"
                + "}";
        when(llmClient.chat(anyString())).thenReturn(new LlmClient.LlmResult(jsonWithRawNewline, 100, 50));

        Itinerary itinerary = generator.generate(tripRequest(), new CollectedData());

        assertNotNull(itinerary);
        assertEquals("成都", itinerary.getDestination());
        assertTrue(itinerary.getSummary().contains("锦里"), "裸换行应被转义而不是丢掉内容");
        assertEquals("武侯祠", itinerary.getDays().get(0).getSpots().get(0).getName());
        // 关键断言：未走修正步（否则会多一次 LLM 调用 + 20s 超时风险）
        verify(llmClient, times(1)).chat(anyString());
        assertEquals(0, itinerary.getTokenUsage().getRewritePromptTokens());
    }

    @Test
    void escapeRawControlCharsTouchesOnlyStringLiterals() {
        String raw = "{\n  \"a\": \"x\ny\",\n  \"b\": \"已转义\\n不动\",\n  \"c\": \"引号\\\"后换行\n\",\n  \"d\": 1\n}";

        String out = ItineraryGenerator.escapeRawControlChars(raw);

        // 字符串外的结构缩进/换行保持原样（否则会被当成内容、破坏 JSON 结构）
        assertTrue(out.startsWith("{\n  \"a\":"));
        // 字符串内的裸换行被转义
        assertTrue(out.contains("\"x\\ny\""));
        // 已有转义序列不被二次转义
        assertTrue(out.contains("\"已转义\\n不动\""));
        // 转义引号后的裸换行也必须处理（不能因为引号状态判断错误而漏掉）
        assertTrue(out.contains("\"引号\\\"后换行\\n\""));

        // 幂等：修复过的文本再跑一次必须逐字不变，否则 tryParse 的"有无改动"判断会永远为 true
        assertEquals(out, ItineraryGenerator.escapeRawControlChars(out));
    }

    @Test
    void throwsWhenParseAndRepairBothFail() {
        when(llmClient.chat(anyString())).thenReturn(
                new LlmClient.LlmResult("这不是JSON", 5, 2),
                new LlmClient.LlmResult("还不是JSON", 5, 2));

        assertThrows(TripGenerationException.class, () -> generator.generate(tripRequest(), new CollectedData()));
    }

    @Test
    void throwsWhenLlmCallFails() {
        when(llmClient.chat(anyString())).thenReturn(null);

        assertThrows(TripGenerationException.class, () -> generator.generate(tripRequest(), new CollectedData()));
    }

    @Test
    void overridesLlmTripIdWithUniqueServerId() {
        // 提示词里的 trip_{目的地}_{日期} 是"示例格式"，LLM 照抄会得到可重复的 id → 必须被服务端覆盖
        when(llmClient.chat(anyString())).thenReturn(new LlmClient.LlmResult(validItineraryJson(), 10, 5));

        Itinerary itinerary = generator.generate(tripRequest(), new CollectedData());

        assertNotEquals("trip_成都_2026-05-01", itinerary.getTripId(), "LLM 给的确定性 trip_id 必须被服务端覆盖");
        assertTrue(itinerary.getTripId().startsWith("trip_成都_2026-05-01_"),
                "服务端 id 应保留可读前缀并追加唯一后缀，实际: " + itinerary.getTripId());
    }

    @Test
    void generatesDistinctTripIdsForSameRequest() {
        // trip_record.trip_id 有 UNIQUE 约束：同一目的地+出发日重复生成也不能撞 id
        when(llmClient.chat(anyString())).thenReturn(new LlmClient.LlmResult(validItineraryJson(), 10, 5));

        String first = generator.generate(tripRequest(), new CollectedData()).getTripId();
        String second = generator.generate(tripRequest(), new CollectedData()).getTripId();

        assertNotEquals(first, second, "同请求两次生成必须是两个不同的 trip_id");
    }
}
