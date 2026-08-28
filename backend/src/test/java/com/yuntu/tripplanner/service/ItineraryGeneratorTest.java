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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    private ItineraryGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new ItineraryGenerator(llmClient, new ObjectMapper(), mapEnrichmentService, itineraryValidator);
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
}
