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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TravelAgent 自主决策模式（llm.agent-mode=autonomous）测试。
 *
 * 覆盖三处改造：
 * 1) 补轮也由 LLM 决策（而非规则按缺口补）；
 * 2) 失败调用回灌给模型（下一次提示词里带失败原因，要求换关键词）；
 * 3) 已满足的数据源不再重复采集；LLM 补轮失败时回落规则补轮（护栏）。
 *
 * 另覆盖：legacy 模式下补轮不咨询 LLM（保证改造前行为与 612 用例基线不变）。
 */
@ExtendWith(MockitoExtension.class)
class TravelAgentAutonomousModeTest {

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
        agent = newAgent("autonomous");
    }

    private TravelAgent newAgent(String mode) {
        LLMConfig config = new LLMConfig();
        config.setMaxIterations(3);
        config.setAgentMode(mode);
        // 同步 executor：工具任务立即执行，保证测试确定性
        Executor synchronous = Runnable::run;
        return new TravelAgent(config, llmClient, amapClient, openMeteoClient, bingSearchClient,
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

    private void stubItinerary() {
        Itinerary itinerary = new Itinerary();
        itinerary.setTripId("trip_成都_2026-05-01");
        itinerary.setDestination("成都");
        itinerary.setDays(new ArrayList<>());
        itinerary.setSourceNotes(new ArrayList<>());
        when(itineraryGenerator.generate(any(), any())).thenReturn(itinerary);
    }

    private void stubWeather() {
        WeatherForecastResponse weather = new WeatherForecastResponse();
        weather.setDays(List.of(new WeatherForecastResponse.WeatherDay()));
        when(openMeteoClient.getWeatherForecast(any(), any(), any())).thenReturn(weather);
    }

    /** 按调用顺序回放 LLM 结果，并记录每次的提示词（用于断言"失败原因被回灌"） */
    private List<String> stubSequentialLlm(List<LlmClient.LlmResult> results) {
        List<String> prompts = new ArrayList<>();
        int[] cursor = {0};
        when(llmClient.chat(anyString())).thenAnswer(invocation -> {
            prompts.add(invocation.getArgument(0));
            int idx = cursor[0]++;
            return idx < results.size() ? results.get(idx) : results.get(results.size() - 1);
        });
        return prompts;
    }

    /** 取第 n 个 plan_search 轨迹的工具名列表（n 从 1 起） */
    private List<String> plannedToolsOf(AgentTraceResponse response, int occurrence) {
        List<String> tools = new ArrayList<>();
        int seen = 0;
        for (AgentTraceStep step : response.getTrace()) {
            if (!"plan_search".equals(step.getAction())) {
                continue;
            }
            if (++seen == occurrence) {
                if (step.getToolCalls() != null) {
                    step.getToolCalls().forEach(tc -> tools.add(String.valueOf(tc.get("tool"))));
                }
                return tools;
            }
        }
        return tools;
    }

    private String thoughtOf(AgentTraceResponse response, int occurrence) {
        int seen = 0;
        for (AgentTraceStep step : response.getTrace()) {
            if ("plan_search".equals(step.getAction()) && ++seen == occurrence) {
                return step.getThought();
            }
        }
        return null;
    }

    @Test
    void autonomous_secondRoundDecidedByLlmWithFailureFeedback() {
        // 首轮：只搜 web_search；而该搜索返回空 → 规则判定数据不足，进入补轮
        List<String> prompts = stubSequentialLlm(List.of(
                new LlmClient.LlmResult(
                        "{\"tools\":[{\"tool\":\"web_search\",\"query\":\"成都 冷门玩法\"}]}", 10, 5),
                // 补轮：模型看到"搜索返回空"，换关键词并补 POI / 天气
                new LlmClient.LlmResult(
                        "{\"tools\":[{\"tool\":\"web_search\",\"query\":\"成都 旅游 攻略 推荐\"},"
                                + "{\"tool\":\"amap_poi\",\"query\":\"成都 景点\"},"
                                + "{\"tool\":\"weather_forecast\",\"query\":\"成都\"}]}", 20, 8)));

        // 第一次搜索返回空（失败），之后返回正常结果（模拟"换关键词后成功"）
        when(bingSearchClient.searchAsText(anyString())).thenReturn("", "成都三日游攻略正文");
        when(amapClient.searchPoi(anyString(), anyString()))
                .thenReturn(List.of(Map.of("name", "武侯祠")));
        stubWeather();
        stubItinerary();

        AgentTraceResponse response = agent.execute(tripRequest());

        assertTrue(response.getSuccess());
        // 补轮确实咨询了模型（首轮 1 次 + 补轮 1 次）
        verify(llmClient, times(2)).chat(anyString());
        assertEquals(2, prompts.size());

        // 补轮提示词里带上了失败调用的原因与原始 query（失败回灌落地）
        String gapPrompt = prompts.get(1);
        assertTrue(gapPrompt.contains("成都 冷门玩法"), "补轮提示词应包含上一轮的调用明细");
        assertTrue(gapPrompt.contains("搜索返回空"), "补轮提示词应包含失败原因");
        assertTrue(gapPrompt.contains("换一个更可能成功的关键词"), "补轮提示词应要求换关键词重试");

        // 补轮计划由模型产出（而非规则"补充缺失数据"）
        assertTrue(thoughtOf(response, 2) != null && thoughtOf(response, 2).contains("LLM 基于缺口重新决策"),
                "第二轮 plan_search 应标注为 LLM 重新决策");
        assertEquals(List.of("web_search", "amap_poi", "weather_forecast"), plannedToolsOf(response, 2));
    }

    @Test
    void autonomous_skipsSourcesAlreadySatisfied() {
        // 首轮：POI + 天气都成功；但没拿到搜索/攻略 → 规则判定不足，进入补轮
        stubSequentialLlm(List.of(
                new LlmClient.LlmResult(
                        "{\"tools\":[{\"tool\":\"amap_poi\",\"query\":\"成都 景点\"},"
                                + "{\"tool\":\"weather_forecast\",\"query\":\"成都\"}]}", 10, 5),
                // 补轮：模型把"已经拿到数据"的天气和景点又列了一遍
                new LlmClient.LlmResult(
                        "{\"tools\":[{\"tool\":\"weather_forecast\",\"query\":\"成都\"},"
                                + "{\"tool\":\"amap_poi\",\"query\":\"成都 景点\"}]}", 20, 8)));

        when(amapClient.searchPoi(anyString(), anyString()))
                .thenReturn(List.of(Map.of("name", "武侯祠")));
        stubWeather();
        when(bingSearchClient.searchAsText(anyString())).thenReturn("成都攻略正文");
        stubItinerary();

        AgentTraceResponse response = agent.execute(tripRequest());

        assertTrue(response.getSuccess());
        // 重复项被过滤 → 模型补轮无可执行计划 → 回落规则，只补真正缺失的 web_search
        assertEquals(List.of("web_search"), plannedToolsOf(response, 2),
                "已满足的数据源不应被重复采集（天气/POI 已在首轮拿到）");
    }

    @Test
    void autonomous_fallsBackToRuleGapFillWhenLlmUnavailable() {
        // 首轮只拿 POI；补轮时 LLM 调用失败（返回 null）→ 必须回落规则补轮，保证数据不塌
        int[] cursor = {0};
        when(llmClient.chat(anyString())).thenAnswer(invocation -> {
            if (cursor[0]++ == 0) {
                return new LlmClient.LlmResult(
                        "{\"tools\":[{\"tool\":\"amap_poi\",\"query\":\"成都 景点\"}]}", 10, 5);
            }
            // 第二次调用失败：模拟网络异常/限流重试后仍失败
            return null;
        });

        when(amapClient.searchPoi(anyString(), anyString()))
                .thenReturn(List.of(Map.of("name", "武侯祠")));
        stubWeather();
        when(bingSearchClient.searchAsText(anyString())).thenReturn("成都攻略正文");
        stubItinerary();

        AgentTraceResponse response = agent.execute(tripRequest());

        assertTrue(response.getSuccess());
        String thought = thoughtOf(response, 2);
        assertNotNull(thought);
        assertTrue(thought.contains("回落规则"), "LLM 补轮不可用时应回落规则补轮：" + thought);
        List<String> secondRoundTools = plannedToolsOf(response, 2);
        assertTrue(secondRoundTools.contains("web_search") && secondRoundTools.contains("weather_forecast"),
                "规则补轮应补齐仍为空的搜索结果与天气：" + secondRoundTools);
    }

    @Test
    void legacyMode_secondRoundDoesNotConsultLlm() {
        TravelAgent legacyAgent = newAgent("legacy");

        stubSequentialLlm(List.of(
                new LlmClient.LlmResult(
                        "{\"tools\":[{\"tool\":\"amap_poi\",\"query\":\"成都 景点\"}]}", 10, 5)));

        when(amapClient.searchPoi(anyString(), anyString()))
                .thenReturn(List.of(Map.of("name", "武侯祠")));
        stubWeather();
        when(bingSearchClient.searchAsText(anyString())).thenReturn("成都攻略正文");
        stubItinerary();

        AgentTraceResponse response = legacyAgent.execute(tripRequest());

        assertTrue(response.getSuccess());
        // legacy：补轮走规则，不额外咨询 LLM（保证改造前行为与测试基线不变）
        verify(llmClient, times(1)).chat(anyString());
        assertTrue(thoughtOf(response, 2) != null && thoughtOf(response, 2).contains("补充缺失数据"),
                "legacy 补轮应仍是规则缺口描述");
    }
}
