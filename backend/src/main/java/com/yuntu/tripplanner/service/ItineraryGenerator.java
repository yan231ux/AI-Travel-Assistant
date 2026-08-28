package com.yuntu.tripplanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.agent.CollectedData;
import com.yuntu.tripplanner.client.LlmClient;
import com.yuntu.tripplanner.exception.TripGenerationException;
import com.yuntu.tripplanner.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 行程生成器
 *
 * 基于 LLM 生成结构化行程。token 消耗通过方法参数/返回值流动，
 * 不使用共享实例字段（修复并发竞态）。生成彻底失败时抛
 * {@link TripGenerationException}，由上层转成明确错误，不返回假兜底数据。
 */
@Slf4j
@Service
public class ItineraryGenerator {

    /** 主生成 LLM 调用超时（秒），防止模型慢响应导致前端无限卡“AI正在思考”。
     * 3 天 + 多偏好行程输出结构较大，实测 45 秒不足，放宽到 90 秒（前端 API 超时 120 秒）。 */
    private static final int GENERATION_TIMEOUT_SECONDS = 90;
    /** JSON 修正 LLM 调用超时（秒），修正 prompt 较短，响应应更快 */
    private static final int CORRECT_JSON_TIMEOUT_SECONDS = 20;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final MapEnrichmentService mapEnrichmentService;
    private final ItineraryValidator itineraryValidator;

    public ItineraryGenerator(LlmClient llmClient, ObjectMapper objectMapper,
                              MapEnrichmentService mapEnrichmentService,
                              ItineraryValidator itineraryValidator) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.mapEnrichmentService = mapEnrichmentService;
        this.itineraryValidator = itineraryValidator;
    }

    /**
     * 生成行程
     */
    public Itinerary generate(TripRequest request, CollectedData collectedData) {
        // 1. 准备数据摘要
        String dataSummary = prepareDataSummary(collectedData);

        // 2. 构建生成提示词并调用 LLM（planner），带超时兜底
        String prompt = buildGenerationPrompt(request, dataSummary);
        LlmClient.LlmResult result = chatWithTimeout(prompt, GENERATION_TIMEOUT_SECONDS);
        if (result == null) {
            throw new TripGenerationException("LLM 调用失败，无法生成行程");
        }

        TokenUsage usage = new TokenUsage();
        usage.setPlannerPromptTokens(result.promptTokens());
        usage.setPlannerCompletionTokens(result.completionTokens());

        // 3. 解析 JSON（失败自动修正一次，仍失败则抛异常）
        Itinerary itinerary = parseItinerary(result.content(), request, usage);

        // 记录生成时刻的天气快照，保证结果页天气表与行程每日备注口径一致
        Object forecast = collectedData.getWeatherData().get("forecast");
        if (forecast instanceof WeatherForecastResponse wf) {
            itinerary.setWeather(wf);
        }

        // 4. 写入 token 消耗（planner + rewrite + embedding）
        mergeEmbeddingUsage(usage, collectedData);
        itinerary.setTokenUsage(usage);

        // 5. 来源说明：标注 RAG 命中数
        addRagSourceNote(itinerary, collectedData);

        // 6. 补充高德地图信息（图片、坐标、地址）
        try {
            mapEnrichmentService.enrich(itinerary);
        } catch (Exception e) {
            log.warn("高德地图信息补全失败（不影响行程生成）: {}", e.getMessage());
        }

        // 7. 补充计算预算
        calculateBudget(itinerary);

        // 8. 校验层（硬约束）：跨天去重 / 真实性标注 / 恶劣天气 Plan B / 预算合理性修正
        try {
            itineraryValidator.validateAndRepair(itinerary, request, collectedData);
        } catch (Exception e) {
            log.warn("行程校验失败（不影响返回行程）: {}", e.getMessage());
        }
        // 预算修正可能改动了价格，重新计算预算分解，保证口径一致
        calculateBudget(itinerary);

        return itinerary;
    }

    /**
     * 带超时的 LLM 调用：Future.get 防止模型偶发慢响应导致前端无限等待。
     * 超时后抛出 TripGenerationException，由上层通过 SSE error 事件反馈给前端。
     */
    private LlmClient.LlmResult chatWithTimeout(String prompt, int timeoutSeconds) {
        try {
            return CompletableFuture.supplyAsync(() -> llmClient.chat(prompt))
                    .get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.error("生成行程 LLM 调用超时（{}s）", timeoutSeconds, e);
            throw new TripGenerationException("生成行程超时，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("生成行程 LLM 调用被中断", e);
            throw new TripGenerationException("生成行程被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("生成行程 LLM 调用失败", cause);
            throw new TripGenerationException("生成行程调用失败: " + cause.getMessage());
        }
    }

    /**
     * 准备数据摘要
     */
    private String prepareDataSummary(CollectedData collectedData) {
        StringBuilder summary = new StringBuilder();

        // 搜索结果摘要
        if (!collectedData.getSearchResults().isEmpty()) {
            summary.append("【搜索结果】\n");
            collectedData.getSearchResults().forEach((key, value) -> {
                summary.append(key).append(": \n").append(value).append("\n\n");
            });
        }

        // POI摘要
        if (!collectedData.getPoiResults().isEmpty()) {
            summary.append("\n【POI数据】\n");
            collectedData.getPoiResults().forEach((category, pois) -> {
                summary.append(category).append(": \n");
                if (pois instanceof List) {
                    List<?> poiList = (List<?>) pois;
                    for (int i = 0; i < Math.min(3, poiList.size()); i++) {
                        summary.append(poiList.get(i)).append("\n");
                    }
                }
                summary.append("\n");
            });
        }

        // 天气摘要：按日期格式化为一目了然的逐日表格，确保 LLM 能准确对号入座
        Object rawForecast = collectedData.getWeatherData().get("forecast");
        if (rawForecast instanceof WeatherForecastResponse forecast
                && forecast.getDays() != null && !forecast.getDays().isEmpty()) {
            summary.append("\n【天气预报】（第N天对应行程第N天）\n");
            List<WeatherForecastResponse.WeatherDay> days = forecast.getDays();
            for (int i = 0; i < days.size(); i++) {
                WeatherForecastResponse.WeatherDay d = days.get(i);
                summary.append(String.format("- %s（第%d天）：白天%s / 夜间%s，%s° / %s°%n",
                        d.getDate(), i + 1,
                        d.getDayWeather() == null ? "未知" : d.getDayWeather(),
                        d.getNightWeather() == null ? "未知" : d.getNightWeather(),
                        d.getDayTemp() == null ? "-" : d.getDayTemp(),
                        d.getNightTemp() == null ? "-" : d.getNightTemp()));
            }
        } else if (!collectedData.getWeatherData().isEmpty()) {
            summary.append("\n【天气预报】\n").append(collectedData.getWeatherData().toString()).append("\n");
        }

        // RAG摘要：直接取攻略正文文本，避免以 Map.toString() 形式（{guide=...}）塞给模型
        if (!collectedData.getRagData().isEmpty()) {
            Object rag = collectedData.getRagData().get("guide");
            if (rag != null) {
                summary.append("\n【本地攻略】\n").append(rag.toString()).append("\n");
            }
        }

        // 用户点名景点（最高优先级，必须安排进行程）
        if (collectedData.getRequestedSpots() != null && !collectedData.getRequestedSpots().isEmpty()) {
            summary.append("\n【用户点名景点】（用户明确要求，必须出现在行程中，不得遗漏）\n");
            for (String s : collectedData.getRequestedSpots()) {
                summary.append("- ").append(s).append("\n");
            }
        }

        return summary.toString();
    }

    /**
     * 构建生成提示词
     */
    private String buildGenerationPrompt(TripRequest request, String dataSummary) {
        long days = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;

        return String.format("""
                你是一位专业的旅行规划师。请基于以下信息生成一份详细的旅行行程。

                ## 用户需求
                - 目的地：%s
                - 出行日期：%s 至 %s（共%d天）
                - 旅行人数：%d人
                - 预算：%.0f 元
                - 旅行偏好：%s
                - 旅行节奏：%s
                - 酒店等级：%s
                - 饮食偏好：%s
                - 特别要求：%s

                ## 已收集的真实数据
                %s

                ## 输出要求
                请生成一个JSON格式的行程规划，包含以下字段：

                ```json
                {
                  "trip_id": "trip_{destination}_{start_date}",
                  "destination": "目的地",
                  "summary": "行程概述（100字以内）",
                  "days": [
                    {
                      "day_index": 1,
                      "date": "日期",
                      "theme": "当天主题",
                      "spots": [
                        {
                          "name": "景点名称",
                          "start_time": "HH:mm",
                          "end_time": "HH:mm",
                          "description": "推荐理由",
                          "estimated_cost": 门票价格,
                          "location": "位置",
                          "address": "详细地址"
                        }
                      ],
                      "meals": [
                        {
                          "name": "餐厅名称",
                          "meal_type": "午餐/晚餐",
                          "estimated_cost": 人均消费,
                          "notes": "推荐菜品"
                        }
                      ],
                      "hotel": {
                        "name": "参考酒店",
                        "level": "酒店等级",
                        "estimated_cost": 预估价格,
                        "location": "位置"
                      },
                      "transport": [
                        {
                          "mode": "交通方式",
                          "from_place": "起点",
                          "to_place": "终点",
                          "estimated_cost": 费用,
                          "duration": "时长",
                          "estimated_minutes": 分钟数
                        }
                      ],
                      "notes": ["当天注意事项"]
                    }
                  ],
                  "estimated_budget": 总预算,
                  "tips": ["旅行建议1", "旅行建议2"],
                  "source_notes": ["数据来源说明"]
                }
                ```

                ## 重要说明
                1. 景点、餐厅名称必须来自上面的真实数据（【POI数据】或【本地攻略】或【搜索结果】），禁止编造不存在的景点或餐厅
                2. 禁止重复：同一景点、同一餐厅在整份行程中只能出现一次，跨天也不得重复
                3. 餐厅必须选择当地特色餐厅（火锅、本帮菜、小吃等），禁止推荐连锁快餐或连锁品牌（如肯德基、麦当劳、汉堡王、星巴克、必胜客等）
                4. 时间安排要合理，考虑景点间的距离
                5. 预算分配要符合用户总预算，餐饮人均、交通费用要贴合实际，不得明显偏低或虚高
                6. 每天安排2-4个主要景点
                7. transport 中的 from_place / to_place 必须使用【POI数据】中的真实地点名称或明确地标（如"洪崖洞""解放碑"），禁止使用"出发点""市区""酒店附近"等模糊表述；mode 必须明确（步行/地铁/公交/打车/驾车）
                8. 天气应对：若某天天气预报含 雷、暴、雨、雪、冰、大风 等字眼，当天只能安排室内景点（博物馆、美术馆、科技馆、商场、书店等），禁止安排户外景点
                9. 返回纯JSON，不要包含```json和```标记
                10. 每日备注（notes）中涉及天气的表述，必须严格使用【天气预报】表格中对应日期的天气描述原文（如"雷暴""小毛毛雨"），第N天只能引用表格中第N天的天气，不得自行改写或编造天气名称，也不得跨日期引用
                11. 每个景点的 description 必须严格围绕该景点本身撰写（该景点的建筑特色、历史、游玩要点），禁止张冠李戴引用其他景点/酒店/娱乐项目的介绍内容（例如给"博物馆"写"综合度假村、贡多拉游船"，或给"炮台/公园"写"商场购物、酒店体验"都是错误的）；若仅提及邻近地点可简短带过，但不得作为描述主体。address 字段必须填该景点自身的真实地理位置，不得填相邻街区或别的地点的地址；若必须提及邻近地点，只能一句话带过，绝不能作为描述主体
                12. 餐厅必须从【POI数据】的「餐厅」分类中选取真实餐厅名称（用户偏好含"美食/吃/餐厅"时务必优先使用），禁止自行编造餐厅；仅在「餐厅」分类为空时才允许推荐本地特色且须真实存在
                13. 酒店价格建模：经济型约 150-250 元/间/晚，舒适型约 300-500 元/间/晚，豪华型约 600-1200 元/间/晚。多人出行需按房间数计算：房间数≈ceil(人数/2)，总住宿=房间数×单间价×晚数；餐饮、门票按实际 人数累加；单价需贴合所选档次，不得明显偏低
14. source_notes（数据来源说明）只能说明数据来源（如「本地攻略命中 N 条」「酒店/餐厅来自高德POI」），禁止在其中核算或编造预算金额与总数；所有金额以系统「预算明细」为准，不要在 source_notes 里复述预算
15. spots 字段只能放景点/地标，禁止把入住的酒店列为景点；酒店必须只写在 hotel 字段
16. 【用户点名景点】中列出的景点是用户明确要求，必须出现在行程 spots 中，不得遗漏；若确因数据源未收录或不在本城市而无法安排，必须在 source_notes 中说明原因，禁止静默忽略

                请开始生成：
                """,
                request.getDestination(),
                request.getStartDate(),
                request.getEndDate(),
                days,
                request.getTravelers(),
                request.getBudget() != null ? request.getBudget() : 5000,
                request.getPreferences() != null ? request.getPreferences() : "无特别偏好",
                request.getPace(),
                request.getHotelLevel(),
                request.getDietaryPreferences() != null ? request.getDietaryPreferences() : "无特别要求",
                request.getSpecialNotes() != null ? request.getSpecialNotes() : "无",
                dataSummary
        );
    }

    /**
     * 解析行程 JSON：失败自动修正一次，仍失败抛异常
     */
    private Itinerary parseItinerary(String aiResponse, TripRequest request, TokenUsage usage) {
        String jsonStr = extractJson(aiResponse);
        Itinerary itinerary = tryParse(jsonStr);
        if (itinerary != null) {
            fillDefaults(itinerary, request);
            return itinerary;
        }

        // 修正一次
        log.warn("行程 JSON 解析失败，尝试回传 LLM 修正");
        String corrected = correctJson(aiResponse, usage);
        if (corrected != null) {
            Itinerary fixed = tryParse(extractJson(corrected));
            if (fixed != null) {
                fillDefaults(fixed, request);
                return fixed;
            }
        }

        throw new TripGenerationException("LLM 输出无法解析为合法行程 JSON");
    }

    /**
     * 尝试解析 JSON 为 Itinerary，失败返回 null
     */
    private Itinerary tryParse(String jsonStr) {
        try {
            return objectMapper.readValue(jsonStr, Itinerary.class);
        } catch (Exception e) {
            log.debug("解析行程 JSON 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 补充默认字段
     */
    private void fillDefaults(Itinerary itinerary, TripRequest request) {
        if (itinerary.getTripId() == null) {
            itinerary.setTripId(generateTripId(request));
        }
        if (itinerary.getDestination() == null) {
            itinerary.setDestination(request.getDestination());
        }
        if (itinerary.getSourceNotes() == null) {
            itinerary.setSourceNotes(new ArrayList<>());
        }
        if (!itinerary.getSourceNotes().contains("由 ReAct Agent 基于真实数据生成")) {
            itinerary.getSourceNotes().add("由 ReAct Agent 基于真实数据生成");
        }
    }

    /**
     * 提取JSON字符串
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
     * 修正 JSON（回传 LLM 一次），成功返回修正后的 JSON 文本，失败返回 null
     */
    private String correctJson(String originalJson, TokenUsage usage) {
        String schema = """
                {
                  "trip_id": "string", "destination": "string", "summary": "string",
                  "days": [{
                    "day_index": 1, "date": "YYYY-MM-DD", "theme": "string",
                    "spots": [{"name": "string", "description": "string", "estimated_cost": 0.0}],
                    "meals": [{"name": "string", "meal_type": "string", "estimated_cost": 0.0, "notes": "string"}],
                    "hotel": {"name": "string", "level": "string", "estimated_cost": 0.0, "location": "string"},
                    "transport": [{"mode": "string", "estimated_cost": 0.0}],
                    "notes": ["string"]
                  }],
                  "estimated_budget": 0.0, "tips": ["string"], "source_notes": ["string"]
                }
                """;
        String prompt = String.format("""
                以下是生成行程时出现的JSON和错误信息。
                请修正这个JSON，使其严格符合下面的目标结构。

                目标结构：
                %s

                原始JSON：
                %s

                请只返回修正后的合法JSON，不要包含```json标记和任何说明文字。
                """, schema, originalJson);

        LlmClient.LlmResult result = chatWithTimeout(prompt, CORRECT_JSON_TIMEOUT_SECONDS);
        if (result != null && usage != null) {
            usage.setRewritePromptTokens(usage.getRewritePromptTokens() + result.promptTokens());
            usage.setRewriteCompletionTokens(usage.getRewriteCompletionTokens() + result.completionTokens());
        }
        return result == null ? null : result.content();
    }

    /**
     * 把 RAG 检索阶段产生的 embedding token 合并进行程 token 统计
     */
    private void mergeEmbeddingUsage(TokenUsage usage, CollectedData collectedData) {
        TokenUsage collected = collectedData.getTokenUsage();
        if (collected != null) {
            usage.setEmbeddingPromptTokens(collected.getEmbeddingPromptTokens());
            usage.setEmbeddingCompletionTokens(collected.getEmbeddingCompletionTokens());
        }
    }

    /**
     * 来源说明：标注 RAG 本地攻略命中数（面试/展示时体现 RAG 参与）
     */
    private void addRagSourceNote(Itinerary itinerary, CollectedData collectedData) {
        try {
            Object rag = collectedData.getRagData().get("guide");
            if (rag != null) {
                String text = rag.toString();
                int count = text.split("\\[来源:").length - 1;
                if (count > 0) {
                    if (itinerary.getSourceNotes() == null) {
                        itinerary.setSourceNotes(new ArrayList<>());
                    }
                    itinerary.getSourceNotes().add("本地攻略库命中 " + count + " 条（RAG）");
                }
            }
        } catch (Exception e) {
            log.debug("添加 RAG 来源说明失败: {}", e.getMessage());
        }
    }

    /**
     * 计算预算
     */
    private void calculateBudget(Itinerary itinerary) {
        if (itinerary.getDays() == null || itinerary.getDays().isEmpty()) {
            return;
        }

        BudgetBreakdown breakdown = new BudgetBreakdown();

        for (DayPlan day : itinerary.getDays()) {
            // 景点门票
            if (day.getSpots() != null) {
                for (SpotItem spot : day.getSpots()) {
                    if (spot.getEstimatedCost() != null) {
                        breakdown.setTickets(breakdown.getTickets() + spot.getEstimatedCost());
                    }
                }
            }

            // 餐饮
            if (day.getMeals() != null) {
                for (MealItem meal : day.getMeals()) {
                    if (meal.getEstimatedCost() != null) {
                        breakdown.setMeals(breakdown.getMeals() + meal.getEstimatedCost());
                    }
                }
            }

            // 酒店
            if (day.getHotel() != null && day.getHotel().getEstimatedCost() != null) {
                breakdown.setHotel(breakdown.getHotel() + day.getHotel().getEstimatedCost());
            }

            // 交通
            if (day.getTransport() != null) {
                for (TransportItem transport : day.getTransport()) {
                    if (transport.getEstimatedCost() != null) {
                        breakdown.setTransport(breakdown.getTransport() + transport.getEstimatedCost());
                    }
                }
            }
        }

        // 计算总计
        double total = breakdown.getTransport() + breakdown.getHotel() +
                breakdown.getMeals() + breakdown.getTickets() + breakdown.getOther();
        breakdown.setTotal(total);

        itinerary.setBudgetBreakdown(breakdown);
        itinerary.setEstimatedBudget(total);
    }

    /**
     * 生成行程ID
     */
    private String generateTripId(TripRequest request) {
        return String.format("trip_%s_%s",
                request.getDestination(),
                request.getStartDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
    }
}
