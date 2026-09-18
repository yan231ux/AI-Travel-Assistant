package com.yuntu.tripplanner.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.client.AmapClient;
import com.yuntu.tripplanner.client.BingSearchClient;
import com.yuntu.tripplanner.client.LlmClient;
import com.yuntu.tripplanner.client.OpenMeteoClient;
import com.yuntu.tripplanner.config.LLMConfig;
import com.yuntu.tripplanner.model.AgentPlanArchive;
import com.yuntu.tripplanner.model.AgentTraceResponse;
import com.yuntu.tripplanner.model.AgentTraceStep;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.TripRequest;
import com.yuntu.tripplanner.model.WeatherForecastResponse;
import com.yuntu.tripplanner.service.AgentPlanArchiveService;
import com.yuntu.tripplanner.service.ItineraryGenerator;
import com.yuntu.tripplanner.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采集方案存档复用测试（ReAct 优化批次 3）。
 *
 * <p>核心命题：自主模式"只自主一次"——同用户+同目的地+同偏好首次生成后，把最终生效的
 * 首轮采集方案落库存档；再次生成直接复用，<b>不再问模型</b>（省 think LLM 调用 + 稳定候选池）。
 *
 * <p>同时锁死三条边界：
 * <ol>
 *   <li>legacy 模式完全不碰存档（保证改造前行为不变）；</li>
 *   <li>规则兜底方案（LLM 解析失败）不落库，避免偶发失败被长期复用；</li>
 *   <li>存档读写异常一律降级，绝不阻断行程生成。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class TravelAgentPlanArchiveTest {

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
    @Mock
    private AgentPlanArchiveService planArchiveService;

    private TravelAgent agent;

    @BeforeEach
    void setUp() {
        agent = newAgent("autonomous");
    }

    private TravelAgent newAgent(String mode) {
        LLMConfig config = new LLMConfig();
        config.setMaxIterations(3);
        config.setAgentMode(mode);
        config.setToolCalling("auto");
        Executor synchronous = Runnable::run;
        return new TravelAgent(config, llmClient, amapClient, openMeteoClient, bingSearchClient,
                itineraryGenerator, ragService, planArchiveService, synchronous, new ObjectMapper());
    }

    private TripRequest tripRequest() {
        TripRequest req = new TripRequest();
        req.setUserId("u_1001");
        req.setDestination("成都");
        req.setStartDate(LocalDate.of(2026, 5, 1));
        req.setEndDate(LocalDate.of(2026, 5, 3));
        req.setTravelers(2);
        req.setBudget(4000.0);
        return req;
    }

    /** 三个数据源全部返回成功，保证首轮采集后规则即判"数据足够"（reflect 已不调 LLM） */
    private void stubAllSourcesOk() {
        when(bingSearchClient.searchAsText(anyString())).thenReturn("成都三日游攻略正文");
        when(amapClient.searchPoi(anyString(), anyString()))
                .thenReturn(List.of(Map.of("name", "武侯祠")));
        WeatherForecastResponse weather = new WeatherForecastResponse();
        weather.setDays(List.of(new WeatherForecastResponse.WeatherDay()));
        when(openMeteoClient.getWeatherForecast(any(), any(), any())).thenReturn(weather);
        Itinerary itinerary = new Itinerary();
        itinerary.setTripId("trip_成都_2026-05-01");
        itinerary.setDestination("成都");
        itinerary.setDays(new ArrayList<>());
        itinerary.setSourceNotes(new ArrayList<>());
        when(itineraryGenerator.generate(any(), any())).thenReturn(itinerary);
    }

    private AgentPlanArchive archivedPlan() {
        AgentPlanArchive row = new AgentPlanArchive();
        row.setId(7L);
        row.setPlanKey("u_1001|成都|null|适中|舒适型|null|null");
        row.setUserId("u_1001");
        row.setDestination("成都");
        row.setPlanDesc("LLM 原生工具调用: web_search, amap_poi, weather_forecast");
        row.setSource(AgentPlanArchive.SOURCE_AUTONOMOUS_NATIVE);
        row.setToolCount(5);
        row.setReuseCount(2);
        row.setCreatedAt(LocalDateTime.now().minusDays(1));
        row.setUpdatedAt(LocalDateTime.now().minusDays(1));
        row.setPlanJson("{\"planDescription\":\"LLM 原生工具调用: web_search, amap_poi, weather_forecast\","
                + "\"source\":\"autonomous-native\",\"toolCalls\":["
                + "{\"tool\":\"web_search\",\"query\":\"成都 攻略\"},"
                + "{\"tool\":\"amap_poi\",\"query\":\"成都 景点\"},"
                + "{\"tool\":\"amap_poi\",\"query\":\"成都 餐厅\"},"
                + "{\"tool\":\"amap_poi\",\"query\":\"成都 酒店\"},"
                + "{\"tool\":\"weather_forecast\",\"query\":\"成都\"}]}");
        return row;
    }

    private String thoughtOfPlanSearch(AgentTraceResponse response) {
        for (AgentTraceStep step : response.getTrace()) {
            if ("plan_search".equals(step.getAction())) {
                return step.getThought();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 复用：命中存档 → 不再问模型（think 的 LLM 调用为 0）
    // ------------------------------------------------------------------

    @Test
    void autonomous_reusesArchivedPlanWithoutAskingLlm() {
        when(planArchiveService.findReusable(anyString())).thenReturn(Optional.of(archivedPlan()));
        stubAllSourcesOk();

        AgentTraceResponse response = agent.execute(tripRequest());

        assertTrue(response.getSuccess());
        // 复用的本质：首轮不必再让模型决策，think 的两次模型入口（文本 / 原生）都不该被调到
        verify(llmClient, never()).chat(anyString());
        verify(llmClient, never()).chatWithTools(any(), any());
        String thought = thoughtOfPlanSearch(response);
        assertTrue(thought != null && thought.contains("沿用上次成功的采集方案"),
                "轨迹应标注为沿用存档方案，便于答辩/运维一眼看出走了复用：" + thought);
        // 复用后仍会把（刷新过的）方案写回存档（延长有效期 + 更新观察摘要），不是只读
        verify(planArchiveService, times(1)).save(any());
    }

    // ------------------------------------------------------------------
    // 归档：首轮方案在采集结束后落库（含来源与观察摘要）
    // ------------------------------------------------------------------

    @Test
    void autonomous_archivesFirstRoundPlanAfterCollection() {
        when(planArchiveService.findReusable(anyString())).thenReturn(Optional.empty());
        when(llmClient.chat(anyString())).thenReturn(new LlmClient.LlmResult(
                "{\"tools\":[{\"tool\":\"web_search\",\"query\":\"成都 攻略\"},"
                        + "{\"tool\":\"amap_poi\",\"query\":\"成都 景点\"},"
                        + "{\"tool\":\"weather_forecast\",\"query\":\"成都\"}]}", 30, 10));
        stubAllSourcesOk();

        AgentTraceResponse response = agent.execute(tripRequest());

        assertTrue(response.getSuccess());
        ArgumentCaptor<AgentPlanArchive> captor = ArgumentCaptor.forClass(AgentPlanArchive.class);
        verify(planArchiveService, times(1)).save(captor.capture());
        AgentPlanArchive saved = captor.getValue();
        assertEquals("u_1001|成都|null|适中|舒适型|null|null", saved.getPlanKey());
        assertEquals("u_1001", saved.getUserId());
        assertEquals("成都", saved.getDestination());
        assertEquals(AgentPlanArchive.SOURCE_AUTONOMOUS_TEXT, saved.getSource());
        // web_search + amap 三类（景点/餐厅/酒店）+ weather
        assertEquals(5, saved.getToolCount());
        assertTrue(saved.getObservationSummary().contains("搜索结果"),
                "观察摘要应记录采集到的规模，作为「复用是否仍成立」的证据");
        assertTrue(saved.getPlanJson().contains("web_search"));
    }

    // ------------------------------------------------------------------
    // 边界 1：legacy 模式完全不碰存档
    // ------------------------------------------------------------------

    @Test
    void legacyModeNeverTouchesArchive() {
        TravelAgent legacy = newAgent("legacy");
        when(llmClient.chat(anyString())).thenReturn(new LlmClient.LlmResult(
                "{\"tools\":[{\"tool\":\"web_search\",\"query\":\"成都 攻略\"},"
                        + "{\"tool\":\"amap_poi\",\"query\":\"成都 景点\"},"
                        + "{\"tool\":\"weather_forecast\",\"query\":\"成都\"}]}", 30, 10));
        stubAllSourcesOk();

        AgentTraceResponse response = legacy.execute(tripRequest());

        assertTrue(response.getSuccess());
        verify(planArchiveService, never()).findReusable(anyString());
        verify(planArchiveService, never()).save(any());
    }

    // ------------------------------------------------------------------
    // 边界 2：规则兜底方案不落库（防偶发 LLM 失败被长期复用）
    // ------------------------------------------------------------------

    @Test
    void ruleFallbackPlanIsNotArchived() {
        when(planArchiveService.findReusable(anyString())).thenReturn(Optional.empty());
        // LLM 返回非 JSON 垃圾 → 解析失败 → buildDefaultPlan（source=rule）
        when(llmClient.chat(anyString())).thenReturn(new LlmClient.LlmResult("抱歉，我无法给出计划。", 10, 5));
        stubAllSourcesOk();

        AgentTraceResponse response = agent.execute(tripRequest());

        assertTrue(response.getSuccess());
        verify(planArchiveService, never()).save(any());
    }

    // ------------------------------------------------------------------
    // 边界 3：存档读写异常一律降级，不阻断生成
    // ------------------------------------------------------------------

    @Test
    void archiveLookupFailureFallsBackToNormalDecision() {
        when(planArchiveService.findReusable(anyString())).thenThrow(new RuntimeException("db down"));
        when(llmClient.chat(anyString())).thenReturn(new LlmClient.LlmResult(
                "{\"tools\":[{\"tool\":\"web_search\",\"query\":\"成都 攻略\"},"
                        + "{\"tool\":\"amap_poi\",\"query\":\"成都 景点\"},"
                        + "{\"tool\":\"weather_forecast\",\"query\":\"成都\"}]}", 30, 10));
        stubAllSourcesOk();

        AgentTraceResponse response = agent.execute(tripRequest());

        assertTrue(response.getSuccess());
        verify(llmClient, times(1)).chat(anyString());
    }

    @Test
    void archiveSaveFailureDoesNotBreakGeneration() {
        when(planArchiveService.findReusable(anyString())).thenReturn(Optional.empty());
        doThrow(new RuntimeException("db down")).when(planArchiveService).save(any());
        when(llmClient.chat(anyString())).thenReturn(new LlmClient.LlmResult(
                "{\"tools\":[{\"tool\":\"web_search\",\"query\":\"成都 攻略\"},"
                        + "{\"tool\":\"amap_poi\",\"query\":\"成都 景点\"},"
                        + "{\"tool\":\"weather_forecast\",\"query\":\"成都\"}]}", 30, 10));
        stubAllSourcesOk();

        AgentTraceResponse response = agent.execute(tripRequest());

        assertTrue(response.getSuccess(), "存档写入失败不能影响本次行程生成");
        assertNotNull(response.getItinerary());
    }

    // ------------------------------------------------------------------
    // 边界 4：存档内容损坏（非法 JSON）→ 不复用，走正常决策
    // ------------------------------------------------------------------

    @Test
    void corruptedArchiveJsonFallsBackToNormalDecision() {
        AgentPlanArchive corrupted = archivedPlan();
        corrupted.setPlanJson("{这不是合法 JSON");
        when(planArchiveService.findReusable(anyString())).thenReturn(Optional.of(corrupted));
        when(llmClient.chat(anyString())).thenReturn(new LlmClient.LlmResult(
                "{\"tools\":[{\"tool\":\"web_search\",\"query\":\"成都 攻略\"},"
                        + "{\"tool\":\"amap_poi\",\"query\":\"成都 景点\"},"
                        + "{\"tool\":\"weather_forecast\",\"query\":\"成都\"}]}", 30, 10));
        stubAllSourcesOk();

        AgentTraceResponse response = agent.execute(tripRequest());

        assertTrue(response.getSuccess());
        verify(llmClient, times(1)).chat(anyString());
    }
}
