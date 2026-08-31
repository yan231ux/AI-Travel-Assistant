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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * TravelAgent 循环测试：THINK→ACT→OBSERVE→FINAL 四类轨迹步、
 * token 聚合、success。数据源与 LLM 全部 mock，同步 executor 保证确定性。
 */
@ExtendWith(MockitoExtension.class)
class TravelAgentTest {

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
        // 同步 executor：工具任务立即执行，保证测试确定性
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

    @Test
    void runsFullLoopWithFourTraceTypesAndAggregatesTokens() {
        // 计划轮：LLM 给出 amap_poi + weather_forecast。
        // 注：v1.1 起 reflect 在规则判定足够时不再调用 LLM，因此整个流程只有这一次 LLM 调用。
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

        AgentTraceResponse response = agent.execute(tripRequest());

        assertTrue(response.getSuccess());
        assertNotNull(response.getItinerary());

        List<String> actions = response.getTrace().stream().map(AgentTraceStep::getAction).toList();
        assertTrue(actions.contains("plan_search"), "应包含 plan_search 轨迹");
        assertTrue(actions.contains("tool_execution"), "应包含 tool_execution 轨迹");
        assertTrue(actions.contains("assess"), "应包含 assess 轨迹");
        assertTrue(actions.contains("generate"), "应包含 generate 轨迹");

        // think 仅一次（10/5）：reflect 规则足够时跳过 LLM 反思调用（v1.1 性能优化）
        assertEquals(10, response.getTokenUsage().getPromptTokens());
        assertEquals(5, response.getTokenUsage().getCompletionTokens());
    }

    @Test
    void usesDefaultPlanWhenLlmPlanUnparsable() {
        // LLM 计划解析失败 → 规则兜底调用全部工具
        when(llmClient.chat(anyString())).thenReturn(
                new LlmClient.LlmResult("无法理解，随便吧", 10, 5),
                new LlmClient.LlmResult("数据足够", 10, 5));

        when(amapClient.searchPoi(anyString(), anyString()))
                .thenReturn(List.of(Map.of("name", "武侯祠")));
        WeatherForecastResponse weather = new WeatherForecastResponse();
        weather.setDays(List.of(new WeatherForecastResponse.WeatherDay()));
        when(openMeteoClient.getWeatherForecast(any(), any(), any())).thenReturn(weather);
        when(ragService.isKnownCity(anyString())).thenReturn(false);

        Itinerary itinerary = new Itinerary();
        itinerary.setTripId("trip_成都_2026-05-01");
        itinerary.setDestination("成都");
        itinerary.setDays(new ArrayList<>());
        itinerary.setSourceNotes(new ArrayList<>());
        when(itineraryGenerator.generate(any(), any())).thenReturn(itinerary);

        AgentTraceResponse response = agent.execute(tripRequest());

        assertTrue(response.getSuccess());
        // 默认计划下 web_search 也会被调用（mock 返回 null 不报错）
        assertTrue(response.getTrace().stream().anyMatch(s -> "plan_search".equals(s.getAction())));
    }
}
