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
     * 3 天 + 多偏好行程输出结构较大，实测 45 秒不足，放宽到 180 秒。
     * 说明：主力链路走 SSE（{@code /trip/generate-stream}），前端用原生 fetch 读流、不受 axios
     * 120 秒超时约束；仅非流式 {@code /trip/generate} 仍受 120 秒约束。
     * ⚠️ 本注释曾残留旧值“90 秒”而常量早已改为 180，排查线上问题时极易被误导——改常量务必同步改注释。 */
    private static final int GENERATION_TIMEOUT_SECONDS = 180;
    /** JSON 修正 LLM 调用超时（秒）。
     * 修正步要把**整份行程 JSON** 重新输出一遍，输出量与主生成同量级（实测主生成约 68 秒成功）——
     * 原值 20 秒是结构性不足，不是“偶发慢响应”：§35 日志实录修正步在 20.000 秒整超时，
     * 导致整个请求失败（主生成其实已经成功）。
     * 注意：这只是解析失败后的一次补救，正常链路走不到——未转义控制字符已由本地修复
     * {@link #escapeRawControlChars} 处理，不需要回传 LLM。 */
    private static final int CORRECT_JSON_TIMEOUT_SECONDS = 60;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final MapEnrichmentService mapEnrichmentService;
    private final ItineraryValidator itineraryValidator;
    private final PersonalizedRankingService personalizedRankingService;

    public ItineraryGenerator(LlmClient llmClient, ObjectMapper objectMapper,
                              MapEnrichmentService mapEnrichmentService,
                              ItineraryValidator itineraryValidator,
                              PersonalizedRankingService personalizedRankingService) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.mapEnrichmentService = mapEnrichmentService;
        this.itineraryValidator = itineraryValidator;
        this.personalizedRankingService = personalizedRankingService;
    }

    /**
     * 生成行程
     */
    public Itinerary generate(TripRequest request, CollectedData collectedData) {
        // 0. 个性化候选排序（阶段三）：有登录用户与画像时，对 POI 候选做确定性打分重排并产出说明。
        //    说明写入 collectedData.personalizedNotes，供步骤 1 数据摘要与来源说明展示；
        //    未登录/无画像/失败 → 静默跳过，行为与个性化前一致。
        try {
            if (personalizedRankingService != null) {
                personalizedRankingService.rankAndFilter(request.getUserId(), request, collectedData);
            }
        } catch (Exception e) {
            log.warn("个性化候选排序失败（不影响生成）: {}", e.getMessage());
        }

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

        // 5. 来源说明：完全由代码拼装，丢弃 LLM 自述（LLM 常编造"预算明细/总和XX元"等
        //    与系统「预算明细」冲突的金额，v1.1 起多次补关键词仍漏网——根治：不让 LLM 写这个字段）。
        //    补两条 POI 来源 / 天气应对的事实性固定句，恢复信息量但不开放 LLM 自由发挥。
        itinerary.setSourceNotes(new ArrayList<>());
        itinerary.getSourceNotes().add("由 ReAct Agent 基于真实数据生成");
        itinerary.getSourceNotes().add("POI 数据均来自高德地图真实采集");
        itinerary.getSourceNotes().add("天气应对严格依据天气预报原文执行");
        addRagSourceNote(itinerary, collectedData);
        addUserMemoryNote(itinerary, collectedData);
        addPersonalizationNote(itinerary, collectedData);

        // 6. 补充高德地图信息（图片、坐标、地址）；成功时 source_notes 由 MapEnrichmentService 内部统一追加
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

        // 个性化候选排序说明（阶段三）：确定性打分结果，告诉模型靠前的候选更符合用户偏好。
        // 注意：POI 候选列表本身已按该排序重排（见 PersonalizedRankingService），此处只做强调。
        if (collectedData.getPersonalizedNotes() != null && !collectedData.getPersonalizedNotes().isEmpty()) {
            summary.append("\n【个性化候选参考】（依据用户画像的确定性评分，供取舍参考）\n");
            for (String note : collectedData.getPersonalizedNotes()) {
                summary.append("- ").append(note).append("\n");
            }
            summary.append("要求：同等条件下优先选择标记「匹配你的偏好」的候选；")
                    .append("对标记「近期不感兴趣/已降低优先级」的候选应避免安排；")
                    .append("标记「已体验过」的如无必要不再重复安排（除非用户点名要去）。\n");
        }

        // 用户长期记忆（个性化）：基于历史行程的画像，让模型延续用户偏好、避免重复推荐
        if (collectedData.getUserMemory() != null && !collectedData.getUserMemory().isBlank()) {
            summary.append("\n【用户历史记忆】\n").append(collectedData.getUserMemory()).append("\n");
            summary.append("要求：\n");
            summary.append("1. 本次目的地若与用户历史行程重复，请安排新玩法或明确区分上次体验\n");
            summary.append("2. 优先延续用户历史偏好的节奏与住宿档次（除非本次请求明确不同）\n");
            summary.append("3. 只参考上述记忆，不得编造记忆之外的用户经历\n");
        }

        return summary.toString();
    }

    /**
     * 构建生成提示词
     */
    private String buildGenerationPrompt(TripRequest request, String dataSummary) {
        long days = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
        // Q3 预算硬约束：按预算反推每晚酒店价上限（总住宿 ≤ 总预算 45%），让 LLM 生成时就对齐预算，
        // 而不是生成超支行程后再依赖校验层补救
        double effBudget = request.getBudget() != null && request.getBudget() > 0
                ? request.getBudget() : 5000;
        long nights = Math.max(1, days - 1);
        long hotelCapPerNight = Math.round(effBudget * 0.45 / nights);

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
                  "trip_id": "（由系统分配，固定填 null）",
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
                          "start_time": "HH:mm",
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
                4. 时间安排要合理，考虑景点间的距离；餐饮（meals）必须给出 start_time 并插在景点之间：早餐 07:00-08:30、午餐 11:30-13:00、晚餐 17:30-19:00，先结束前一景点的游览再去吃饭，同一时刻只能有一项活动，不得把全部餐饮排在景点之后
                5. 预算分配要符合用户总预算，餐饮人均、交通费用要贴合实际，不得明显偏低或虚高
                6. 每天安排2-4个主要景点
                7. transport 中的 from_place / to_place 必须使用【POI数据】中的真实地点名称或明确地标（如"洪崖洞""解放碑"），禁止使用"出发点""市区""酒店附近"等模糊表述；mode 必须明确（步行/地铁/公交/打车/驾车）
                8. 天气应对：若某天天气预报含 雷暴、暴雨、大雨、中雨、暴雪、大风、台风 等字眼，当天只能安排室内景点（博物馆、美术馆、科技馆、商场、书店等），禁止安排户外景点（海滩、山景、公园、江畔、骑行道等）；只有小毛毛雨、小雨、多云等轻微天气时可按正常安排并提示带伞
                9. 返回纯JSON，不要包含```json和```标记
                10. 每日备注（notes）中涉及天气的表述，必须严格使用【天气预报】表格中对应日期的天气描述原文（如"雷暴""小毛毛雨"），第N天只能引用表格中第N天的天气，不得自行改写或编造天气名称，也不得跨日期引用
                11. 每个景点的 description 必须严格围绕该景点本身撰写（该景点的建筑特色、历史、游玩要点），禁止张冠李戴引用其他景点/酒店/娱乐项目的介绍内容（例如给"博物馆"写"综合度假村、贡多拉游船"，或给"炮台/公园"写"商场购物、酒店体验"都是错误的）；若仅提及邻近地点可简短带过，但不得作为描述主体。address 字段必须填该景点自身的真实地理位置，不得填相邻街区或别的地点的地址；若必须提及邻近地点，只能一句话带过，绝不能作为描述主体
                12. 餐厅必须从【POI数据】的「餐厅」分类中选取真实餐厅名称（用户偏好含"美食/吃/餐厅"时务必优先使用），禁止自行编造餐厅；仅在「餐厅」分类为空时才允许推荐本地特色且须真实存在
                13. 酒店价格建模：经济型约 150-250 元/间/晚，舒适型约 300-500 元/间/晚，豪华型约 600-1200 元/间/晚。多人出行需按房间数计算：房间数≈ceil(人数/2)，总住宿=房间数×单间价×晚数；餐饮、门票按实际 人数累加；单价需贴合所选档次，不得明显偏低
14. source_notes（数据来源说明）只能说明数据来源（如「本地攻略命中 N 条」「酒店/餐厅来自高德POI」），禁止在其中核算或编造预算金额与总数；所有金额以系统「预算明细」为准，不要在 source_notes 里复述预算
15. spots 字段只能放可游览的景点/地标：禁止把入住的酒店、客栈、民宿、青年旅舍、公寓或任何餐厅/火锅店/饭店列为景点；也禁止把商铺（专卖店/门店/卖场/旗舰店/体验店等）、写字楼/办公楼、住宅小区等非游览商业场所列为景点。酒店必须只写在 hotel 字段，餐厅必须只写在 meals 字段；商铺/公寓类只允许在当天备注中提示"可顺路前往"，不得作为主要景点
16. 【用户点名景点】中列出的地点是用户明确要求，仅当其为可游览景点（景区/公园/博物馆/老街/山川湖海等）时才必须出现在 spots 中且不得遗漏；若确因数据源未收录或不在本城市而无法安排，必须在 source_notes 中说明原因，禁止静默忽略。若点名地点本质是商铺/公寓/写字楼/餐厅等非游览场所（名称含 专卖店/门店/公寓/客栈/餐厅 等），不得放入 spots、也不算"未安排"，如用户确有兴致只在当天备注写"可顺路前往"，严禁把这类场所排成主要景点
17. 用户特别要求中提到的具体地点或活动（例如"体验红花湖骑行"中的红花湖、"去XX拍照"中的XX）若为可游览地点，必须作为当天核心景点或活动安排到 spots 中，不得用其他无关景点（如海洋馆、商场）替代；若为商铺/公寓/写字楼等非游览场所，不得作为景点安排，只能作顺访提示。若用户表达的是活动（骑行/徒步/拍照/泡温泉），应安排到对应的真实地点，并在当天主题或备注中体现该活动，禁止只把它当成泛泛的"主题"而实际填入无关景点
18. 酒店档次与店型必须匹配：hotel.name 若为青年旅舍/民宿/公寓/客栈/招待所，level 只能标"经济型"；用户要求舒适型/高档型/豪华型时，必须选择与档次相符的真实酒店（名称通常是"XX大酒店/XX酒店/XX度假酒店"），禁止用青旅/民宿/公寓冒充高档
19. 旅行建议 tips 中不得把商铺、专卖店、公寓、写字楼等非游览场所写成"建议优先安排参观/必去"之类，仅可表述为"若感兴趣可顺路前往"
                20. 预算硬约束（必须遵守）：整份行程估算总花费不得超过用户总预算；其中总住宿（房间数×单间价×晚数）不得超过总预算的 45%%，即每晚单间价不得超过约 %d 元（本次共 %d 晚）。若你心仪的酒店档次价位超过该上限，必须主动降一档选择（豪华→高档→舒适→经济），并在 source_notes 中说明「为匹配预算已降低酒店档次」；禁止靠调低餐饮/交通价格来凑预算
                21. 就餐空间合理性（必须遵守）：每天的午餐/晚餐必须安排在与当天主要景点相同或相邻的片区（原则上距当天景点不超过 25 公里），按"上午景点 → 就近午餐 → 下午景点"的顺序成链。禁止出现"上午在远郊景区、中午回市区吃饭、下午再回远郊"这类物理上来不及的行程；若当天景点在郊区/郊县，午餐必须从当天景点所在片区的餐厅中选取（就近解决），不得跨区返回市区用餐

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
                dataSummary,
                hotelCapPerNight,
                nights
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
     * 尝试解析 JSON 为 Itinerary，失败返回 null。
     * 解析失败时先做一次**本地**修复（未转义控制字符），修复成功就不再回传 LLM 走 60 秒修正步。
     * 依据：LLM 在字符串值里夹带裸换行是高频缺陷，属于纯格式问题，本地可确定性修好；
     * 把它丢给 LLM 修正既慢（实测 20 秒超时→整个请求失败）又不可靠（模型可能再犯）。
     */
    private Itinerary tryParse(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) {
            return null;
        }
        Itinerary direct = tryParseStrict(jsonStr);
        if (direct != null) {
            return direct;
        }
        String repaired = escapeRawControlChars(jsonStr);
        if (!repaired.equals(jsonStr)) {
            Itinerary fixed = tryParseStrict(repaired);
            if (fixed != null) {
                log.warn("行程 JSON 含未转义控制字符，已在本地转义修复（未回传 LLM，省去修正步）");
                return fixed;
            }
        }
        return null;
    }

    /**
     * 严格解析（不做任何修复），失败返回 null
     */
    private Itinerary tryParseStrict(String jsonStr) {
        try {
            return objectMapper.readValue(jsonStr, Itinerary.class);
        } catch (Exception e) {
            log.debug("解析行程 JSON 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 转义 JSON 字符串字面量内部的裸控制字符（换行/回车/制表符等）。
     * <p>Jackson 默认严格模式会拒绝 {@code CTRL-CHAR code 10} 这类未转义字符，
     * 但 LLM 生成"多行描述"时经常直接换行，导致解析失败。这里按状态机只处理
     * **双引号内部**的字符：字符串外的缩进/换行一律不动，避免破坏 JSON 结构；
     * 已转义序列（如 {@code \n}、{@code \"}）整体保留，不会被二次转义。
     *
     * @return 转义后的文本；原文无需修改时逐字返回（调用方可据此判断有无改动）
     */
    static String escapeRawControlChars(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                // 前一个字符是反斜杠：当前字符属于已有转义序列，原样保留
                sb.append(c);
                escaped = false;
                continue;
            }
            if (!inString) {
                if (c == '"') {
                    inString = true;
                }
                sb.append(c);
                continue;
            }
            if (c == '\\') {
                sb.append(c);
                escaped = true;
                continue;
            }
            if (c == '"') {
                sb.append(c);
                inString = false;
                continue;
            }
            switch (c) {
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * 补充默认字段
     */
    private void fillDefaults(Itinerary itinerary, TripRequest request) {
        // trip_id 由服务端唯一分配：即使 LLM 按提示词回了 trip_{目的地}_{日期} 这类可重复值，也一律覆盖。
        // 原因：trip_record.trip_id 是全局唯一键，确定性 id 会让"不同用户同日同目的地"撞主键（保存 500）。
        itinerary.setTripId(generateTripId(request));
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
                    "meals": [{"name": "string", "meal_type": "string", "start_time": "string", "estimated_cost": 0.0, "notes": "string"}],
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
     * 来源说明：标注本次已结合用户历史行程定制（个性化可见，结果页"数据来源说明"展示）
     */
    private void addUserMemoryNote(Itinerary itinerary, CollectedData collectedData) {
        if (collectedData.getUserMemory() == null || collectedData.getUserMemory().isBlank()) {
            return;
        }
        try {
            if (itinerary.getSourceNotes() == null) {
                itinerary.setSourceNotes(new ArrayList<>());
            }
            itinerary.getSourceNotes().add("✨ 已结合你的历史行程定制（延续偏好、避免重复推荐）");
        } catch (Exception e) {
            log.debug("添加用户记忆来源说明失败: {}", e.getMessage());
        }
    }

    /**
     * 来源说明：标注本次候选经过个性化排序（阶段三）。取前几条代表性说明拼进
     * 「数据来源说明」，让结果页直接可见"系统按你的画像调整了候选优先级"。
     */
    private void addPersonalizationNote(Itinerary itinerary, CollectedData collectedData) {
        List<String> notes = collectedData.getPersonalizedNotes();
        if (notes == null || notes.isEmpty()) {
            return;
        }
        try {
            if (itinerary.getSourceNotes() == null) {
                itinerary.setSourceNotes(new ArrayList<>());
            }
            int shown = Math.min(3, notes.size());
            itinerary.getSourceNotes().add("🎯 已按你的偏好与历史对候选景点/餐厅排序（前 " + shown + " 条依据）：");
            for (int i = 0; i < shown; i++) {
                itinerary.getSourceNotes().add("· " + notes.get(i));
            }
        } catch (Exception e) {
            log.debug("添加个性化排序来源说明失败: {}", e.getMessage());
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
     * 生成行程 ID：保留「目的地 + 出发日期」的可读前缀，追加 8 位随机后缀保证全局唯一。
     *
     * 唯一性是硬要求：trip_record.trip_id 上有 UNIQUE 约束，而 trip_id 又是
     * agent_trace / recommendation_log / candidate_evidence / user_behavior 等表的关联键，
     * 一旦重复会同时破坏保存与按 trip_id 读取轨迹的正确性。
     * 纯「目的地_日期」在"两个用户规划同一城市同一出发日"时必然撞车，故必须带随机段。
     */
    private String generateTripId(TripRequest request) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return String.format("trip_%s_%s_%s",
                request.getDestination(),
                request.getStartDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                suffix);
    }
}
