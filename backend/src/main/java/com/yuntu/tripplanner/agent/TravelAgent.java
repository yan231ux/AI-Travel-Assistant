package com.yuntu.tripplanner.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.client.AmapClient;
import com.yuntu.tripplanner.client.BingSearchClient;
import com.yuntu.tripplanner.client.LlmClient;
import com.yuntu.tripplanner.client.OpenMeteoClient;
import com.yuntu.tripplanner.config.LLMConfig;
import com.yuntu.tripplanner.model.AgentTraceResponse;
import com.yuntu.tripplanner.model.AgentTraceStep;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.TokenUsage;
import com.yuntu.tripplanner.model.TripRequest;
import com.yuntu.tripplanner.service.ItineraryGenerator;
import com.yuntu.tripplanner.service.RagService;
import com.yuntu.tripplanner.common.TagDictionary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * ReAct Travel Agent - 核心AI代理
 *
 * 实现 THINK → ACT → OBSERVE → 反思 → FINAL 循环。
 * - THINK: LLM 基于工具目录制定搜索计划（tool+query 成对 JSON，失败用规则兜底）
 * - ACT: 并行执行工具（专用线程池，先全部提交再统一等待，8 秒超时）
 * - OBSERVE: 规则先行短路，数据不足时不再白调 LLM；规则足够时 LLM 反思确认
 * - RAG 使用真实攻略数据（RagService），不编造
 * - token 消耗用 execute() 内局部变量累加，避免并发竞态
 */
@Slf4j
@Component
public class TravelAgent {

    private final LLMConfig llmConfig;
    private final LlmClient llmClient;
    private final AmapClient amapClient;
    private final OpenMeteoClient openMeteoClient;
    private final BingSearchClient bingSearchClient;
    private final ItineraryGenerator itineraryGenerator;
    private final RagService ragService;
    private final Executor toolExecutor;
    private final ObjectMapper objectMapper;

    // 并行工具执行的总超时
    private static final int TOOL_EXECUTION_TIMEOUT_SECONDS = 8;
    private static final String TOOL_WEB_SEARCH = "web_search";
    private static final String TOOL_WEATHER = "weather_forecast";
    private static final String TOOL_AMAP_POI = "amap_poi";
    private static final String TOOL_RAG = "rag_guide";

    /**
     * think 计划缓存：同一目的地+偏好下工具计划基本不变，短时复用可省一次 LLM 调用。
     * 内存缓存（计划对象小、无序列化开销），1 小时过期，超容量惰性清理。
     */
    private final Map<String, CachedPlan> planCache = new ConcurrentHashMap<>();
    private static final long PLAN_CACHE_TTL_MS = 60 * 60 * 1000L;

    /** 计划缓存条目：计划快照 + 过期时间 */
    private record CachedPlan(SearchPlan plan, long expireAt) {}

    /**
     * 构建动态工具目录（RAG 支持城市从 RagService 实时读取，避免新增攻略后 prompt 仍写死 6 城）。
     */
    private String buildToolCatalog() {
        Set<String> cities = ragService.getSupportedCities();
        String cityList = cities.isEmpty()
                ? "暂无"
                : String.join("/", cities);
        return """
                可用工具目录（由你决定调用哪些）：

                1. web_search(query)
                   联网搜索目的地的最新攻略、景点、餐厅、交通等信息。
                   参数 query: 搜索关键词，如 "成都 历史景点 美食 推荐"
                   适合场景：获取攻略文章、实时信息、未收录城市的资料。

                2. weather_forecast(location)
                   查询目的地未来天气预报（温度、降水概率、天气描述）。
                   参数 location: 目的地名称，如 "成都"。
                   适合场景：需要了解出行期间天气以安排室内/室外活动。

                3. amap_poi(destination, category)
                   查询高德地图POI，返回景点/餐厅/酒店的真实名称、地址、坐标、图片。
                   参数 destination: 目的地；category: 景点/餐厅/酒店/购物/交通。
                   适合场景：获取结构化地点数据（坐标+图片），用于地图展示。

                4. rag_guide(destination)
                   检索本地攻略知识库（支持城市：%s）。
                   参数 destination: 目的地名称。
                   适合场景：目的地在知识库内时，必须优先调用，可获得人工整理的精准攻略片段。
                """.formatted(cityList);
    }

    public TravelAgent(LLMConfig llmConfig,
                       LlmClient llmClient,
                       AmapClient amapClient,
                       OpenMeteoClient openMeteoClient,
                       BingSearchClient bingSearchClient,
                       ItineraryGenerator itineraryGenerator,
                       RagService ragService,
                       @Qualifier("toolExecutor") Executor toolExecutor,
                       ObjectMapper objectMapper) {
        this.llmConfig = llmConfig;
        this.llmClient = llmClient;
        this.amapClient = amapClient;
        this.openMeteoClient = openMeteoClient;
        this.bingSearchClient = bingSearchClient;
        this.itineraryGenerator = itineraryGenerator;
        this.ragService = ragService;
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行Agent循环，生成行程（无回调版本，兼容历史调用与测试）
     */
    public AgentTraceResponse execute(TripRequest request) {
        return execute(request, AgentCallback.NOOP);
    }

    /**
     * 执行Agent循环，生成行程。
     *
     * @param callback 非空时在关键节点回调：阶段完成推 onStep、阻塞调用前推 onProgress、
     *                 isClosed()==true 时提前退出（客户端断开场景）。实现必须自行吞掉异常。
     */
    public AgentTraceResponse execute(TripRequest request, AgentCallback callback) {
        AgentTraceResponse response = new AgentTraceResponse();
        CollectedData collectedData = new CollectedData();
        List<String> errors = new ArrayList<>();
        // 本轮 LLM（think/reflect）真实 token 累加，局部变量避免并发竞态
        TokenUsage agentUsage = new TokenUsage();

        // 预收集：解析用户特殊需求中点名的景点，为其发起定向 POI 查询，
        // 让模型在生成时能拿到"攻略未收录但用户指定"的景点真实数据（任何失败降级不阻断）
        collectRequestedSpots(request, collectedData);

        try {
            int maxIterations = llmConfig.getMaxIterations() != null ? llmConfig.getMaxIterations() : 3;

            for (int iteration = 0; iteration < maxIterations; iteration++) {
                log.info("=== Agent迭代 {} ===", iteration + 1);

                // 客户端已断开 → 提前退出，释放 agentExecutor 线程
                if (callback.isClosed()) {
                    return response;
                }

                // THINK: 制定搜索计划（首轮 LLM 决策，后续轮按缺口补充）
                callback.onProgress("think", String.format("正在制定搜索计划（第 %d 轮）", iteration + 1));
                SearchPlan plan = think(request, collectedData, iteration, agentUsage);

                // 记录本轮执行前已收集的数据量，用于判断本轮是否有新增
                int searchBefore = collectedData.getSearchResults().size();
                int poiBefore = collectedData.getPoiResults().size();
                int weatherBefore = collectedData.getWeatherData().size();
                int ragBefore = collectedData.getRagData().size();

                if (!plan.getToolCalls().isEmpty()) {
                    AgentTraceStep thinkStep = new AgentTraceStep();
                    thinkStep.setStep(response.getTrace().size() + 1);
                    thinkStep.setAction("plan_search");
                    thinkStep.setThought(plan.getPlanDescription());

                    List<Map<String, Object>> toolCalls = new ArrayList<>();
                    for (SearchPlan.ToolCall tc : plan.getToolCalls()) {
                        Map<String, Object> toolCall = new HashMap<>();
                        toolCall.put("tool", tc.getTool());
                        toolCall.put("query", tc.getQuery());
                        toolCalls.add(toolCall);
                    }
                    thinkStep.setToolCalls(toolCalls);
                    response.getTrace().add(thinkStep);
                    callback.onStep(thinkStep);

                    // ACT: 并行执行工具调用
                    callback.onProgress("act", "正在并行执行工具调用");
                    AgentTraceStep actStep = new AgentTraceStep();
                    actStep.setStep(response.getTrace().size() + 1);
                    actStep.setAction("tool_execution");

                    long startTime = System.currentTimeMillis();
                    executeTools(plan, request, collectedData, errors);
                    long duration = System.currentTimeMillis() - startTime;

                    actStep.setObservation(String.format("工具执行完成，耗时 %d ms", duration));
                    response.getTrace().add(actStep);
                    callback.onStep(actStep);
                }

                // 本轮是否真的收集到了新数据（无工具可补 / 工具都失败 → false）
                boolean newDataCollected =
                        collectedData.getSearchResults().size() > searchBefore
                                || collectedData.getPoiResults().size() > poiBefore
                                || collectedData.getWeatherData().size() > weatherBefore
                                || collectedData.getRagData().size() > ragBefore;

                // OBSERVE: 反思数据是否足够
                callback.onProgress("assess", "正在反思数据充分性");
                AgentThought reflection = reflect(request, collectedData, agentUsage);

                AgentTraceStep observeStep = new AgentTraceStep();
                observeStep.setStep(response.getTrace().size() + 1);
                observeStep.setAction("assess");
                observeStep.setThought(reflection.getThought());
                observeStep.setObservation(reflection.getObservation());
                response.getTrace().add(observeStep);
                callback.onStep(observeStep);

                // 判断是否足够
                if (Boolean.TRUE.equals(reflection.getEnough())) {
                    log.info("Agent判断数据已足够，结束循环");
                    break;
                }

                // 补充迭代未收集到任何新数据 → 再循环只会空转，直接进入生成
                if (iteration > 0 && !newDataCollected) {
                    log.info("本轮未收集到新数据，继续补充无意义，结束循环直接生成");
                    break;
                }

                if (iteration == maxIterations - 1) {
                    log.info("达到最大迭代次数 {}，强制进入生成阶段", maxIterations);
                }
            }

            // FINAL: 生成行程
            if (callback.isClosed()) {
                return response;
            }
            callback.onProgress("generate", "正在生成行程");
            AgentTraceStep finalStep = new AgentTraceStep();
            finalStep.setStep(response.getTrace().size() + 1);
            finalStep.setAction("generate");
            finalStep.setThought("基于收集的数据生成结构化行程");

            Itinerary itinerary = itineraryGenerator.generate(request, collectedData);
            response.setItinerary(itinerary);

            finalStep.setObservation("成功生成行程：" + itinerary.getTripId());
            response.getTrace().add(finalStep);
            callback.onStep(finalStep);

            // 汇总 token：think/reflect 消耗 + 行程内的 planner/rewrite/embedding 消耗
            response.setTokenUsage(mergeTokenUsage(agentUsage, itinerary.getTokenUsage()));
            response.setCollectedData(buildCollectedDataMap(collectedData));
            response.setSuccess(true);

        } catch (Exception e) {
            log.error("Agent执行失败", e);
            errors.add("Agent执行失败: " + e.getMessage());
            response.setSuccess(false);
        }

        response.setErrors(errors);
        return response;
    }

    /**
     * 汇总 agent 侧与行程侧的 token 消耗
     */
    private TokenUsage mergeTokenUsage(TokenUsage agentUsage, TokenUsage itineraryUsage) {
        TokenUsage total = new TokenUsage();
        total.setPromptTokens(agentUsage.getPromptTokens());
        total.setCompletionTokens(agentUsage.getCompletionTokens());
        if (itineraryUsage != null) {
            total.setPlannerPromptTokens(itineraryUsage.getPlannerPromptTokens());
            total.setPlannerCompletionTokens(itineraryUsage.getPlannerCompletionTokens());
            total.setRewritePromptTokens(itineraryUsage.getRewritePromptTokens());
            total.setRewriteCompletionTokens(itineraryUsage.getRewriteCompletionTokens());
            total.setEmbeddingPromptTokens(itineraryUsage.getEmbeddingPromptTokens());
            total.setEmbeddingCompletionTokens(itineraryUsage.getEmbeddingCompletionTokens());
        }
        return total;
    }

    /**
     * THINK: 制定搜索计划
     * 首轮用 LLM 基于工具目录决策（JSON 计划，解析失败用规则兜底）；
     * 后续轮按数据缺口补充缺失工具。
     */
    private SearchPlan think(TripRequest request, CollectedData collectedData, int iteration, TokenUsage usage) {
        SearchPlan plan = new SearchPlan();

        if (iteration == 0) {
            plan = cachedPlanOrBuild(request, usage);
            if (plan.getToolCalls().isEmpty()) {
                plan = buildDefaultPlan(request);
                plan.setPlanDescription("LLM 计划解析失败，使用默认策略");
            }
        } else {
            buildGapFillPlan(request, collectedData, plan);
        }
        return plan;
    }

    /**
     * think 计划缓存：同目的地+偏好（与日期/人数/预算无关，不影响工具选择）短时复用，
     * 命中直接返回计划快照，省一次 LLM 调用；未命中则 LLM 生成并写入缓存。
     */
    private SearchPlan cachedPlanOrBuild(TripRequest request, TokenUsage usage) {
        String key = buildPlanCacheKey(request);
        long now = System.currentTimeMillis();
        // 惰性清理过期项，防止无界增长
        if (planCache.size() > 300) {
            planCache.entrySet().removeIf(e -> e.getValue().expireAt() < now);
        }
        CachedPlan hit = planCache.get(key);
        if (hit != null && hit.expireAt() > now) {
            SearchPlan copy = copyPlan(hit.plan());
            copy.setPlanDescription((copy.getPlanDescription() == null ? "" : copy.getPlanDescription()) + "；计划缓存命中");
            log.info("think 计划缓存命中: {}", key);
            return copy;
        }
        SearchPlan plan = buildPlanFromLLM(request, usage);
        if (!plan.getToolCalls().isEmpty()) {
            planCache.put(key, new CachedPlan(copyPlan(plan), now + PLAN_CACHE_TTL_MS));
        }
        return plan;
    }

    /**
     * 计划缓存 key：只取影响"调哪些工具"的参数（工具选择与日期/人数/预算无关，排除以扩大命中面）
     */
    private String buildPlanCacheKey(TripRequest r) {
        return String.join("|",
                String.valueOf(r.getDestination()),
                String.valueOf(r.getPreferences()),
                String.valueOf(r.getPace()),
                String.valueOf(r.getHotelLevel()),
                String.valueOf(r.getDietaryPreferences()),
                String.valueOf(r.getSpecialNotes()));
    }

    /** 深拷贝计划（SearchPlan 可变，缓存存取必须拷贝，避免并发修改污染缓存/调用方） */
    private SearchPlan copyPlan(SearchPlan src) {
        SearchPlan copy = new SearchPlan();
        copy.setPlanDescription(src.getPlanDescription());
        List<SearchPlan.ToolCall> tcs = new ArrayList<>();
        if (src.getToolCalls() != null) {
            for (SearchPlan.ToolCall tc : src.getToolCalls()) {
                SearchPlan.ToolCall c = new SearchPlan.ToolCall();
                c.setTool(tc.getTool());
                c.setQuery(tc.getQuery());
                c.setReason(tc.getReason());
                tcs.add(c);
            }
        }
        copy.setToolCalls(tcs);
        return copy;
    }

    /**
     * 让 LLM 基于工具目录输出 tool+query 成对的 JSON 计划
     */
    private SearchPlan buildPlanFromLLM(TripRequest request, TokenUsage usage) {
        SearchPlan plan = new SearchPlan();
        try {
            String prompt = buildThinkPrompt(request);
            LlmClient.LlmResult result = callLlm(prompt, usage);
            if (result == null) {
                return plan;
            }

            List<SearchPlan.ToolCall> parsed = parseToolCalls(result.content());
            if (parsed.isEmpty()) {
                return plan;
            }

            List<String> toolNames = parsed.stream().map(SearchPlan.ToolCall::getTool).toList();
            plan.setPlanDescription("LLM 基于工具目录制定计划: " + String.join(", ", toolNames));

            String destination = request.getDestination();
            // amap_poi 类别集合：只要 LLM 选用了 amap_poi，最终统一展开为 景点/餐厅/酒店 三类，
            // 保证行程数据丰富（之前仅用 LLM 给的单一类别导致 POI 只有 1 类、数据偏薄）
            Set<String> poiCategories = new LinkedHashSet<>();
            for (SearchPlan.ToolCall tc : parsed) {
                switch (tc.getTool()) {
                    case TOOL_WEB_SEARCH -> plan.getToolCalls().add(
                            resolveQuery(tc, destination + " 景点 美食 攻略"));
                    case TOOL_WEATHER -> plan.getToolCalls().add(
                            resolveQuery(tc, destination));
                    case TOOL_AMAP_POI -> {
                        // 汇总 LLM 明确点名的类别（如有）
                        if (containsAmapCategory(tc.getQuery())) {
                            for (String cat : List.of("景点", "餐厅", "酒店", "购物", "交通")) {
                                if (tc.getQuery().contains(cat)) {
                                    poiCategories.add(cat);
                                }
                            }
                        }
                    }
                    case TOOL_RAG -> {
                        if (ragService.isKnownCity(destination)) {
                            plan.getToolCalls().add(createToolCall(TOOL_RAG, destination));
                        }
                    }
                    // 未知工具名忽略
                    default -> { /* 忽略未识别工具 */ }
                }
            }
            if (parsed.stream().anyMatch(tc -> TOOL_AMAP_POI.equals(tc.getTool()))) {
                poiCategories.add("景点");
                poiCategories.add("餐厅");
                poiCategories.add("酒店");
                for (String cat : poiCategories) {
                    plan.getToolCalls().add(createToolCall(TOOL_AMAP_POI, destination + " " + cat));
                }
            }

            // 已知城市必须调 RAG：即使 LLM 漏选，也强制追加，保证有攻略的城市能命中本地知识库。
            if (ragService.isKnownCity(destination)
                    && plan.getToolCalls().stream().noneMatch(tc -> TOOL_RAG.equals(tc.getTool()))) {
                plan.getToolCalls().add(createToolCall(TOOL_RAG, destination));
                plan.setPlanDescription(plan.getPlanDescription() + "; 强制追加 rag_guide");
            }
        } catch (Exception e) {
            log.warn("LLM 计划失败，使用默认策略: {}", e.getMessage());
        }
        return plan;
    }

    /**
     * 默认计划：调用全部工具（规则兜底）
     */
    private SearchPlan buildDefaultPlan(TripRequest request) {
        SearchPlan plan = new SearchPlan();
        plan.setPlanDescription("首轮采集：调用全部数据源");
        String destination = request.getDestination();
        plan.getToolCalls().add(createToolCall(TOOL_WEB_SEARCH,
                String.format("%s %s 景点推荐 攻略", destination,
                        request.getPreferences() != null ? String.join(" ", request.getPreferences()) : "")));
        plan.getToolCalls().add(createToolCall(TOOL_WEATHER, destination));
        plan.getToolCalls().add(createToolCall(TOOL_AMAP_POI, destination + " 景点"));
        plan.getToolCalls().add(createToolCall(TOOL_AMAP_POI, destination + " 餐厅"));
        plan.getToolCalls().add(createToolCall(TOOL_AMAP_POI, destination + " 酒店"));
        if (ragService.isKnownCity(destination)) {
            plan.getToolCalls().add(createToolCall(TOOL_RAG, destination));
        }
        return plan;
    }

    /**
     * 后续轮：按数据缺口补充缺失工具
     */
    private void buildGapFillPlan(TripRequest request, CollectedData collectedData, SearchPlan plan) {
        String destination = request.getDestination();
        List<String> missing = new ArrayList<>();

        if (collectedData.getSearchResults().isEmpty()) {
            missing.add(TOOL_WEB_SEARCH);
            plan.getToolCalls().add(createToolCall(TOOL_WEB_SEARCH, destination + " 旅行攻略 贴士"));
        }
        if (collectedData.getWeatherData().isEmpty()) {
            missing.add(TOOL_WEATHER);
            plan.getToolCalls().add(createToolCall(TOOL_WEATHER, destination));
        }
        if (collectedData.getPoiResults().isEmpty()) {
            missing.add(TOOL_AMAP_POI);
            plan.getToolCalls().add(createToolCall(TOOL_AMAP_POI, destination + " 景点"));
            plan.getToolCalls().add(createToolCall(TOOL_AMAP_POI, destination + " 餐厅"));
        }
        if (ragService.isKnownCity(destination) && collectedData.getRagData().isEmpty()) {
            missing.add(TOOL_RAG);
            plan.getToolCalls().add(createToolCall(TOOL_RAG, destination));
        }
        plan.setPlanDescription(missing.isEmpty() ? "无数据缺口，直接生成" : "补充缺失数据: " + String.join(", ", missing));
    }

    /**
     * 从 LLM 返回中解析 tool+query 列表（JSON 数组）
     */
    private List<SearchPlan.ToolCall> parseToolCalls(String text) {
        List<SearchPlan.ToolCall> calls = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return calls;
        }
        try {
            String json = extractJson(text);
            JsonNode root = objectMapper.readTree(json);
            JsonNode tools = root.path("tools");
            if (!tools.isArray()) {
                return calls;
            }
            for (JsonNode node : tools) {
                String tool = node.path("tool").asText("");
                if (tool.isBlank() || !isKnownTool(tool)) {
                    continue;
                }
                SearchPlan.ToolCall tc = new SearchPlan.ToolCall();
                tc.setTool(tool);
                String query = node.path("query").asText("");
                if (!query.isBlank()) {
                    tc.setQuery(query.trim());
                }
                calls.add(tc);
            }
        } catch (Exception e) {
            log.warn("解析 LLM 工具计划失败: {}", e.getMessage());
        }
        return calls;
    }

    private boolean isKnownTool(String tool) {
        return TOOL_WEB_SEARCH.equals(tool) || TOOL_WEATHER.equals(tool)
                || TOOL_AMAP_POI.equals(tool) || TOOL_RAG.equals(tool);
    }

    private boolean containsAmapCategory(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        return query.contains("景点") || query.contains("餐厅") || query.contains("酒店")
                || query.contains("购物") || query.contains("交通");
    }

    /**
     * 把 LLM 自由发挥的 POI 类别词归一化到标准桶（景点/餐厅/酒店/购物/交通），
     * 避免"旅游景点""火锅""小面"等词导致结果散落在不同桶、校验层取不到候选。
     */
    private String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return "景点";
        }
        if (raw.contains("餐厅") || raw.contains("美食") || raw.contains("火锅")
                || raw.contains("小吃") || raw.contains("餐馆") || raw.contains("小面")
                || raw.contains("面馆") || raw.contains("面食") || raw.contains("烧烤")
                || raw.contains("酒吧") || raw.contains("咖啡")) {
            return "餐厅";
        }
        if (raw.contains("酒店") || raw.contains("住宿") || raw.contains("宾馆")
                || raw.contains("民宿") || raw.contains("旅馆")) {
            return "酒店";
        }
        if (raw.contains("购物") || raw.contains("商场") || raw.contains("商业")
                || raw.contains("商圈")) {
            return "购物";
        }
        if (raw.contains("交通") || raw.contains("地铁") || raw.contains("车站")
                || raw.contains("公交") || raw.contains("机场") || raw.contains("高铁")) {
            return "交通";
        }
        // 其余一律视为景点（景点/景区/地标/索道/古镇/公园/打卡等）
        return "景点";
    }

    /**
     * 预收集：从用户特殊需求中解析"点名景点"，为其发起定向 POI 查询，
     * 让模型在生成时能拿到攻略未收录但真实存在的景点数据。
     * 点名景点来源 = 攻略卡片名反向匹配 ∪ 高德定向查询返回的 POI 名。
     * 任何失败都降级（不阻断生成）。
     */
    private void collectRequestedSpots(TripRequest request, CollectedData collectedData) {
        if (request.getSpecialNotes() == null || request.getSpecialNotes().isBlank()) {
            return;
        }
        List<String> requested = new ArrayList<>();

        // 1) 攻略卡片名反向匹配：用户文本里提到攻略里已有的景点（如"去四行仓库"）
        try {
            requested.addAll(ragService.findSpotCardNames(request.getDestination(), request.getSpecialNotes()));
        } catch (Exception e) {
            log.warn("攻略卡片名匹配失败（降级）: {}", e.getMessage());
        }

        // 2) 高德定向 POI 查询：把清洗后的特殊需求文本作为关键词精确查一次。
        //    单独存"指定景点"类别，避免被 prepareDataSummary 的每类前 3 条截断。
        String cleaned = cleanSpecialNotes(request.getSpecialNotes(), request.getDestination());
        if (cleaned.length() >= 2) {
            try {
                List<Map<String, Object>> pois = amapClient.searchPoi(request.getDestination(), cleaned);
                if (pois != null && !pois.isEmpty()) {
                    List<Map<String, Object>> merged = new ArrayList<>();
                    Set<String> names = new HashSet<>();
                    Object existing = collectedData.getPoiResults().get("指定景点");
                    if (existing instanceof List<?> list) {
                        for (Object o : list) {
                            if (o instanceof Map<?, ?> m) {
                                String n = String.valueOf(m.get("name"));
                                if (n != null && names.add(n)) {
                                    merged.add(new HashMap<>((Map<String, Object>) m));
                                }
                            }
                        }
                    }
                    for (Map<String, Object> p : pois) {
                        String n = String.valueOf(p.get("name"));
                        if (n != null && !"null".equals(n) && names.add(n)) {
                            merged.add(p);
                            requested.add(n);
                        }
                    }
                    collectedData.getPoiResults().put("指定景点", merged);
                }
            } catch (Exception e) {
                log.warn("指定景点定向查询失败（降级，不影响生成）: {}", e.getMessage());
            }
        }

        if (!requested.isEmpty()) {
            collectedData.setRequestedSpots(requested.stream().distinct().toList());
            log.info("用户点名景点（定向查询+攻略匹配）：{}", collectedData.getRequestedSpots());
        }
    }

    /**
     * 清洗特殊需求文本：去城市名、标点、常见"想去/必去/安排"等引导词，
     * 剩下来的核心短语交给高德做定向 POI 查询。
     */
    private String cleanSpecialNotes(String notes, String destination) {
        String t = notes;
        if (destination != null) {
            t = t.replace(destination, "");
        }
        for (String w : new String[]{"一定要去", "必须去", "别忘了去", "记得去", "务必去", "想去", "要去", "必去",
                "安排", "包括", "看看", "游玩", "参观", "顺便", "加上", "优先", "务必", "希望", "一定要", "记得", "去",
                "除了", "攻略", "里的", "我还", "如果", "的话", "另外", "还有", "然后", "最后", "之后", "先", "再", "并",
                "以及", "或者", "还是", "那里", "这里", "帮我", "想", "要", "和", "跟", "与"}) {
            t = t.replace(w, "");
        }
        return t.replaceAll("[\\s，。、！？；：,.!?;:（）()\"'“”「」]+", "").trim();
    }

    /**
     * LLM 给定了 query 就用 LLM 的，否则用默认 query
     */
    private SearchPlan.ToolCall resolveQuery(SearchPlan.ToolCall parsed, String defaultQuery) {
        if (parsed.getQuery() != null && !parsed.getQuery().isBlank()) {
            return parsed;
        }
        return createToolCall(parsed.getTool(), defaultQuery);
    }

    /**
     * 提取文本中的 JSON（第一个 { 到最后一个 }）
     */
    private String extractJson(String text) {
        int start = text.indexOf("{");
        int end = text.lastIndexOf("}");
        if (start != -1 && end != -1 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /**
     * ACT: 并行执行工具调用（先全部提交，再统一等待）
     */
    private void executeTools(SearchPlan plan, TripRequest request,
                              CollectedData collectedData, List<String> errors) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (SearchPlan.ToolCall toolCall : plan.getToolCalls()) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    executeTool(toolCall, request, collectedData);
                } catch (Exception e) {
                    log.error("工具执行失败: {}", toolCall.getTool(), e);
                    errors.add(String.format("工具 %s 执行失败: %s", toolCall.getTool(), e.getMessage()));
                    collectedData.getGaps().add(toolCall.getTool());
                }
            }, toolExecutor));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(TOOL_EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("工具执行被中断");
        } catch (Exception e) {
            log.warn("部分工具执行超时（{}s），基于已有数据继续", TOOL_EXECUTION_TIMEOUT_SECONDS);
        }
    }

    /**
     * 执行单个工具
     */
    private void executeTool(SearchPlan.ToolCall toolCall, TripRequest request,
                             CollectedData collectedData) {

        switch (toolCall.getTool()) {
            case TOOL_WEB_SEARCH -> {
                String searchResult = bingSearchClient.searchAsText(toolCall.getQuery());
                if (searchResult != null && !searchResult.isEmpty()) {
                    collectedData.getSearchResults().put(toolCall.getQuery(), searchResult);
                }
            }
            case TOOL_WEATHER -> {
                long days = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
                int forecastDays = (int) Math.max(3, Math.min(16, days));
                LocalDate endDate = request.getStartDate().plusDays(forecastDays - 1);
                var weather = openMeteoClient.getWeatherForecast(
                        request.getDestination(), request.getStartDate(), endDate);
                if (weather != null && weather.getDays() != null && !weather.getDays().isEmpty()) {
                    collectedData.getWeatherData().put("forecast", weather);
                }
            }
            case TOOL_AMAP_POI -> {
                String[] parts = toolCall.getQuery().split(" ", 2);
                String destination = parts[0];
                String category = normalizeCategory(parts.length > 1 ? parts[1] : "景点");
                List<Map<String, Object>> pois = amapClient.searchPoi(destination, category);
                if (pois != null && !pois.isEmpty()) {
                    // 同一类别多次查询（如"餐厅"+"火锅"）结果合并去重，避免覆盖
                    Object existing = collectedData.getPoiResults().get(category);
                    if (existing instanceof List<?> list && !list.isEmpty()) {
                        List<Map<String, Object>> merged = new ArrayList<>();
                        Set<String> names = new HashSet<>();
                        for (Object o : list) {
                            if (o instanceof Map<?, ?> m) {
                                String n = String.valueOf(m.get("name"));
                                if (n != null && !"null".equals(n) && names.add(n)) {
                                    merged.add(new HashMap<>((Map<String, Object>) m));
                                }
                            }
                        }
                        for (Map<String, Object> p : pois) {
                            String n = String.valueOf(p.get("name"));
                            if (n != null && !"null".equals(n) && names.add(n)) {
                                merged.add(p);
                            }
                        }
                        collectedData.getPoiResults().put(category, merged);
                    } else {
                        collectedData.getPoiResults().put(category, pois);
                    }
                }
            }
            case TOOL_RAG -> {
                List<String> ragChunks = ragService.search(request.getDestination(),
                        buildRagQuery(request), 5, collectedData.getTokenUsage(), extractTags(request));
                if (!ragChunks.isEmpty()) {
                    collectedData.getRagData().put("guide", String.join("\n\n", ragChunks));
                }
            }
            default -> log.warn("未知工具: {}", toolCall.getTool());
        }
    }

    /**
     * 构建 RAG 检索关键词（目的地+偏好+节奏+备注），并经查询改写扩展口语化表达
     */
    private String buildRagQuery(TripRequest request) {
        List<String> parts = new ArrayList<>();
        parts.add(request.getDestination());
        if (request.getPreferences() != null) parts.addAll(request.getPreferences());
        if (request.getPace() != null) parts.add(request.getPace());
        if (request.getSpecialNotes() != null) parts.add(request.getSpecialNotes());
        return ragService.expandQuery(String.join(" ", parts));
    }

    /**
     * 从用户需求中抽取约束标签（RAG 元数据过滤用）。
     * 来源：preferences + specialNotes；统一使用 TagDictionary 匹配，
     * 保证与攻略标注维度严格一致，避免手工同步两份词典漏命中。
     */
    private Set<String> extractTags(TripRequest request) {
        StringBuilder text = new StringBuilder();
        if (request.getPreferences() != null) {
            request.getPreferences().forEach(p -> text.append(p).append(' '));
        }
        if (request.getSpecialNotes() != null) {
            text.append(request.getSpecialNotes()).append(' ');
        }
        return TagDictionary.match(text.toString());
    }

    /**
     * OBSERVE: 反思数据是否足够
     * 规则是唯一裁决：不足时直接返回并触发补充；足够时调 LLM 产出简短反思文本作为过程展示，
     * 但 LLM 不参与"是否足够"的裁决（避免过苛模型反复判"不够"导致无意义循环）。
     */
    private AgentThought reflect(TripRequest request, CollectedData collectedData, TokenUsage usage) {
        AgentThought thought = new AgentThought();
        thought.setObservation(buildDataSummary(collectedData));

        boolean hasSearch = !collectedData.getSearchResults().isEmpty();
        boolean hasPoi = !collectedData.getPoiResults().isEmpty();
        boolean hasWeather = !collectedData.getWeatherData().isEmpty();
        boolean hasRag = !collectedData.getRagData().isEmpty() || ragService.isKnownCity(request.getDestination());

        // 规则兜底：有 POI 且有搜索或 RAG 且有天气，视为足够
        boolean ruleEnough = hasPoi && (hasSearch || hasRag) && hasWeather;

        // 规则已判定不足 → 跳过 LLM 往返（规则判定不够时 LLM 也无法让其足够）
        if (!ruleEnough) {
            thought.setThought("规则判断：数据不足，需要补充");
            thought.setEnough(false);
            return thought;
        }

        // 规则已判定足够 → 不再白调一次 LLM 反思（它是纯展示、不参与结论，直接省掉串行往返）
        thought.setThought(buildRuleSufficientText(collectedData));
        thought.setEnough(true);
        return thought;
    }

    /**
     * 规则判定"数据足够"时的展示文本（替代原 LLM 反思调用，省一次模型往返）
     */
    private String buildRuleSufficientText(CollectedData collectedData) {
        List<String> parts = new ArrayList<>();
        if (!collectedData.getPoiResults().isEmpty()) {
            parts.add("POI " + collectedData.getPoiResults().size() + " 类");
        }
        if (!collectedData.getWeatherData().isEmpty()) {
            parts.add("天气");
        }
        if (!collectedData.getRagData().isEmpty()) {
            parts.add("RAG 本地攻略");
        }
        if (!collectedData.getSearchResults().isEmpty()) {
            parts.add("搜索结果");
        }
        return "规则判断：数据已足够（" + String.join("、", parts) + "），无需继续补充，直接进入生成";
    }

    /**
     * 调用 LLM 并累加 token 到本地统计（无共享状态，线程安全）
     */
    private LlmClient.LlmResult callLlm(String prompt, TokenUsage usage) {
        LlmClient.LlmResult result = llmClient.chat(prompt);
        if (result != null && usage != null) {
            usage.setPromptTokens(usage.getPromptTokens() + result.promptTokens());
            usage.setCompletionTokens(usage.getCompletionTokens() + result.completionTokens());
        }
        return result;
    }

    /**
     * 构建思考提示词（注入工具目录，要求 tool+query 成对输出）
     */
    private String buildThinkPrompt(TripRequest request) {
        return String.format("""
                %s

                用户需求：
                - 目的地：%s
                - 日期：%s 至 %s
                - 旅行人数：%d
                - 预算：%.0f 元
                - 偏好：%s
                - 节奏：%s
                - 特别要求：%s

                请从工具目录中挑选本次规划需要调用的工具，并为每个工具给出具体的搜索关键词。
                只返回如下 JSON，不要包含其他说明文字：
                {"tools": [{"tool": "web_search", "query": "成都 历史景点 美食 推荐"}, {"tool": "amap_poi", "query": "成都 景点"}, {"tool": "weather_forecast", "query": "成都"}]}
                """,
                buildToolCatalog(),
                request.getDestination(),
                request.getStartDate(),
                request.getEndDate(),
                request.getTravelers(),
                request.getBudget() != null ? request.getBudget() : 0,
                request.getPreferences() != null ? request.getPreferences() : "无特别偏好",
                request.getPace(),
                request.getSpecialNotes() != null ? request.getSpecialNotes() : "无"
        );
    }

    /**
     * 构建反思提示词
     * 仅在规则已判定足够后调用：只让 LLM 基于已有数据做简短正面总结（≤3 句），
     * 不参与"是否足够"的裁决，避免过苛模型反复判"不够"导致无意义循环。
     */
    private String buildReflectPrompt(CollectedData collectedData) {
        return String.format("""
                已收集的数据：
                - 搜索结果：%d 条
                - POI数据：%d 类别
                - 天气数据：%s
                - RAG数据：%s

                规则判定：当前数据已满足生成行程所需的最低要求（具备 POI、天气，以及搜索或本地攻略之一），结论为【足够】。

                请基于以上数据，用不超过 3 句话简要说明它们能支撑生成行程的哪些维度（例如景点选择、天气应对、本地攻略、美食与住宿线索）。
                不要质疑数据是否足够，不要列举缺失项。最后单独一行输出：
                结论：足够
                """,
                collectedData.getSearchResults().size(),
                collectedData.getPoiResults().size(),
                collectedData.getWeatherData().isEmpty() ? "无" : "已获取",
                collectedData.getRagData().isEmpty() ? "无" : "已获取"
        );
    }

    /**
     * 构建数据摘要
     */
    private String buildDataSummary(CollectedData collectedData) {
        return String.format("搜索结果 %d 条，POI %d 类，天气 %s，RAG %s",
                collectedData.getSearchResults().size(),
                collectedData.getPoiResults().size(),
                collectedData.getWeatherData().isEmpty() ? "无" : "有",
                collectedData.getRagData().isEmpty() ? "无" : "有"
        );
    }

    private SearchPlan.ToolCall createToolCall(String tool, String query) {
        SearchPlan.ToolCall tc = new SearchPlan.ToolCall();
        tc.setTool(tool);
        tc.setQuery(query);
        return tc;
    }

    private Map<String, Object> buildCollectedDataMap(CollectedData collectedData) {
        Map<String, Object> result = new HashMap<>();
        result.put("search_results", collectedData.getSearchResults());
        result.put("poi_results", collectedData.getPoiResults());
        result.put("weather_data", collectedData.getWeatherData());
        result.put("rag_data", collectedData.getRagData());
        return result;
    }
}
