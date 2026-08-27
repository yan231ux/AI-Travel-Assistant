package com.yuntu.tripplanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.agent.CollectedData;
import com.yuntu.tripplanner.client.LlmClient;
import com.yuntu.tripplanner.model.DayPlan;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.MealItem;
import com.yuntu.tripplanner.model.SpotItem;
import com.yuntu.tripplanner.model.TransportItem;
import com.yuntu.tripplanner.model.TripRequest;
import com.yuntu.tripplanner.model.WeatherForecastResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 行程校验层（硬约束，P0/P1 数据可信度优化）。
 *
 * <p>在 LLM 生成行程后、返回前端前执行，逐项校验并修复：
 * <ol>
 *   <li><b>跨天去重</b>：同一景点/餐厅只允许出现一次，重复项尝试用高德真实候选池替换；
 *       连锁快餐（肯德基/麦当劳/汉堡王等）同样替换为候选池中的本地餐厅；</li>
 *   <li><b>真实性校验 + 来源标签</b>：每个景点/餐厅名称能在高德POI（或本地攻略原文）中命中的
 *       标「高德POI/本地攻略」，否则标「LLM建议（需核实）」，前端可据此展示可信度；</li>
 *   <li><b>恶劣天气 Plan B</b>：天气预报含雷/暴/雨/雪/冰等字眼的天，尝试把户外景点替换为
 *       候选池中的室内景点（博物馆/美术馆/商场等）；无候选则在备注中给出警示；</li>
 *   <li><b>预算合理性</b>：估算总预算与用户预算偏差过大（<30% 或 >150%）时，
 *       回传 LLM 按用户预算重新分配价格一次（仅改价格，不动结构与名称）。</li>
 * </ol>
 */
@Slf4j
@Service
public class ItineraryValidator {

    /** 连锁快餐/连锁餐饮品牌（程序兜底，提示词已禁止但仍防 LLM 不听话）。
     *  覆盖大陆常见连锁 + 港式连锁 + 日式国际连锁。 */
    private static final List<String> CHAIN_RESTAURANTS =
            List.of("肯德基", "麦当劳", "汉堡王", "星巴克", "必胜客", "瑞幸", "蜜雪冰城", "赛百味", "德克士",
                    "大家乐", "翠华", "太兴", "谭仔", "北京楼", "美心", "添好运", "大快活",
                    "一风堂", "一兰", "吉野家", "味千", "萨莉亚", "快乐蜂", "绿茶");

    /** 恶劣天气关键词（出现任一 → 当天按室内安排） */
    private static final List<String> BAD_WEATHER_WORDS = List.of("雷", "暴", "雨", "雪", "冰", "大风", "台风", "沙尘");

    /** 室内景点关键词（Plan B 替换时从候选池里挑） */
    private static final List<String> INDOOR_KEYWORDS =
            List.of("博物馆", "美术馆", "科技馆", "图书馆", "艺术馆", "展览", "商场", "购物", "书店", "剧院", "纪念馆", "会展", "馆");

    /** 行政区划后缀（名称规范化去重用） */
    private static final List<String> PLACE_SUFFIXES = List.of("风景区", "景区", "公园", "古镇", "老街", "景点");

    /** 地理通名后缀：描述中合理提及的邻近地理区域（三亚湾/大东海/凤凰岛等）不视为"混入其他地点" */
    private static final List<String> GEO_SUFFIXES =
            List.of("湾", "海", "港", "滩", "岸", "岛", "湖", "河", "江",
                    "路", "大道", "街", "巷", "广场", "大桥", "隧道", "码头", "机场", "车站");

    /** 预算偏差阈值：总预算 &lt; 用户预算30% 或 &gt; 150% 视为不合理，触发修正 */
    private static final double BUDGET_LOW_RATIO = 0.3;
    private static final double BUDGET_HIGH_RATIO = 1.5;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public ItineraryValidator(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 对生成后的行程执行校验与修复（原地修改 itinerary）。
     */
    public void validateAndRepair(Itinerary itinerary, TripRequest request, CollectedData collectedData) {
        if (itinerary == null || itinerary.getDays() == null || itinerary.getDays().isEmpty()) {
            return;
        }
        List<String> poiSpotNames = collectPoiNames(collectedData, "景点");
        List<String> poiMealNames = collectPoiNames(collectedData, "餐厅");
        String ragText = collectedData.getRagData() == null
                ? null : String.valueOf(collectedData.getRagData().get("guide"));

        // 1. 景点：跨天去重 + 真实性标注
        Set<String> usedSpots = new HashSet<>();
        List<String> spotPool = new ArrayList<>(poiSpotNames);
        for (DayPlan day : itinerary.getDays()) {
            if (day.getSpots() == null) {
                continue;
            }
            for (SpotItem spot : day.getSpots()) {
                String norm = normalize(spot.getName());
                if (isDuplicate(norm, usedSpots)) {
                    String replacement = popFree(spotPool, usedSpots);
                    if (replacement != null) {
                        spot.setName(replacement);
                        spot.setSource("高德POI");
                        usedSpots.add(normalize(replacement));
                    } else {
                        spot.setSource("LLM建议（需核实）");
                    }
                } else {
                    usedSpots.add(norm);
                }
                if (spot.getSource() == null) {
                    spot.setSource(resolveSource(spot.getName(), poiSpotNames, ragText));
                }
            }
        }

        // 2. 餐厅：跨天去重 + 连锁快餐替换 + 真实性标注
        // 替换候选池排除连锁品牌（避免去重/连锁替换时把汉堡王等选进候选）
        List<String> mealPool = poiMealNames.stream()
                .filter(n -> n != null
                        && CHAIN_RESTAURANTS.stream().noneMatch(n::contains))
                .collect(Collectors.toCollection(ArrayList::new));
        Set<String> usedMeals = new HashSet<>();
        for (DayPlan day : itinerary.getDays()) {
            if (day.getMeals() == null) {
                continue;
            }
            for (MealItem meal : day.getMeals()) {
                String norm = normalize(meal.getName());
                boolean chain = meal.getName() != null
                        && CHAIN_RESTAURANTS.stream().anyMatch(meal.getName()::contains);
                if (isDuplicate(norm, usedMeals) || chain) {
                    // 连锁替换时先从候选池排除自身（POI 池可能含同名连锁店），避免"替换成自己"无效替换
                    if (chain) {
                        mealPool.removeIf(n -> n != null && normalize(n).equals(norm));
                    }
                    String replacement = popFree(mealPool, usedMeals);
                    if (replacement != null) {
                        meal.setName(replacement);
                        meal.setSource("高德POI");
                        usedMeals.add(normalize(replacement));
                    } else {
                        meal.setSource("LLM建议（需核实）");
                    }
                } else {
                    usedMeals.add(norm);
                }
                if (meal.getSource() == null) {
                    meal.setSource(resolveSource(meal.getName(), poiMealNames, ragText));
                }
            }
        }

        // 3. Plan B：恶劣天气日 → 替换为室内候选（无候选则备注警示）
        applyPlanB(itinerary, collectedData, poiSpotNames, usedSpots, spotPool, ragText);

        // 4. 交通：未由高德路线补全的项，标注为估算（LLM）
        for (DayPlan day : itinerary.getDays()) {
            if (day.getTransport() == null) {
                continue;
            }
            for (TransportItem t : day.getTransport()) {
                if (t.getSource() == null) {
                    t.setSource("估算（LLM）");
                }
            }
        }

        // 5. 预算合理性：偏差过大 → 回传 LLM 按预算修正一次
        repairBudget(itinerary, request);

        // 6. 酒店晚数自检：行程天数 vs 有价格的酒店天数，明显少算晚数时给出警示
        checkHotelNights(itinerary);

        // 7. 景点描述交叉检测：描述中出现其他地点名 → 警示（防 LLM 张冠李戴）
        checkDescriptionMismatch(itinerary, poiSpotNames);

        // 8. 住宿人数合理性：多人出行按单间价计价（未按人数配房）→ 警示
        checkHotelCapacity(itinerary, request);
    }

    /**
     * 景点描述/地址交叉检测：LLM 常把其他景点/酒店的内容写进某景点的 description
     * （如给"博物馆"写"综合度假村、贡多拉游船"）。若描述或地址中出现 POI 池/其他景点/酒店的名称
     * （且非自身），在当天 notes 加警示，不篡改内容。
     */
    private void checkDescriptionMismatch(Itinerary itinerary, List<String> poiSpotNames) {
        Set<String> knownNames = new HashSet<>();
        if (poiSpotNames != null) {
            knownNames.addAll(poiSpotNames);
        }
        // 行程内所有景点名 + 酒店名，作为"可能被张冠李戴提及"的已知地点
        for (DayPlan day : itinerary.getDays()) {
            if (day.getSpots() != null) {
                for (SpotItem s : day.getSpots()) {
                    if (s.getName() != null && !s.getName().isBlank()) {
                        knownNames.add(s.getName());
                    }
                }
            }
            if (day.getHotel() != null && day.getHotel().getName() != null
                    && !day.getHotel().getName().isBlank()) {
                knownNames.add(day.getHotel().getName());
            }
        }

        for (DayPlan day : itinerary.getDays()) {
            if (day.getSpots() == null) {
                continue;
            }
            for (SpotItem spot : day.getSpots()) {
                if (spot.getName() == null || spot.getName().isBlank()) {
                    continue;
                }
                String self = spot.getName();
                List<String> mentioned = new ArrayList<>();
                // 描述、地址与当日备注都可能被混入其他地点名（LLM 常把错位内容写进备注）
                String text = (spot.getDescription() == null ? "" : spot.getDescription())
                        + (spot.getAddress() == null ? "" : spot.getAddress())
                        + (day.getNotes() == null ? "" : String.join(" ", day.getNotes()));
                if (text.isBlank()) {
                    continue;
                }
                for (String n : knownNames) {
                    if (n == null || n.isBlank()) {
                        continue;
                    }
                    // 排除自身（描述提到自己的名字是正常的）
                    if (self.contains(n) || n.contains(self)) {
                        continue;
                    }
                    // 排除地理区域通名（"三亚湾""大东海""凤凰岛"等合理提及不算张冠李戴）
                    if (isGeoName(n)) {
                        continue;
                    }
                    if (text.contains(n)) {
                        mentioned.add(n);
                    }
                }
                if (!mentioned.isEmpty()) {
                    if (day.getNotes() == null) {
                        day.setNotes(new ArrayList<>());
                    }
                    day.getNotes().add("⚠️ 景点「" + self + "」的描述或地址疑似混入其他地点内容（提及："
                            + String.join("、", mentioned) + "），请核实");
                }
            }
        }
    }

    /**
     * 住宿人数合理性：3 人以上出行时，酒店单晚价按"每晚每人"估算，
     * 若明显偏低（<150 元/人/晚）说明可能只按一间房计价、未按人数分配多间房，给出警示。
     */
    private void checkHotelCapacity(Itinerary itinerary, TripRequest request) {
        if (request.getTravelers() == null || request.getTravelers() < 3) {
            return;
        }
        int travelers = request.getTravelers();
        for (DayPlan day : itinerary.getDays()) {
            if (day.getHotel() == null || day.getHotel().getEstimatedCost() == null
                    || day.getHotel().getEstimatedCost() <= 0) {
                continue;
            }
            double perPersonPerNight = day.getHotel().getEstimatedCost() / travelers;
            if (perPersonPerNight < 150) {
                if (itinerary.getSourceNotes() == null) {
                    itinerary.setSourceNotes(new ArrayList<>());
                }
                itinerary.getSourceNotes().add(String.format(
                        "⚠️ 系统检测：%d 人行程酒店约 %.0f 元/晚（约 %.0f 元/人/晚），可能未按人数分配多间房，请核实",
                        travelers, day.getHotel().getEstimatedCost(), perPersonPerNight));
                return;
            }
        }
    }

    /**
     * 酒店晚数自检：多日行程通常需计 (天数-1) 晚住宿（最后一天退房）；
     * 若 LLM 只填了远少于该数的酒店费用，说明可能少算住宿晚数（如 3 天只算 1 晚）。
     * 以 source_notes 警示，不强行改写价格。
     */
    private void checkHotelNights(Itinerary itinerary) {
        if (itinerary.getDays() == null || itinerary.getDays().size() <= 1) {
            return;
        }
        int days = itinerary.getDays().size();
        int nightsWithCost = 0;
        for (DayPlan day : itinerary.getDays()) {
            if (day.getHotel() != null && day.getHotel().getEstimatedCost() != null
                    && day.getHotel().getEstimatedCost() > 0) {
                nightsWithCost++;
            }
        }
        // 期望至少 (days-1) 晚有费用；少于此说明可能漏算
        if (nightsWithCost < days - 1) {
            if (itinerary.getSourceNotes() == null) {
                itinerary.setSourceNotes(new ArrayList<>());
            }
            itinerary.getSourceNotes().add(String.format(
                    "⚠️ 系统检测：行程 %d 天但酒店仅计 %d 晚，可能少算住宿晚数，请核实", days, nightsWithCost));
            log.warn("酒店晚数自检：行程 {} 天，酒店仅计 {} 晚（可能漏算）", days, nightsWithCost);
        }
    }

    /**
     * 恶劣天气日 Plan B：把当天非室内景点替换为候选池中的室内景点。
     */
    private void applyPlanB(Itinerary itinerary, CollectedData collectedData,
                            List<String> poiSpotNames, Set<String> usedSpots,
                            List<String> spotPool, String ragText) {
        Object raw = collectedData.getWeatherData() == null
                ? null : collectedData.getWeatherData().get("forecast");
        if (!(raw instanceof WeatherForecastResponse wf)
                || wf.getDays() == null || wf.getDays().isEmpty()) {
            return;
        }
        Map<Integer, String> badDays = new HashMap<>();
        for (int i = 0; i < wf.getDays().size(); i++) {
            WeatherForecastResponse.WeatherDay d = wf.getDays().get(i);
            String text = String.valueOf(d.getDayWeather()) + " " + String.valueOf(d.getNightWeather());
            if (BAD_WEATHER_WORDS.stream().anyMatch(text::contains)) {
                badDays.put(i + 1, text.trim());
            }
        }
        if (badDays.isEmpty()) {
            return;
        }

        List<String> indoorPool = poiSpotNames.stream()
                .filter(n -> n != null && INDOOR_KEYWORDS.stream().anyMatch(n::contains))
                .collect(Collectors.toCollection(ArrayList::new));

        for (DayPlan day : itinerary.getDays()) {
            String badWeather = day.getDayIndex() == null ? null : badDays.get(day.getDayIndex());
            if (badWeather == null || day.getSpots() == null) {
                continue;
            }
            boolean anyIndoor = false;
            for (SpotItem spot : day.getSpots()) {
                String nm = spot.getName() == null ? "" : spot.getName();
                boolean indoor = INDOOR_KEYWORDS.stream().anyMatch(nm::contains);
                if (!indoor) {
                    String replacement = popFree(indoorPool, usedSpots);
                    if (replacement != null) {
                        spot.setName(replacement);
                        spot.setSource("高德POI");
                        usedSpots.add(normalize(replacement));
                        indoor = true;
                    }
                }
                if (indoor) {
                    anyIndoor = true;
                }
                if (spot.getSource() == null) {
                    spot.setSource(resolveSource(spot.getName(), poiSpotNames, ragText));
                }
            }
            if (!anyIndoor) {
                if (day.getNotes() == null) {
                    day.setNotes(new ArrayList<>());
                }
                day.getNotes().add("⚠️ 当日天气「" + badWeather + "」恶劣，建议以室内活动为主");
            }
        }
    }

    /**
     * 预算合理性：与用户预算偏差过大时，回传 LLM 仅调整价格字段（不增删项目）。
     */
    private void repairBudget(Itinerary itinerary, TripRequest request) {
        if (request.getBudget() == null || request.getBudget() <= 0
                || itinerary.getEstimatedBudget() == null) {
            return;
        }
        double budget = request.getBudget();
        double total = itinerary.getEstimatedBudget();
        boolean tooLow = total < budget * BUDGET_LOW_RATIO;
        boolean tooHigh = total > budget * BUDGET_HIGH_RATIO;
        if (!tooLow && !tooHigh) {
            return;
        }
        log.info("预算不合理：估算 {} 元，用户预算 {} 元（{}），触发按预算修正", total, budget,
                tooLow ? "明显偏低" : "明显超支");

        try {
            String json = objectMapper.writeValueAsString(itinerary);
            String prompt = String.format("""
                    用户总预算为 %.0f 元，当前行程估算总预算 %.0f 元，%s。
                    请调整行程 JSON 中各项目的 estimated_cost 与 estimated_budget，使其与用户总预算匹配，
                    且餐饮人均、交通费用、门票价格贴近现实（不要过低也不要虚高），酒店价格与酒店等级相符。
                    要求：
                    1. 不得增删、不得重排任何 day / spot / meal / transport / hotel 项目，只修改价格数值与 estimated_budget；
                    2. 保持 JSON 结构、字段名、所有名称与时间完全不变；
                    3. 只返回调整后的合法 JSON，不要包含任何说明文字或代码块标记。

                    行程 JSON：
                    %s
                    """, budget, total, tooLow ? "明显偏低，请按预算上调并贴近实际" : "明显超支，请按预算压缩", json);

            LlmClient.LlmResult result = llmClient.chat(prompt);
            if (result == null || result.content() == null || result.content().isBlank()) {
                return;
            }
            Itinerary adjusted = objectMapper.readValue(extractJson(result.content()), Itinerary.class);
            if (adjusted == null || adjusted.getDays() == null) {
                return;
            }

            // 按 day_index + 列表位置匹配回填价格，保留原行程的高德补全信息（图片/坐标）
            Map<Integer, DayPlan> adjByIndex = new HashMap<>();
            for (DayPlan d : adjusted.getDays()) {
                if (d.getDayIndex() != null) {
                    adjByIndex.put(d.getDayIndex(), d);
                }
            }
            for (DayPlan day : itinerary.getDays()) {
                DayPlan adj = adjByIndex.get(day.getDayIndex());
                if (adj == null) {
                    continue;
                }
                backfillCost(day.getSpots(), adj.getSpots(), SpotItem::getEstimatedCost, SpotItem::setEstimatedCost);
                backfillCost(day.getMeals(), adj.getMeals(), MealItem::getEstimatedCost, MealItem::setEstimatedCost);
                backfillCost(day.getTransport(), adj.getTransport(),
                        TransportItem::getEstimatedCost, TransportItem::setEstimatedCost);
                if (day.getHotel() != null && adj.getHotel() != null
                        && adj.getHotel().getEstimatedCost() != null) {
                    day.getHotel().setEstimatedCost(adj.getHotel().getEstimatedCost());
                }
            }
            log.info("预算修正完成：估算总预算将由上层按新价格重新计算");
        } catch (Exception e) {
            log.warn("预算修正失败（保留原行程）: {}", e.getMessage());
        }
    }

    /** 按位置回填价格（LLM 保持结构不变，故按下标对齐） */
    private <T> void backfillCost(List<T> src, List<T> ref,
                                  java.util.function.Function<T, Double> getter,
                                  java.util.function.BiConsumer<T, Double> setter) {
        if (src == null || ref == null) {
            return;
        }
        int n = Math.min(src.size(), ref.size());
        for (int i = 0; i < n; i++) {
            Double cost = getter.apply(ref.get(i));
            if (cost != null) {
                setter.accept(src.get(i), cost);
            }
        }
    }

    /**
     * 从候选池中取一个未被使用且非空的名字（取后从池中移除，避免重复选用）。
     * "已使用"按互相包含判定，避免替换进与已用地点同名的变体。
     */
    private String popFree(List<String> pool, Set<String> used) {
        Iterator<String> it = pool.iterator();
        while (it.hasNext()) {
            String c = it.next();
            if (c != null && !c.isBlank() && !isDuplicate(normalize(c), used)) {
                it.remove();
                return c;
            }
        }
        return null;
    }

    /**
     * 去重判定：精确相等，或两字以上互相包含（"洪崖洞" vs "洪崖洞民俗风貌区"、"解放碑" vs "解放碑步行街" 视为同一地）。
     */
    private boolean isDuplicate(String norm, Set<String> used) {
        if (norm == null || norm.isEmpty()) {
            return false;
        }
        if (used.contains(norm)) {
            return true;
        }
        if (norm.length() < 2) {
            return false;
        }
        for (String u : used) {
            if (u.length() >= 2 && (norm.contains(u) || u.contains(norm))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断名称是否来自真实数据：高德POI 命中（含包含关系）→ 高德POI；攻略原文命中 → 本地攻略；否则 LLM 建议。
     */
    private String resolveSource(String name, List<String> knownNames, String ragText) {
        if (name == null || name.isBlank()) {
            return "LLM建议（需核实）";
        }
        String norm = normalize(name);
        for (String k : knownNames) {
            if (k == null) {
                continue;
            }
            String kn = normalize(k);
            if (kn.equals(norm)) {
                return "高德POI";
            }
            if (kn.length() >= 3 && norm.length() >= 3 && (kn.contains(norm) || norm.contains(kn))) {
                return "高德POI";
            }
        }
        if (ragText != null && !ragText.isBlank() && ragText.contains(name)) {
            return "本地攻略";
        }
        return "LLM建议（需核实）";
    }

    /** 从收集数据中提取某类 POI 的名称列表 */
    private List<String> collectPoiNames(CollectedData collectedData, String category) {
        List<String> names = new ArrayList<>();
        if (collectedData.getPoiResults() == null) {
            return names;
        }
        Object raw = collectedData.getPoiResults().get(category);
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Object n = m.get("name");
                    if (n != null && !n.toString().isBlank()) {
                        names.add(n.toString());
                    }
                }
            }
        }
        return names;
    }

    /** 名称规范化：去空白、去行政区划后缀，用于去重与匹配 */
    private String normalize(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("[\\s\\u3000]", "");
        for (String suffix : PLACE_SUFFIXES) {
            if (t.length() > suffix.length() && t.endsWith(suffix)) {
                t = t.substring(0, t.length() - suffix.length());
                break;
            }
        }
        return t;
    }

    /** 是否为地理区域通名（以湾/海/岛/路/街等地理通名结尾），合理提及不算张冠李戴 */
    private boolean isGeoName(String name) {
        String n = normalize(name);
        if (n.length() < 2) {
            return false;
        }
        for (String suffix : GEO_SUFFIXES) {
            if (n.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** 提取文本中的 JSON（第一个 { 到最后一个 }） */
    private String extractJson(String text) {
        int start = text.indexOf("{");
        int end = text.lastIndexOf("}");
        if (start != -1 && end != -1 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}
