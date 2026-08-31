package com.yuntu.tripplanner.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.client.AmapClient;
import com.yuntu.tripplanner.client.BingSearchClient;
import com.yuntu.tripplanner.client.LlmClient;
import com.yuntu.tripplanner.client.OpenMeteoClient;
import com.yuntu.tripplanner.config.LLMConfig;
import com.yuntu.tripplanner.model.AgentTraceResponse;
import com.yuntu.tripplanner.model.AgentTraceStep;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.TripRequest;
import com.yuntu.tripplanner.model.WeatherForecastResponse;
import com.yuntu.tripplanner.service.ItineraryGenerator;
import com.yuntu.tripplanner.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Agent 回调测试：SSE 流式推送的核心逻辑。
 * 断言 onStep 顺序、onProgress phase 顺序、isClosed 提前退出。
 */
@ExtendWith(MockitoExtension.class)
class TravelAgentCallbackTest {

    @Mock
    private LlmClient llmClient;
    @Mock
    private AmapClient amapClient;
    @Mock
    private OpenMeteoClient openMeteoClient;
    @Mock
    private BingSearchClient bingSearchClient;
    @Mock
    private ItineraryGenerator itineraryGenerator;
    @Mock
    private RagService ragService;

    private TravelAgent agent;

    @BeforeEach
    void setUp() {
        LLMConfig config = new LLMConfig();
        config.setMaxIterations(3);
        Executor synchronous = Runnable::run;
        agent = new TravelAgent(config, llmClient, amapClient, openMeteoClient, bingSearchClient,
                itineraryGenerator, ragService, synchronous, new ObjectMapper());
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

    private void mockHappyPath() {
        // 计划轮：LLM 给出 amap_poi + weather_forecast（reflect 规则足够时不再调 LLM）
        when(llmClient.chat(anyString())).thenReturn(
                new LlmClient.LlmResult(
                        "{\"tools\":[{\"tool\":\"amap_poi\",\"query\":\"成都 景点\"},{\"tool\":\"weather_forecast\"}]}",
                        10, 5));
        when(amapClient.searchPoi(anyString(), anyString()))
                .thenReturn(List.of(Map.of("name", "武侯祠")));
        WeatherForecastResponse weather = new WeatherForecastResponse();
        weather.setDays(List.of(new WeatherForecastResponse.WeatherDay()));
        when(openMeteoClient.getWeatherForecast(any(), any(), any())).thenReturn(weather);
        // RAG 已知城市 → 规则判定足够（POI + RAG + 天气），一轮结束
        when(ragService.isKnownCity(anyString())).thenReturn(true);

        Itinerary itinerary = new Itinerary();
        itinerary.setTripId("trip_成都_2026-05-01");
        itinerary.setDestination("成都");
        itinerary.setDays(new ArrayList<>());
        itinerary.setSourceNotes(new ArrayList<>());
        when(itineraryGenerator.generate(any(), any())).thenReturn(itinerary);
    }

    @Test
    void onStepReceivesPhasesInOrder() {
        mockHappyPath();

        List<AgentTraceStep> steps = new ArrayList<>();
        AgentTraceResponse response = agent.execute(tripRequest(), steps::add);

        assertTrue(response.getSuccess());
        // 回调收到的 step 顺序与 response.trace 一致
        assertEquals(response.getTrace(), steps);
        List<String> actions = steps.stream().map(AgentTraceStep::getAction).toList();
        assertEquals("plan_search", actions.get(0));
        assertEquals("tool_execution", actions.get(1));
        assertEquals("assess", actions.get(2));
        assertEquals("generate", actions.get(3));
    }

    @Test
    void onProgressReportsPhasesBeforeBlocks() {
        mockHappyPath();

        List<String> phases = new ArrayList<>();
        agent.execute(tripRequest(), new AgentCallback() {
            @Override
            public void onStep(AgentTraceStep step) {
            }

            @Override
            public void onProgress(String phase, String message) {
                phases.add(phase);
            }
        });

        // think/act/assess/generate 每个阶段都先有 progress
        assertTrue(phases.contains("think"));
        assertTrue(phases.contains("act"));
        assertTrue(phases.contains("assess"));
        assertTrue(phases.contains("generate"));
        // act 在 think 之后（本轮有工具调用）
        assertEquals("think", phases.get(0));
    }

    @Test
    void isClosedTrueSkipsExecutionEarly() {
        // isClosed=true 在首个阻塞调用前就提前返回，无需 stub 任何 mock
        List<AgentTraceStep> steps = new ArrayList<>();
        List<String> phases = new ArrayList<>();
        AtomicBoolean closed = new AtomicBoolean(true);
        AgentCallback callback = new AgentCallback() {
            @Override
            public void onStep(AgentTraceStep step) {
                steps.add(step);
            }

            @Override
            public void onProgress(String phase, String message) {
                phases.add(phase);
            }

            @Override
            public boolean isClosed() {
                return closed.get();
            }
        };

        AgentTraceResponse response = agent.execute(tripRequest(), callback);

        // 客户端已断开：不产生任何轨迹与进度，不进入生成阶段
        assertTrue(steps.isEmpty());
        assertTrue(phases.isEmpty());
        assertNull(response.getItinerary());
        assertTrue(response.getTrace().isEmpty());
    }
}
