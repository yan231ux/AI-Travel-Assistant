package com.yuntu.tripplanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.agent.CollectedData;
import com.yuntu.tripplanner.client.LlmClient;
import com.yuntu.tripplanner.common.SightseeingFilter;
import com.yuntu.tripplanner.common.SpotText;
import com.yuntu.tripplanner.model.CandidateEvidence;
import com.yuntu.tripplanner.model.DayPlan;
import com.yuntu.tripplanner.model.FilteredCandidate;
import com.yuntu.tripplanner.model.HotelItem;
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

    /** 恶劣天气关键词（出现任一 → 当天按室内安排）。
     *  拆细"雨"：雷暴/大雨/中雨才触发室内化，「小毛毛雨」「小雨」只带伞不误判。 */
    private static final List<String> BAD_WEATHER_WORDS = List.of("雷", "暴", "雪", "冰", "大风", "台风", "沙尘", "暴雨", "大雨", "中雨");

    /** 住宿/餐饮/商铺/公寓等"非游览场所"的判定统一收敛到 common.SightseeingFilter
     *  （高德 type 业态优先 + 名称兜底），避免多份词表漂移，此处不再各自维护关键词表。 */

    /**
     * 经济定位住宿关键词：名称含这些词的住宿本质是经济型（床位/民宿/公寓），
     * 若 LLM 把 hotel.level 标成 舒适型/高档型/豪华型 却选了这类店（如"高档型住青旅"），
     * 必须按实际降级为经济型并警示，不能让用户花高档价住青旅。
     */
    private static final List<String> ECONOMY_LODGING_KEYWORDS =
            List.of("青年旅舍", "青旅", "旅舍", "招待所", "民宿", "公寓", "旅馆", "客栈");

    /** 室内景点关键词（Plan B 替换时从候选池里挑） */
    private static final List<String> INDOOR_KEYWORDS =
            List.of("博物馆", "美术馆", "科技馆", "图书馆", "艺术馆", "展览", "商场", "购物", "书店", "剧院", "纪念馆", "会展", "馆");

    /** 行政区划后缀（名称规范化去重用）；"风景名胜区"须在"风景区"之前（先匹配更长的） */
    private static final List<String> PLACE_SUFFIXES = List.of("风景名胜区", "风景区", "景区", "公园", "古镇", "老街", "景点");

    /** 地理通名后缀：描述中合理提及的邻近地理区域（三亚湾/大东海/凤凰岛等）不视为"混入其他地点" */
    private static final List<String> GEO_SUFFIXES =
            List.of("湾", "海", "港", "滩", "岸", "岛", "湖", "河", "江",
                    "路", "大道", "街", "巷", "广场", "大桥", "隧道", "码头", "机场", "车站");

    /** 简介兜底文案：整段简介被判定为复制/无真实资料时统一降级（防"编造别处简介"） */
    private static final String DESC_FALLBACK = SpotText.NO_GUIDE_DESC;
    /** 地址待核实兜底文案（多处分叉共用，统一常量防漂移） */
    private static final String ADDR_PENDING = "（地址待核实）";

    /** 简介复用判定阈值（跨景点 description 去重，dedupeCrossSpotMedia 分支3）：
     *  完全相同判定要求规范化文本 ≥16 字符，防"适合拍照/看日落"等通用短句误伤；
     *  高相似判定要求 ≥40 字符且相似度 ≥0.90；地址已被标"待核实"的景点（多重异常信号）
     *  阈值放宽到 0.82。 */
    private static final int DESC_SAME_MIN_LEN = 16;
    private static final int DESC_SIM_MIN_LEN = 40;
    private static final double DESC_SIM_THRESHOLD = 0.90;
    private static final double DESC_SIM_THRESHOLD_WEAK = 0.82;

    /** 预算偏差阈值：总预算 &lt; 用户预算30% 或 &gt; 150% 视为不合理，触发修正 */
    private static final double BUDGET_LOW_RATIO = 0.3;
    private static final double BUDGET_HIGH_RATIO = 1.5;

    /** 预算使用率低于此值 → 诚实说明"预算没用出去"的原因（防用户以为系统算错钱） */
    private static final double BUDGET_UNDERUSE_RATIO = 0.6;

    /** 每日景点数下限：完整游玩日 ≥2、返程日（最后一天）≥1。
     *  仅当候选池还有未使用的真实景点时才补（确定性、零 token），
     *  避免"5 天只有 4 个景点"的空心行程。 */
    private static final int MIN_SPOTS_FULL_DAY = 2;
    private static final int MIN_SPOTS_LAST_DAY = 1;

    /** 住宿档次 → 每晚参考价（与 {@link #hotelLevelForPrice} 的价位口径自洽）。
     *  高德 POI 不提供房价，仅在"用户要高档型/豪华型、而 LLM 选了经济型公寓"、
     *  且候选池里确实存在非经济型酒店时，按用户所选档次给出估价——
     *  保证同一页面里"档次"与"价格"不互相打架。 */
    private static final Map<String, Double> TIER_PER_NIGHT = Map.of(
            "舒适型", 380.0, "高档型", 520.0, "豪华型", 760.0);

    /** 酒店位置合理性阈值(km)：酒店到"所有"景点距离均超过此值 → 视为跨片区，警示 */
    private static final double HOTEL_FAR_KM = 30.0;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final RagService ragService;

    public ItineraryValidator(LlmClient llmClient, ObjectMapper objectMapper, RagService ragService) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.ragService = ragService;
    }

    /**
     * 对生成后的行程执行校验与修复（原地修改 itinerary）。
     */
    public void validateAndRepair(Itinerary itinerary, TripRequest request, CollectedData collectedData) {
        if (itinerary == null || itinerary.getDays() == null || itinerary.getDays().isEmpty()) {
            return;
        }
        // 0.0 行程骨架对齐（天数 = 出行日期跨度 / 每日景点数下限 / trip_days 对齐）。
        //     幂等：正常流程已在生成器"补图片坐标之前"调用过一次（补入的景点才能拿到图片与坐标），
        //     这里再兜一次，保证任何单独调用校验层的路径同样满足结构约束。
        syncStructure(itinerary, request, collectedData);

        List<String> poiSpotNames = collectPoiNames(collectedData, "景点");
        List<String> poiMealNames = collectPoiNames(collectedData, "餐厅");
        String ragText = collectedData.getRagData() == null
                ? null : String.valueOf(collectedData.getRagData().get("guide"));

        // 0. 真实 POI 地址映射：直接用高德 POI 的地址覆盖景点/酒店地址，杜绝 LLM 写错地理位置
        Map<String, String> poiAddressMap = collectPoiAddressMap(collectedData);
        for (DayPlan day : itinerary.getDays()) {
            if (day.getSpots() != null) {
                for (SpotItem spot : day.getSpots()) {
                    if (spot.getName() == null) {
                        continue;
                    }
                    String addr = bestPoiAddress(poiAddressMap, spot.getName());
                    if (addr != null) {
                        spot.setAddress(addr);
                    }
                }
            }
            if (day.getHotel() != null && day.getHotel().getName() != null) {
                String addr = bestPoiAddress(poiAddressMap, day.getHotel().getName());
                if (addr != null) {
                    day.getHotel().setLocation(addr);
                }
            }
        }

        // 0.1 地址真实性硬校验（防"地址被其他景点占用"的张冠李戴，如四方街被写成木府官院巷49号）
        verifySpotAddresses(itinerary, poiAddressMap);

        // 0.5 攻略卡片事实回写：地址/简介/门票用人工整理的攻略卡片强制覆盖（事实锚定）。
        // 有攻略覆盖的景点，事实 100% 来自攻略，杜绝 LLM 张冠李戴。
        applyGuideCardFacts(itinerary, request);

        // 1. 景点：跨天去重 + 真实性标注
        Set<String> usedSpots = new HashSet<>();
        List<String> spotPool = new ArrayList<>(poiSpotNames);
        for (DayPlan day : itinerary.getDays()) {
            String hotelName = day.getHotel() != null ? day.getHotel().getName() : null;
            if (day.getSpots() == null) {
                continue;
            }
            // 1.5 移除被错误地当作景点列出的非游览场所
            // 判定两路：①与当天酒店同名/互相包含（原逻辑，只覆盖同店）；②高德业态/名称命中非游览
            //（住宿/餐饮/商铺/公寓/写字楼——覆盖"大隐国际青年旅舍""登巴客栈(XX店)""绿茶餐厅"
            // 及"火星人集成灶(XX店)""来自火星公寓"这类点名场所被 LLM 误排的场景）。
            // ⚠️ 酒店同名判定用"核心名"（去括号后的主体）：避免"可见时光·望达斯旅舍(杭州西湖湖滨河坊街店)"
            //    这种长店名里带地理定位词（西湖/河坊街），把真实景点"西湖""河坊街"误删。
            String hnCore = hotelName == null ? "" : normalize(hotelName.replaceAll("[（(][^）)]*[）)]", ""));
            Iterator<SpotItem> it = day.getSpots().iterator();
            List<String> removedByKind = new ArrayList<>();
            while (it.hasNext()) {
                SpotItem s = it.next();
                if (s.getName() == null) {
                    continue;
                }
                String sn = normalize(s.getName());
                boolean isSameHotel = !hnCore.isEmpty()
                        && (sn.equals(hnCore) || sn.contains(hnCore)
                        || (hnCore.length() >= 2 && hnCore.contains(sn)));
                boolean nonSightseeing = SightseeingFilter.isNonSightseeing(s.getPoiType(), s.getName());
                if (isSameHotel || nonSightseeing) {
                    String kind = isSameHotel ? "与入住酒店同名"
                            : SightseeingFilter.kindOfNonSightseeing(s.getPoiType(), s.getName());
                    log.warn("已将非景点「{}」从景点列表移除（{}）", s.getName(), kind);
                    removedByKind.add(s.getName() + "（" + kind + "）");
                    it.remove();
                }
            }
            if (!removedByKind.isEmpty()) {
                if (day.getNotes() == null) {
                    day.setNotes(new ArrayList<>());
                }
                day.getNotes().add("⚠️ 已移除被误列为景点的「" + String.join("、", removedByKind)
                        + "」（非游览场所不应作为主要景点，如需可自行前往）");
            }
            // 当天景点被删空 → 从真实景点候选池补位，避免"当日无景点"的空洞天
            if (day.getSpots().isEmpty() && spotPool != null && !spotPool.isEmpty()) {
                String replacement = popFree(spotPool, usedSpots);
                if (replacement != null) {
                    SpotItem fill = new SpotItem();
                    fill.setName(replacement);
                    fill.setSource("高德POI");
                    String fillAddr = bestPoiAddress(poiAddressMap, replacement);
                    if (fillAddr != null) {
                        fill.setAddress(fillAddr);
                    }
                    if (day.getNotes() == null) {
                        day.setNotes(new ArrayList<>());
                    }
                    day.getNotes().add("🔧 已自动补入真实景点「" + replacement
                            + "」（替换被移除的非游览场所）");
                    day.getSpots().add(fill);
                }
            }
            for (SpotItem spot : day.getSpots()) {
                String norm = normalize(spot.getName());
                if (isDuplicate(norm, usedSpots)) {
                    String replacement = popFree(spotPool, usedSpots);
                    if (replacement != null) {
                        replaceSpot(itinerary, spot, replacement, poiAddressMap, request);
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

        // 1.7 历史去重落地为"不安排"（而不是只降权）：最近行程去过的景点，若用户没点名要，
        //     就用候选池里"历史未去过"的真实景点替换；换不掉（候选不足）则保留并如实说明原因。
        //     放在餐食处理之前，保证 usedSpots/spotPool 状态与后续一致。
        excludeVisitedSpots(itinerary, collectedData, spotPool, usedSpots, poiAddressMap, request);

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
                if (meal.getSource() == null) {
                    meal.setSource(resolveSource(meal.getName(), poiMealNames, ragText));
                }
                String norm = normalize(meal.getName());
                boolean chain = meal.getName() != null
                        && CHAIN_RESTAURANTS.stream().anyMatch(meal.getName()::contains);
                // 重复、连锁、或不在 POI 餐厅池中的（且 POI 池仍有可用项）→ 用真实 POI 餐厅替换
                boolean notInPoi = "LLM建议（需核实）".equals(meal.getSource());
                if (isDuplicate(norm, usedMeals) || chain || (notInPoi && !mealPool.isEmpty())) {
                    // 连锁替换时先从候选池排除自身（POI 池可能含同名连锁店），避免"替换成自己"无效替换
                    if (chain) {
                        mealPool.removeIf(n -> n != null && normalize(n).equals(norm));
                    }
                    String replacement = popFree(mealPool, usedMeals);
                    if (replacement != null) {
                        replaceMeal(itinerary, meal, replacement);
                        usedMeals.add(normalize(replacement));
                    } else if (chain || isDuplicate(norm, usedMeals)) {
                        meal.setSource("LLM建议（需核实）");
                    }
                } else {
                    usedMeals.add(norm);
                }
            }
        }

        // 2.5 每日就餐空间合理性（Q1）：餐厅须贴近当天活动区域，跨区就餐就近替换或诚实警示。
        //     必须放在第 2 步之后（依赖 usedMeals / mealPool 的当前状态），放在交通与预算之前
        //     （替换只改名称不改价格，不影响预算口径）。
        checkMealLocation(itinerary, collectedData, mealPool, usedMeals);

        // 2.6 每日餐次完整性：午餐/晚餐缺失时用候选池里"离当天活动区域最近"的真实餐厅补位，
        //     补不到就如实标注"自行解决"。放在就餐就近校验之后（补位天然满足就近），预算之前（补的价格要进合计）。
        fillMissingMeals(itinerary, collectedData, mealPool, usedMeals);

        // 3. Plan B：恶劣天气日 → 替换为室内候选（无候选则备注警示）
        applyPlanB(itinerary, collectedData, poiSpotNames, usedSpots, spotPool, ragText,
                poiAddressMap, request);

        // 4. 交通：统一「交通段必须带金额」口径 + 未由高德路线补全的项标注为估算（LLM）；并统一时长格式
        for (DayPlan day : itinerary.getDays()) {
            if (day.getTransport() == null) {
                continue;
            }
            for (TransportItem t : day.getTransport()) {
                // Q2 金额补算：高德路线 API 只回过路费不回打车费（补全层因此置空金额），
                // LLM 的金额也常缺失 —— 缺金额时按 出行方式×距离 计价补算（无距离用时长×典型速度折算），
                // 并在来源中标注，保证末次预算合计不漏算跨区大段交通费。
                boolean costBackfilled = false;
                if (t.getEstimatedCost() == null) {
                    Double km = t.getDistanceKm();
                    if (km == null || km <= 0) {
                        km = estimateKmFromMinutes(t.getMode(), t.getEstimatedMinutes());
                    }
                    if (km != null && km > 0) {
                        t.setEstimatedCost(round2(estimateTransportCost(t.getMode(), km)));
                        costBackfilled = true;
                    }
                }
                if (t.getSource() == null) {
                    t.setSource(costBackfilled ? "估算（按距离补算）" : "估算（LLM）");
                } else if (costBackfilled && !t.getSource().contains("补算")) {
                    t.setSource(t.getSource() + "·金额按距离补算");
                }
                // LLM 有时写"11.80 km / 28 分钟"混排，统一提取"X 分钟"（有分钟则取分钟，否则保留原文）
                if (t.getDuration() != null && t.getDuration().contains("km")) {
                    java.util.regex.Matcher m =
                            java.util.regex.Pattern.compile("(\\d+\\s*分钟)").matcher(t.getDuration());
                    if (m.find()) {
                        t.setDuration(m.group(1));
                    }
                }
            }
        }

        // 4.2 交通段端点对齐：每一段的起点/终点必须是"当天真实落地的地点"（酒店/景点/餐厅，
        //     或机场车站这类合理中转枢纽）。LLM 常拿候选池里另一个同类地点（甚至别的天的餐厅）
        //     当端点，于是出现"去 A 店吃饭，路线却写 B 店"这种页面自相矛盾。
        //     放在预算计算之前：删段会影响当天交通合计。
        alignTransportEndpoints(itinerary);

        // 4.5 预算硬收敛（Q3）：超支 >20% 时先本地降住宿档次重算（确定性、零 token），
        //     压回预算后再交给 repairBudget 处理残余偏差，避免"LLM 修不动→超支放行"
        collapseHotelForBudget(itinerary, request);

        // 5. 预算合理性：偏差过大 → 回传 LLM 按预算修正一次；修正后若仍不符用户预算则诚实告知
        repairBudget(itinerary, request);
        checkBudgetMismatch(itinerary, request);

        // 6. 酒店晚数自检：行程天数 vs 有价格的酒店天数，明显少算晚数时给出警示
        checkHotelNights(itinerary);

        // 6.05 住宿档次落地：用户选高档型/豪华型、LLM 却拿经济型公寓交差时，先在候选酒店池里
        //      换成与档次相符的真实酒店（价格按所选档次估价，档次由价格反推，保证口径自洽）。
        //      放在 6.1 之前——能换就不该降级；池里确实没有，才由 6.1 按实际降级并如实告知。
        upgradeHotelTier(itinerary, request, collectedData);

        // 6.1 酒店档次与类型一致性：高档型不能住青旅（通用校验）
        validateHotelLevelMatch(itinerary);

        // 6.2 住宿口径收口：LLM 在「旅行提示」「每日备注」里写下的住宿金额/档次，会在
        //     4.5/5/6.1 改动酒店数据之后变成过期值（实测同一页面出现「780 元/晚、占 39%」
        //     与「¥200/晚、10%」两套数字）。凡是金额/档次断言一律清掉，改由系统按最终数据补一条。
        cleanStaleLodgingClaims(itinerary, request);

        // 6.25 摘要口径收口：summary 同样是 LLM 直写、前端原样渲染（Result.vue），
        //      4.5/6.1 改过住宿档次、结构对齐改过天数后，摘要里的"5 天·精选高档酒店"就成了过期口径
        //      （实测：摘要写 5 天高档，正文 3 天 ¥200/晚经济型）→ 删过期住宿断言 + 校正天数/晚数。
        cleanStaleSummaryClaims(itinerary);

        // 6.3 预算"没花出去"的诚实说明（与第 5 步的超支提示对称）。
        //      放在 6.05 之后，用最终酒店价判断，避免"刚把酒店换成高档型、却仍按旧价说预算没用满"。
        checkBudgetUnderuse(itinerary, request);

        // 7. 景点描述交叉检测：描述中出现其他地点名 → 警示（防 LLM 张冠李戴）
        checkDescriptionMismatch(itinerary, poiSpotNames, poiAddressMap);

        // 8. 住宿人数合理性：多人出行按单间价计价（未按人数配房）→ 警示
        checkHotelCapacity(itinerary, request);

        // 9. 清理 LLM 在 source_notes 中编造的预算核算（系统已单独展示「预算明细」）
        cleanFakeBudgetNotes(itinerary);

        // 10. 点名景点校验：用户明确要求的景点必须安排进行程，未安排的明确告知原因
        checkRequestedSpots(itinerary, collectedData);

        // 11. 行程级图片/地址/简介串用去重兜底（张冠李戴最后一道防线，不依赖高德 POI 池）：
        //     同一行程中多个"名称明显不同"的景点却共用同一张图/同一地址/同一段简介，说明被串用；
        //     实测三亚「三亚湾/海月广场」简介整段复用了「椰梦长廊」的，惠州「博物馆/大云寺」
        //     都复用了「惠州西湖」的图与地址。复用者图片清空、地址标待核实、简介降级兜底文案。
        dedupeCrossSpotMedia(itinerary);

        // 12. 酒店位置合理性：酒店经纬度与景点集群差距过大（跨区/跨县）→ 警示，
        //     避免"住海边却玩城区"这类行程内地理不自洽（惠州实测：酒店选在惠东巽寮湾，景点全在惠城区/博罗）
        checkHotelLocation(itinerary);
    }

    // ==================== 生成后结构对齐：天数 / 每日景点密度 / 住宿档次 / 摘要口径 ====================

    /**
     * 行程骨架对齐（确定性修复、零 token）。
     *
     * <p>实测问题：用户请求 5 天（09-18~09-22）、2 人、¥8000、高档型，模型只输出 <b>3 天 4 个景点</b>，
     * 而 {@code trip_days} 仍按日期跨度记成 5 → 页面写"5 天"、正文只有 3 天，预算也因此只用到 16%。
     * 根因是"天数/密度只是提示词里的一句要求，生成后没人核对"。
     *
     * <p>三步修复：
     * <ol>
     *   <li><b>天数与出行日期跨度对齐</b>：多了截断，少了用候选池补齐
     *       （住宿沿前一晚续住、末晚退房不计费，与既有"最后一天 hotel 价格为 0"口径一致）；</li>
     *   <li><b>每日景点数下限</b>（完整游玩日 ≥2、返程日 ≥1）：候选池有未使用的真实景点才补，不硬凑；</li>
     *   <li><b>重排 day_index / date 并把 trip_days 写成真实天数</b>——根治"页面 5 天 / 正文 3 天"打架。</li>
     * </ol>
     *
     * <p>⚠️ 必须在 {@code MapEnrichmentService} 补图片/坐标<b>之前</b>调用，否则补入的景点拿不到图片与坐标。
     * <p>日期缺失（如单测直接构造请求）时不做任何改动。
     */
    public void syncStructure(Itinerary itinerary, TripRequest request, CollectedData collectedData) {
        if (itinerary == null || request == null || itinerary.getDays() == null
                || itinerary.getDays().isEmpty()) {
            return;
        }
        int expected = expectedDays(request);
        if (expected <= 0) {
            return;
        }
        List<DayPlan> days = new ArrayList<>(itinerary.getDays());
        Set<String> used = new HashSet<>();
        for (DayPlan d : days) {
            collectSpotNames(d, used);
        }
        List<String> spotPool = collectedData == null
                ? new ArrayList<>() : collectPoiNames(collectedData, "景点");
        // 历史去过的景点不作为补位来源（第 1.7 步会把它们再换一次，这里提前排除，避免补完又被换）
        if (collectedData != null && !spotPool.isEmpty()) {
            Set<String> visited = visitedSpotNames(collectedData);
            if (visited != null && !visited.isEmpty()) {
                spotPool.removeIf(n -> n == null || matchesAny(visited, n));
            }
        }
        Map<String, String> poiAddressMap = collectedData == null
                ? Map.of() : collectPoiAddressMap(collectedData);

        // ① 天数多于请求 → 截断（保留前 N 天）
        if (days.size() > expected) {
            log.warn("结构对齐：行程 {} 天多于请求 {} 天，截断多余天数", days.size(), expected);
            days = new ArrayList<>(days.subList(0, expected));
        }

        // ② 天数少于请求 → 用候选池补齐（候选不够也把天数补齐，保证日期与请求一致）
        int modelledDays = days.size();
        for (int i = days.size(); i < expected; i++) {
            DayPlan nd = new DayPlan();
            nd.setDayIndex(i + 1);
            nd.setDate(dayDate(days, request, i));
            nd.setTheme("自由活动与深度体验");
            nd.setSpots(new ArrayList<>());
            nd.setMeals(new ArrayList<>());
            nd.setTransport(new ArrayList<>());
            nd.setNotes(new ArrayList<>());
            DayPlan prev = days.isEmpty() ? null : days.get(days.size() - 1);
            if (prev != null && prev.getHotel() != null) {
                nd.setHotel(copyHotel(prev.getHotel(), i == expected - 1));
            }
            fillDaySpots(nd, spotPool, used, poiAddressMap, MIN_SPOTS_FULL_DAY);
            List<String> names = spotNamesOf(nd);
            nd.getNotes().add(names.isEmpty()
                    ? "🔧 已按您的出行日期补齐本日（候选池中暂无更多未占用景点，可自由安排）"
                    : "🔧 已按您的出行日期补齐本日：" + String.join("、", names));
            days.add(nd);
        }
        int addedDays = days.size() - modelledDays;
        if (addedDays > 0) {
            log.warn("结构对齐：请求 {} 天、模型只输出 {} 天，已按候选池补齐 {} 天",
                    expected, modelledDays, addedDays);
            addSourceNote(itinerary, String.format(
                    "🔧 原计划只排了 %d 天，系统已按您的出行日期（%d 天）补齐为 %d 天",
                    modelledDays, expected, days.size()));
        }

        // ③ 每日景点数下限（完整游玩日 ≥2、返程日 ≥1）
        int topped = 0;
        for (int i = 0; i < days.size(); i++) {
            DayPlan d = days.get(i);
            int floor = (i == days.size() - 1) ? MIN_SPOTS_LAST_DAY : MIN_SPOTS_FULL_DAY;
            int before = d.getSpots() == null ? 0 : d.getSpots().size();
            if (before >= floor) {
                continue;
            }
            fillDaySpots(d, spotPool, used, poiAddressMap, floor);
            int after = d.getSpots() == null ? 0 : d.getSpots().size();
            if (after > before) {
                topped++;
                if (d.getNotes() == null) {
                    d.setNotes(new ArrayList<>());
                }
                d.getNotes().add("🔧 已补充真实景点：" + String.join("、", addedSpotNames(d, before))
                        + "（原安排偏少，已按候选池充实当天内容）");
            }
        }
        if (topped > 0) {
            log.info("结构对齐：{} 天因景点数少于下限，已用候选池补充", topped);
        }

        // ④ 序号 / 日期重排 + trip_days 与真实天数对齐
        for (int i = 0; i < days.size(); i++) {
            DayPlan d = days.get(i);
            d.setDayIndex(i + 1);
            if (d.getDate() == null || d.getDate().isBlank()) {
                d.setDate(dayDate(days, request, i));
            }
        }
        itinerary.setDays(days);
        itinerary.setTripDays(days.size());

        if (spotPool.isEmpty() && days.size() < expected) {
            addSourceNote(itinerary, String.format(
                    "⚠️ 候选景点不足以补满 %d 天，当前安排 %d 天，请核实出行日期或更换目的地",
                    expected, days.size()));
        }
    }

    /** 请求的出行天数（首尾日期差 + 1，钳制在 1..31）；日期缺失返回 0（不做天数对齐） */
    private int expectedDays(TripRequest request) {
        if (request == null || request.getStartDate() == null || request.getEndDate() == null) {
            return 0;
        }
        long span = java.time.temporal.ChronoUnit.DAYS
                .between(request.getStartDate(), request.getEndDate()) + 1;
        return (int) Math.min(Math.max(span, 1), 31);
    }

    /** 第 i 天（0 基）的日期：优先沿用前一天 +1，异常时用出行开始日期推算 */
    private String dayDate(List<DayPlan> days, TripRequest request, int i) {
        if (days != null && !days.isEmpty()) {
            String last = days.get(days.size() - 1).getDate();
            if (last != null && !last.isBlank()) {
                try {
                    return java.time.LocalDate.parse(last).plusDays(1).toString();
                } catch (Exception ignored) {
                    // 日期格式异常 → 回退用开始日期推算
                }
            }
        }
        return request != null && request.getStartDate() != null
                ? request.getStartDate().plusDays(i).toString() : null;
    }

    /** 复制住宿（同一家酒店续住）；lastNight=true 表示末晚退房、不计住宿费 */
    private HotelItem copyHotel(HotelItem src, boolean lastNight) {
        HotelItem h = new HotelItem();
        h.setName(src.getName());
        h.setLevel(src.getLevel());
        h.setAddress(src.getAddress());
        h.setLocation(src.getLocation());
        h.setLatitude(src.getLatitude());
        h.setLongitude(src.getLongitude());
        h.setEstimatedCost(lastNight ? 0.0 : src.getEstimatedCost());
        return h;
    }

    private void collectSpotNames(DayPlan day, Set<String> used) {
        if (day == null || day.getSpots() == null) {
            return;
        }
        for (SpotItem s : day.getSpots()) {
            if (s != null && s.getName() != null) {
                used.add(normalize(s.getName()));
            }
        }
    }

    private List<String> spotNamesOf(DayPlan day) {
        List<String> names = new ArrayList<>();
        if (day == null || day.getSpots() == null) {
            return names;
        }
        for (SpotItem s : day.getSpots()) {
            if (s != null && s.getName() != null) {
                names.add(s.getName());
            }
        }
        return names;
    }

    private List<String> addedSpotNames(DayPlan day, int fromIndex) {
        List<String> names = new ArrayList<>();
        if (day == null || day.getSpots() == null) {
            return names;
        }
        for (int i = fromIndex; i < day.getSpots().size(); i++) {
            SpotItem s = day.getSpots().get(i);
            if (s != null && s.getName() != null) {
                names.add(s.getName());
            }
        }
        return names;
    }

    /** 从候选池把某天景点补到 target 个（只用未占用项；池空即停，不凑数） */
    private void fillDaySpots(DayPlan day, List<String> spotPool, Set<String> used,
                              Map<String, String> poiAddressMap, int target) {
        if (day == null) {
            return;
        }
        List<SpotItem> spots = day.getSpots() == null
                ? new ArrayList<>() : new ArrayList<>(day.getSpots());
        day.setSpots(spots);
        while (spots.size() < target) {
            String pick = popFree(spotPool, used);
            if (pick == null) {
                return;
            }
            SpotItem s = new SpotItem();
            s.setName(pick);
            s.setSource("高德POI");
            String addr = poiAddressMap == null ? null : bestPoiAddress(poiAddressMap, pick);
            if (addr != null) {
                s.setAddress(addr);
            }
            spots.add(s);
            used.add(normalize(pick));
        }
    }

    /**
     * 住宿档次落地（生成后）：用户选的是高档型/豪华型，而 LLM 拿经济型公寓/青旅来交差时，
     * <b>先在候选酒店池里换一家与档次相符的真实酒店</b>；池里确实没有才走 6.1 的"按实际降级 + 诚实告警"。
     *
     * <p>实测：三亚 5 天 ¥8000 明确选了"高档型"，结果住「三亚湾海忆时光海景公寓」——
     * 同一页面同时写着"精选高档酒店"与"¥200/晚 经济型"。高德 POI 不含房价，
     * 因此换店后按用户所选档次的参考价估价，并用 {@link #hotelLevelForPrice} 反推档次，
     * 保证<b>价格与档次永远自洽</b>（不会再出现"高档型 ¥200/晚"这种自相矛盾）。
     */
    private void upgradeHotelTier(Itinerary itinerary, TripRequest request, CollectedData collectedData) {
        if (itinerary == null || itinerary.getDays() == null || request == null) {
            return;
        }
        String tier = request.getHotelLevel();
        if (tier == null || tier.isBlank() || "经济型".equals(tier)) {
            return;
        }
        String currentName = null;
        Set<String> usedHotels = new HashSet<>();
        for (DayPlan d : itinerary.getDays()) {
            if (d.getHotel() != null && d.getHotel().getName() != null) {
                usedHotels.add(normalize(d.getHotel().getName()));
                if (currentName == null) {
                    currentName = d.getHotel().getName();
                }
            }
        }
        if (currentName == null
                || ECONOMY_LODGING_KEYWORDS.stream().noneMatch(currentName::contains)) {
            return;
        }
        List<String> pool = collectPoiNames(collectedData, "酒店");
        if (pool.isEmpty()) {
            return;
        }
        String replacement = null;
        for (String c : pool) {
            if (c == null || c.isBlank() || samePlace(c, currentName)
                    || ECONOMY_LODGING_KEYWORDS.stream().anyMatch(c::contains)
                    || matchesAny(usedHotels, c)) {
                continue;
            }
            replacement = c;
            break;
        }
        if (replacement == null) {
            // 候选池里没有与档次相符的酒店 → 交给 6.1 如实降级并说明
            return;
        }
        double price = Math.min(perNightCap(request, itinerary.getDays().size()),
                TIER_PER_NIGHT.getOrDefault(tier, 400.0));
        String level = hotelLevelForPrice(price);
        Map<String, String> poiAddressMap = collectPoiAddressMap(collectedData);
        for (DayPlan d : itinerary.getDays()) {
            HotelItem h = d.getHotel();
            if (h == null || h.getName() == null || !samePlace(h.getName(), currentName)) {
                continue;
            }
            h.setName(replacement);
            String addr = bestPoiAddress(poiAddressMap, replacement);
            if (addr != null) {
                h.setLocation(addr);
                h.setAddress(addr);
            }
            h.setLevel(level);
            if (h.getEstimatedCost() != null && h.getEstimatedCost() > 0) {
                h.setEstimatedCost(round2(price));
            }
        }
        addSourceNote(itinerary, String.format(
                "🔧 您选择「%s」，但原方案安排的是经济型住宿「%s」，系统已改为候选池中的「%s」（约 %.0f 元/晚）",
                tier, currentName, replacement, price));
        log.warn("住宿档次落地：{}（原经济店型）-> {}（{}，约 {}/晚）", currentName, replacement, level, price);
    }

    /** 每晚住宿价上限（与生成提示词同一口径：总住宿 ≤ 总预算 45%） */
    private double perNightCap(TripRequest request, int days) {
        double budget = request.getBudget() != null && request.getBudget() > 0
                ? request.getBudget() : 5000;
        long nights = Math.max(1, days - 1);
        return budget * 0.45 / nights;
    }

    /**
     * 摘要口径收口：{@code summary} 由 LLM 直接撰写、前端原样渲染（Result.vue），
     * 而 6.1 / 4.5 会改住宿档次、{@link #syncStructure} 会改天数——摘要里的旧口径没人重算，
     * 于是出现"5 天·精选高档酒店"配"3 天·经济型 ¥200/晚"。这里做确定性清理：
     * 删掉含过期住宿断言的句子，把天数/晚数表述校正为真实值；清空后给一条系统口径的兜底摘要。
     */
    private void cleanStaleSummaryClaims(Itinerary itinerary) {
        if (itinerary == null || itinerary.getSummary() == null || itinerary.getSummary().isBlank()) {
            return;
        }
        int realDays = itinerary.getDays() == null ? 0 : itinerary.getDays().size();
        String original = itinerary.getSummary();
        StringBuilder kept = new StringBuilder();
        int removed = 0;
        for (String s : original.split("(?<=[。！？；!?;])|\\r?\\n")) {
            if (s == null || s.isBlank()) {
                continue;
            }
            if (isStaleLodgingClaim(s)) {
                removed++;
                continue;
            }
            kept.append(s.trim());
        }
        String cleaned = kept.toString().trim();
        if (realDays > 0) {
            cleaned = cleaned.replaceAll("\\d+\\s*天", realDays + "天")
                    .replaceAll("\\d+\\s*晚", Math.max(0, realDays - 1) + "晚");
        }
        if (cleaned.isBlank()) {
            cleaned = fallbackSummary(itinerary, realDays);
        }
        if (!cleaned.equals(original)) {
            log.info("摘要口径收口：清除 {} 条过期住宿断言并校正天数表述", removed);
            itinerary.setSummary(cleaned);
        }
    }

    /** 兜底摘要：全由代码拼装，不含任何金额/档次断言 */
    private String fallbackSummary(Itinerary itinerary, int realDays) {
        String dest = itinerary.getDestination() == null ? "" : itinerary.getDestination();
        List<String> themes = new ArrayList<>();
        int spots = 0;
        if (itinerary.getDays() != null) {
            for (DayPlan d : itinerary.getDays()) {
                if (d.getTheme() != null && !d.getTheme().isBlank()) {
                    themes.add(d.getTheme());
                }
                spots += d.getSpots() == null ? 0 : d.getSpots().size();
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(dest).append(realDays).append(" 天行程，共安排 ").append(spots).append(" 个景点");
        if (!themes.isEmpty()) {
            sb.append("：").append(String.join(" → ", themes));
        }
        return sb.toString();
    }

    /**
     * 预算"没花出去"的诚实说明（与 {@link #checkBudgetMismatch} 对称）。
     *
     * <p>实测用户选了"¥8000 / 5 天 / 高档型"，最终只估算 ¥1276（16%）——系统对此一言不发，
     * 用户只会以为"价格在搞笑"。这里说明原因并给出可选动作，而不是把价格硬凑上去。
     */
    private void checkBudgetUnderuse(Itinerary itinerary, TripRequest request) {
        if (itinerary == null || request == null
                || request.getBudget() == null || request.getBudget() <= 0) {
            return;
        }
        double budget = request.getBudget();
        double total = recomputeTotal(itinerary);
        if (total <= 0 || total >= budget * BUDGET_UNDERUSE_RATIO) {
            return;
        }
        addSourceNote(itinerary, String.format(
                "💡 本行程估算约 ¥%.0f，占您预算 ¥%.0f 的 %.0f%%。"
                        + "原因是候选池中符合「%s」消费水平的项目有限、且多数景点免费；"
                        + "如需用足预算，可提高住宿档次、增加付费体验或延长行程",
                total, budget, total / budget * 100,
                request.getHotelLevel() == null ? "所选档次" : request.getHotelLevel()));
    }

    /**
     * 点名景点校验：用户特殊需求中点名的景点若最终未出现在行程中，
     * 在 source_notes 中明确说明"未能安排"，让用户知道原因而不是被静默忽略。
     */
    private void checkRequestedSpots(Itinerary itinerary, CollectedData collectedData) {
        if (collectedData == null || collectedData.getRequestedSpots() == null
                || collectedData.getRequestedSpots().isEmpty()) {
            return;
        }
        Set<String> planned = new HashSet<>();
        for (DayPlan day : itinerary.getDays()) {
            if (day.getSpots() != null) {
                for (SpotItem s : day.getSpots()) {
                    if (s.getName() != null && !s.getName().isBlank()) {
                        planned.add(normalize(s.getName()));
                    }
                }
            }
        }
        List<String> missing = new ArrayList<>();
        List<String> nonSight = new ArrayList<>();
        for (String r : collectedData.getRequestedSpots()) {
            if (r == null || r.isBlank()) {
                continue;
            }
            String rn = normalize(r);
            boolean found = planned.stream().anyMatch(p -> p.contains(rn) || rn.contains(p));
            if (!found) {
                // 点名地点本身是住宿/商铺等非游览场所 → 不报"未能安排"
                // （这不是系统漏排，而是此类场所本就不应作为主要景点，另作说明）
                if (SightseeingFilter.isNonSightseeing(null, r)) {
                    nonSight.add(r);
                } else {
                    missing.add(r);
                }
            }
        }
        if (!missing.isEmpty()) {
            if (itinerary.getSourceNotes() == null) {
                itinerary.setSourceNotes(new ArrayList<>());
            }
            itinerary.getSourceNotes().add("⚠️ 你指定的景点未能全部安排：" + String.join("、", missing)
                    + "（原因可能是数据源未收录或不在本城市，请核实）");
            log.warn("点名景点未安排：{}", missing);
        }
        if (!nonSight.isEmpty()) {
            if (itinerary.getSourceNotes() == null) {
                itinerary.setSourceNotes(new ArrayList<>());
            }
            itinerary.getSourceNotes().add("ℹ️ 你提到的「" + String.join("、", nonSight)
                    + "」为住宿/商铺类非游览场所，未作为主要景点安排（如需可自行前往）");
        }
    }

    /**
     * 攻略卡片事实回写：按景点名在攻略库中查找人工整理的卡片，
     * 命中则用卡片的「位置/简介/门票」强制覆盖 LLM 生成的内容，并把来源标为「本地攻略」。
     * 这是事实锚定的核心：有攻略覆盖的景点，地址与简介不可能再张冠李戴。
     */
    private void applyGuideCardFacts(Itinerary itinerary, TripRequest request) {
        if (ragService == null || request.getDestination() == null) {
            return;
        }
        int hit = 0;
        for (DayPlan day : itinerary.getDays()) {
            if (day.getSpots() == null) {
                continue;
            }
            for (SpotItem spot : day.getSpots()) {
                if (spot.getName() == null || spot.getName().isBlank()) {
                    continue;
                }
                Map<String, String> card = ragService.findSpotCard(request.getDestination(), spot.getName());
                if (card == null) {
                    continue;
                }
                hit++;
                if (card.get("location") != null && !card.get("location").isBlank()) {
                    spot.setAddress(card.get("location"));
                }
                if (card.get("intro") != null && !card.get("intro").isBlank()) {
                    spot.setDescription(card.get("intro"));
                }
                applyCardTicket(spot, card.get("ticket"));
                spot.setSource("本地攻略");
                log.info("攻略卡片事实回写：{} → 地址[{}]", spot.getName(), spot.getAddress());
            }
        }
        if (hit > 0) {
            log.info("攻略卡片事实回写完成：命中 {} 个景点", hit);
        }
    }

    /**
     * 门票事实回写：卡片写明"免费" → 门票置 0；卡片有明确价格 → 模型未填或填得明显偏高时按卡片价校正。
     */
    private void applyCardTicket(SpotItem spot, String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return;
        }
        if (ticket.contains("免费")) {
            spot.setEstimatedCost(0.0);
            return;
        }
        Double price = extractFirstNumber(ticket);
        if (price == null) {
            return;
        }
        Double cur = spot.getEstimatedCost();
        if (cur == null || cur <= 0 || cur > price * 1.5) {
            spot.setEstimatedCost(price);
        }
    }

    /** 从文本中提取第一个数字（门票"旺季60元/淡季40元"取 60 作参考价） */
    private Double extractFirstNumber(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+(\\.\\d+)?").matcher(s);
        return m.find() ? Double.parseDouble(m.group(0)) : null;
    }

    /**
     * 从 POI 结果中构建「名称→地址」映射（覆盖所有分类：景点/餐厅/酒店等），
     * 用于用真实地址覆盖 LLM 写错的景点/酒店地址。
     */
    private Map<String, String> collectPoiAddressMap(CollectedData collectedData) {
        Map<String, String> map = new HashMap<>();
        if (collectedData.getPoiResults() == null) {
            return map;
        }
        for (Object value : collectedData.getPoiResults().values()) {
            if (!(value instanceof List<?> list)) {
                continue;  // 只处理列表类型
            }
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                Object n = m.get("name");
                Object a = m.get("address");
                if (n == null || a == null) {
                    continue;
                }
                String name = n.toString();
                String addr = a.toString();
                if (!name.isBlank() && !addr.isBlank() && !"null".equals(addr)) {
                    map.put(normalize(name), addr);
                }
            }
        }
        return map;
    }

    /**
     * 从真实 POI 中匹配最贴合的地址：优先精确匹配，其次按名称包含关系取最长（最具体）的匹配。
     * 例：「上海博物馆（东馆）」会优先命中「上海博物馆（东馆）」而非「上海博物馆」。
     */
    private String bestPoiAddress(Map<String, String> map, String name) {
        if (name == null) {
            return null;
        }
        String key = normalize(name);
        if (map.containsKey(key)) {
            return map.get(key);
        }
        String bestKey = null;
        for (String k : map.keySet()) {
            if (k.length() >= 2 && (k.contains(key) || key.contains(k))) {
                if (bestKey == null || k.length() > bestKey.length()) {
                    bestKey = k;
                }
            }
        }
        return bestKey == null ? null : map.get(bestKey);
    }

    /**
     * 地址真实性硬校验（张冠李戴治理的"聪明版"）：
     * 地址必须"有出处"——① 本景点有真实 POI 地址 → 强制覆盖（杜绝"人民大道185号"类错误）；
     * ② 本景点无真实地址，但当前地址命中「其他 POI 的地址」（地址被占用，如四方街=木府官院巷49号）
     *    → 判定张冠李戴 → 置「（地址待核实）」并在备注警示；
     * ③ 地址既无自身出处也未被占用（冷门景点）→ 无法判断，保留原样不误伤。
     */
    private void verifySpotAddresses(Itinerary itinerary, Map<String, String> poiAddressMap) {
        if (itinerary.getDays() == null || poiAddressMap == null || poiAddressMap.isEmpty()) {
            return;
        }
        // address → 占用它的 POI 名（反向映射）
        Map<String, String> ownerByAddr = new HashMap<>();
        for (Map.Entry<String, String> e : poiAddressMap.entrySet()) {
            String addr = normalize(e.getValue());
            if (!addr.isEmpty() && !ownerByAddr.containsKey(addr)) {
                ownerByAddr.put(addr, e.getKey());
            }
        }
        for (DayPlan day : itinerary.getDays()) {
            if (day.getSpots() == null) {
                continue;
            }
            for (SpotItem spot : day.getSpots()) {
                if (spot.getName() == null || spot.getName().isBlank()
                        || spot.getAddress() == null || spot.getAddress().isBlank()) {
                    continue;
                }
                String nameNorm = normalize(spot.getName());
                String ownAddr = bestPoiAddress(poiAddressMap, spot.getName());
                String curAddrNorm = normalize(spot.getAddress());

                if (ownAddr != null && !normalize(ownAddr).equals(curAddrNorm)) {
                    // ① 自身有真实地址且与当前不符（可能抄了别的景点）→ 用真实地址纠偏
                    log.warn("地址纠偏：{} 地址由「{}」修正为「{}」", spot.getName(), spot.getAddress(), ownAddr);
                    spot.setAddress(ownAddr);
                    continue;
                }
                if (ownAddr == null) {
                    // ② 无自身真实地址：检测当前地址是否被其他 POI 占用
                    String owner = ownerByAddr.get(curAddrNorm);
                    if (owner != null && !owner.equals(spot.getName())
                            && !owner.contains(nameNorm) && !nameNorm.contains(owner)) {
                        log.warn("地址张冠李戴：{} 的地址「{}」实为 {} 的地址，置为待核实",
                                spot.getName(), spot.getAddress(), owner);
                        spot.setAddress("（地址待核实）");
                        if (day.getNotes() == null) {
                            day.setNotes(new ArrayList<>());
                        }
                        day.getNotes().add("⚠️ 景点「" + spot.getName() + "」地址疑似错用「"
                                + owner + "」的地址，已置为待核实");
                    }
                }
            }
        }
    }

    /**
     * 清理 source_notes 中由 LLM 编造的预算核算行：系统已在「预算明细」中给出真实金额，
     * LLM 在 source_notes 里另写一套数字会与之一致性冲突，故移除核算类说明。
     * 覆盖：总额/核算/总预算/总和/合计/预算建模/元每间/预算明细/费用估算/应急预留/严格控制在
     * 等（实测 LLM 会换着花样写：建模/明细/控制在…元内，枚举必须持续补齐）。
     */
    private static final List<String> FAKE_BUDGET_PATTERNS =
            List.of("总额", "核算", "总预算", "总和", "合计", "预算建模", "预算模型", "价格核算",
                    "元/间", "元×", "预算明细", "费用估算", "费用预估", "价格明细", "总费用",
                    "应急预留", "严格控制在", "控制在", "均价", "人均");

    private void cleanFakeBudgetNotes(Itinerary itinerary) {
        if (itinerary.getSourceNotes() == null) {
            return;
        }
        List<String> kept = new ArrayList<>();
        for (String n : itinerary.getSourceNotes()) {
            if (n == null) {
                continue;
            }
            if (FAKE_BUDGET_PATTERNS.stream().anyMatch(n::contains)) {
                log.warn("移除 LLM 编造的预算说明：{}", n);
                continue;
            }
            kept.add(n);
        }
        itinerary.setSourceNotes(kept);
    }

    /**
     * 景点描述交叉检测：LLM 常把其他景点/酒店的内容整句写进某景点的 description
     * （如给"苍山"写"大雁塔是玄奘为保存佛经而建…"）。
     * 只删除"以其他地点名为主语、整句介绍该地点属性"的抄写句（主语位判定）——
     * "可俯瞰大理古城""比大理古城低8℃""才村→磻溪村→喜洲这一段最美"等句中，
     * 其他地点名只是参照对象、不占主语位，属正常地理语境（有 RAG 城市攻略原文常见），保留；
     * 命中真串用时删句并警示。不篡改合法内容、不扫当天备注（备注多为把多个地点串起来的行程描述）。
     * 地址级错配由 POI 地址覆盖/占用校验与行程级图址去重兜底，不在此重复扫描
     * （否则会误伤高德真实地址中合法出现的村/镇/路等地名）。
     */
    private void checkDescriptionMismatch(Itinerary itinerary, List<String> poiSpotNames,
                                          Map<String, String> poiAddressMap) {
        Set<String> knownNames = new HashSet<>();
        if (poiSpotNames != null) {
            knownNames.addAll(poiSpotNames);
        }
        // 把所有 POI 名称（景点/餐厅/酒店等）也纳入"已知地点"，扩大张冠李戴检测覆盖面
        if (poiAddressMap != null) {
            knownNames.addAll(poiAddressMap.keySet());
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
                String desc = spot.getDescription();
                if (desc == null || desc.isBlank()) {
                    continue;
                }
                // 候选：行程/POI 中其他地点名（含去掉"景区/古镇"等后缀的简称，如
                // POI"惠州西湖风景名胜区"→"惠州西湖"，描述写简称也能检出）。排除自身
                //（含互为包含/规范化同名）与"双地理区互提"（三亚湾提大东海属正常）。
                List<String> candidates = new ArrayList<>();
                for (String n : knownNames) {
                    if (n == null || n.isBlank()) {
                        continue;
                    }
                    if (self.contains(n) || n.contains(self)) {
                        continue;
                    }
                    if (isGeoName(n) && isGeoName(self)) {
                        continue;
                    }
                    if (!candidates.contains(n)) {
                        candidates.add(n);
                    }
                    String shortForm = normalize(n);
                    if (!shortForm.isEmpty() && !shortForm.equals(n)
                            && !self.contains(shortForm) && !shortForm.contains(self)
                            && !(isGeoName(shortForm) && isGeoName(self))
                            && !candidates.contains(shortForm)) {
                        candidates.add(shortForm);
                    }
                }
                if (candidates.isEmpty()) {
                    continue;
                }
                // 只删"以其他地点名为主语、整句介绍该地点"的抄写句（主语位判定）；
                // "可俯瞰大理古城""比大理古城低8℃""才村→磻溪村→喜洲这一段最美"等
                // 合法地理语境中，其他地点名不占主语位，一律保留。
                List<String> removed = new ArrayList<>();
                String cleaned = stripSubjectCopies(desc, candidates, removed);
                if (cleaned == null || cleaned.isBlank()) {
                    spot.setDescription(DESC_FALLBACK);
                } else if (!cleaned.equals(desc)) {
                    spot.setDescription(cleaned);
                }
                if (removed.isEmpty()) {
                    continue; // 未删任何抄写句（正常数据 no-op），不加警示
                }
                if (day.getNotes() == null) {
                    day.setNotes(new ArrayList<>());
                }
                day.getNotes().add("⚠️ 景点「" + self + "」的描述疑似混入其他地点内容（提及："
                        + String.join("、", removed) + "），已自动清理，请核实");
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
     * 预算不符检测：repairBudget 已尝试按用户预算压缩价格，但 LLM 受"不得改名称/结构"
     * 约束，无法把 POI 真实价格（高档型酒店 800/晚）压到接近 0。修复后若估算仍远超用户预算，
     * 必须诚实告知用户并给具体建议，否则用户会以为系统真给他安排了"30 元预算的高档型行程"。
     */
    private void checkBudgetMismatch(Itinerary itinerary, TripRequest request) {
        if (request.getBudget() == null || request.getBudget() <= 0
                || itinerary.getEstimatedBudget() == null) {
            return;
        }
        double userBudget = request.getBudget();
        double total = itinerary.getEstimatedBudget();
        if (total <= userBudget * BUDGET_HIGH_RATIO) {
            return;
        }
        double ratio = total / userBudget;
        String msg;
        if (ratio > 5) {
            // 极端不符（如 30 元预算 vs 2735 元行程）——先程序强制降级酒店为经济型，
            // 再诚实告知剩余缺口与建议（LLM 受"不得改名称"约束压不动 POI 真实价，程序来兜底）
            int downgraded = downgradeHotelsToEconomy(itinerary);
            String hotelAction = downgraded > 0
                    ? String.format("已自动将 %d 晚酒店降为「经济型」（约 200 元/晚）", downgraded)
                    : "已尝试压缩酒店价格";
            msg = String.format(
                    "⚠️ 您的预算 ¥%.0f 远低于行程估算 ¥%.0f（超 %.1f 倍）。%s，"
                            + "但即使如此仍不足以覆盖行程，建议：① 提高预算；② 缩短行程天数；③ 改为日游不过夜",
                    userBudget, total, ratio, hotelAction);
        } else {
            // 中度不符（1.5~5 倍）——温和提示
            msg = String.format(
                    "⚠️ 行程估算 ¥%.0f 已超出您的预算 ¥%.0f 约 %.0f%%。可考虑：提高预算 / 改选更经济的酒店档次 / 减少行程天数",
                    total, userBudget, (ratio - 1) * 100);
        }
        if (itinerary.getSourceNotes() == null) {
            itinerary.setSourceNotes(new ArrayList<>());
        }
        itinerary.getSourceNotes().add(msg);
    }

    /**
     * 极端预算不符时的程序兜底：把行程中价格高于经济型上限的酒店强制降为经济型
     * （约 200 元/晚），level 同步改为「经济型」。返回被降级的晚数。
     * 随后上层 calculateBudget 会按新价格重算总预算。
     */
    private int downgradeHotelsToEconomy(Itinerary itinerary) {
        int n = 0;
        if (itinerary.getDays() == null) {
            return 0;
        }
        for (DayPlan day : itinerary.getDays()) {
            if (day.getHotel() == null) {
                continue;
            }
            HotelItem h = day.getHotel();
            if (h.getEstimatedCost() != null && h.getEstimatedCost() > 250.0) {
                h.setEstimatedCost(200.0);
                if (h.getLevel() != null) {
                    h.setLevel("经济型");
                }
                n++;
                log.warn("预算严重不符：酒店「{}」强制降为经济型（200/晚）", h.getName());
            }
        }
        return n;
    }

    /**
     * 酒店档次与住宿类型一致性校验（通用，不针对任何城市）：
     * LLM 可能把「青年旅舍/民宿/招待所」标成 舒适型/高档型/豪华型（如"高档型"却选"可见时光·望达斯旅舍"）。
     * 青旅/民宿/公寓/客栈是经济定位，与高档/豪华档次矛盾 → 程序强制按实际降级为经济型（200/晚）
     * 并警示，防止用户花高档价住青旅。
     */
    private void validateHotelLevelMatch(Itinerary itinerary) {
        if (itinerary.getDays() == null) {
            return;
        }
        // 同一住宿问题（每晚 hotel 相同）只在首夜警示一次，避免 source_notes 重复刷屏
        boolean warned = false;
        for (DayPlan day : itinerary.getDays()) {
            if (day.getHotel() == null || day.getHotel().getName() == null) {
                continue;
            }
            HotelItem h = day.getHotel();
            String level = h.getLevel();
            if (level == null || level.isBlank()) {
                continue;
            }
            boolean isHighLevel = level.contains("舒适") || level.contains("高档") || level.contains("豪华");
            boolean isEconomyType = ECONOMY_LODGING_KEYWORDS.stream().anyMatch(h.getName()::contains);
            if (isHighLevel && isEconomyType) {
                log.warn("档次不符：{}（{}）实为经济型住宿，强制降为经济型", h.getName(), level);
                h.setLevel("经济型");
                if (h.getEstimatedCost() == null || h.getEstimatedCost() > 250) {
                    h.setEstimatedCost(200.0);
                }
                if (!warned) {
                    warned = true;
                    if (itinerary.getSourceNotes() == null) {
                        itinerary.setSourceNotes(new ArrayList<>());
                    }
                    itinerary.getSourceNotes().add("⚠️ 系统检测：所选住宿「" + h.getName()
                            + "」为青年旅舍/民宿类（经济定位），与您选择的「" + level + "」不匹配，已按实际调整为经济型（约 200 元/晚）。"
                            + "如需高档型酒店，建议更换住宿选项");
                }
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
                            List<String> spotPool, String ragText,
                            Map<String, String> poiAddressMap, TripRequest request) {
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
                        // Plan B 替换真实发生：把被替换的户外景点记录进 filtered_candidates（WEATHER_API/HARD），
                        // 支撑结果页「为什么没安排这些」（OPTIMIZATION_TODO P0③；TripGenerationFinalizer 收尾合并展示）
                        addWeatherFiltered(itinerary, day, nm, badWeather);
                        replaceSpot(itinerary, spot, replacement, poiAddressMap, request);
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
                // 无室内候选可换：明确指出哪些是户外景点，避免"建议室内"与户外安排自相矛盾
                List<String> outdoor = new ArrayList<>();
                for (SpotItem spot : day.getSpots()) {
                    if (spot.getName() != null
                            && INDOOR_KEYWORDS.stream().noneMatch(spot.getName()::contains)) {
                        outdoor.add(spot.getName());
                    }
                }
                String detail = outdoor.isEmpty() ? ""
                        : "（户外景点：" + String.join("、", outdoor) + "，雷雨时段请勿前往或改期）";
                if (day.getNotes() == null) {
                    day.setNotes(new ArrayList<>());
                }
                day.getNotes().add("⚠️ 当日天气「" + badWeather + "」恶劣，建议以室内活动为主" + detail);
            }
        }
    }

    /**
     * 把被恶劣天气替换掉的户外景点记录为结构化过滤原因（OPTIMIZATION_TODO P0③）：
     * 写入 itinerary.filteredCandidates（evidence=WEATHER_API, severity=HARD），
     * 供结果页「为什么没安排这些」展示；TripGenerationFinalizer 收尾时与画像类过滤原因合并去重。
     */
    private void addWeatherFiltered(Itinerary itinerary, DayPlan day, String outdoorName, String badWeather) {
        if (itinerary == null || outdoorName == null || outdoorName.isBlank()) {
            return;
        }
        FilteredCandidate fc = new FilteredCandidate();
        fc.setName(outdoorName);
        fc.setBucket("景点");
        fc.setReason(String.format("当日天气「%s」不适合户外活动，已替换为室内景点", badWeather));
        fc.setEvidence("WEATHER_API");
        fc.setSeverity("HARD");
        if (itinerary.getFilteredCandidates() == null) {
            itinerary.setFilteredCandidates(new ArrayList<>());
        }
        itinerary.getFilteredCandidates().add(fc);
        log.info("天气替换记录过滤原因：{}（第{}天，{} → 室内）", outdoorName,
                day == null || day.getDayIndex() == null ? "?" : day.getDayIndex(), badWeather);
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

    /**
     * 删除"主语抄写句"：某句话以"其他地点名"为主语、通篇描述该地点自身的属性
     * （如给苍山写"大理古城是文献名邦…"），即 LLM 把别处内容整句抄进本景点简介 → 删整句。
     * <p>与旧版"句子中出现其他地点名就删"不同：合法地理语境句
     * （"可俯瞰大理古城""比大理古城低8-10°C""骑行途经喜洲古镇"等）中
     * 其他地点名只是参照对象、不占主语位，会被保留——解决有 RAG 城市攻略原文被误删的问题。
     *
     * @return 清理后的描述；整段都被判定为抄写时返回 null（调用方降级为"暂未匹配真实资料"）
     */
    private String stripSubjectCopies(String description, List<String> mentioned, List<String> removed) {
        if (description == null) {
            return null;
        }
        String[] parts = description.split("[。！？；\n]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) {
                continue;
            }
            boolean bad = false;
            for (String m : mentioned) {
                if (isSubjectCopy(t, m)) {
                    bad = true;
                    if (!removed.contains(m)) {
                        removed.add(m);
                    }
                }
            }
            if (!bad) {
                sb.append(t).append("。");
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 判断句子是否是"以其他地点为主语的抄写句"：
     * 句子（去掉前导连接词/标点后）以该地点名开头，且其后紧跟该地点的属性描述词
     * （是/位于/有/始建于…），说明整句在介绍"那个地点"而非本景点 → 属于串用。
     * 反过来，若该名字不是句首主语（前面还有 俯瞰/远眺/比/距/骑行至 等参照成分，
     * 如"可俯瞰大理古城""比大理古城低8℃"），属于正常地理语境，予以保留。
     * 注意：候选名已含去后缀简称（惠州西湖风景名胜区→惠州西湖），句首是简称同样可命中。
     * 距离/距/车程 等虽出现在名字之后，却描述"两地的相对距离"（关系语境），不列入删词。
     */
    private boolean isSubjectCopy(String sentence, String name) {
        if (sentence == null || name == null || name.isEmpty()) {
            return false;
        }
        // 去掉前导的连接词/标点（"另外，""同时，"等），看真正的主语
        String core = sentence.replaceFirst("^[，。；、：\\s]*((另外|同时|此外|然后|接着|随后|最后|其中|比如|例如)[，,、]?)?\\s*", "");
        if (!core.startsWith(name)) {
            return false;
        }
        String after = core.substring(name.length());
        // 紧接其后的若是"自身属性描述词"，说明整句是在介绍该地点本身
        return after.startsWith("是") || after.startsWith("位于") || after.startsWith("坐落")
                || after.startsWith("始建于") || after.startsWith("建于") || after.startsWith("因")
                || after.startsWith("被") || after.startsWith("有") || after.startsWith("以")
                || after.startsWith("拥有") || after.startsWith("其") || after.startsWith("门票")
                || after.startsWith("面积") || after.startsWith("占地")
                || after.startsWith("从") || after.startsWith("在");
    }

    /**
     * 行程级图片/地址/简介串用去重兜底（张冠李戴最后一道防线，不依赖高德 POI 池）。
     * <p>同一行程中多个"名称明显不同"的景点却共用同一张图片 URL / 同一地址 / 同一段简介时，
     * 说明数据被串用（实测：惠州「博物馆」「大云寺」都复用了「惠州西湖」的图与地址；
     * 三亚「三亚湾」「海月广场」简介整段复用了「椰梦长廊」的简介——LLM 把攻略里最详尽的一段到处贴）。
     * 处理：复用者图片直接清空（宁缺毋错，不显示错图）、地址标为待核实、简介降级为兜底文案，
     * 并在 sourceNotes 汇总告知。
     * <p>名称互为包含关系（同一景点不同称呼，如"西湖"与"惠州西湖"）视为同一景点，不处理。
     * 地址维度额外排除"复用者为地理通名结尾"的景点（如 三亚湾/海月广场 共用大区域地址"天涯区…"
     * 属大景区合法共享，非错配）——只有复用者是具体地点（博物馆/寺庙等非通名）时才判地址错配；
     * 简介维度不套此护栏：区域景点的简介被整段复制同样是错误（三亚湾不能"介绍椰梦长廊"）。
     */
    private void dedupeCrossSpotMedia(Itinerary itinerary) {
        List<SpotItem> all = new ArrayList<>();
        for (DayPlan day : itinerary.getDays()) {
            if (day.getSpots() != null) {
                all.addAll(day.getSpots());
            }
        }
        if (all.size() < 2) {
            return;
        }

        List<String> warned = new ArrayList<>();

        // 1) 图片去重：同一 imageUrl 被 ≥2 个不同景点使用 → 复用者清空图片
        Map<String, SpotItem> imgOwner = new HashMap<>();
        for (SpotItem spot : all) {
            String img = spot.getImageUrl();
            if (img == null || img.isBlank()) {
                continue;
            }
            SpotItem owner = imgOwner.get(img);
            if (owner == null) {
                imgOwner.put(img, spot);
            } else if (!namesRelated(owner.getName(), spot.getName())) {
                log.warn("图片串用兜底：{}↔{} 共用同一图片，已清空后者", owner.getName(), spot.getName());
                spot.setImageUrl(null);
                warned.add("图片：「" + spot.getName() + "」与「" + owner.getName() + "」疑似串用，已移除");
            }
        }

        // 2) 地址去重：同一 address 被 ≥2 个不同景点使用 → 复用者地址标待核实。
        //    复用者是"地理通名结尾"的景点（三亚湾/海月广场/大东海…）时不判错配——
        //    大景区内多个子景点共用大区域地址（"天涯区（三亚市区西侧沿海）"）是合法的。
        Map<String, SpotItem> addrOwner = new HashMap<>();
        for (SpotItem spot : all) {
            String addr = spot.getAddress();
            if (addr == null || addr.isBlank() || addr.equals(ADDR_PENDING)) {
                continue;
            }
            SpotItem owner = addrOwner.get(addr);
            if (owner == null) {
                addrOwner.put(addr, spot);
            } else if (!namesRelated(owner.getName(), spot.getName()) && !isGeoName(spot.getName())) {
                log.warn("地址串用兜底：{}↔{} 共用地址「{}」，后者标待核实", owner.getName(), spot.getName(), addr);
                spot.setAddress(ADDR_PENDING);
                warned.add("地址：「" + spot.getName() + "」与「" + owner.getName()
                        + "」疑似共用「" + addr + "」，已标待核实");
            }
        }

        // 3) 简介去重：同一段简介被 ≥2 个不同景点完整/高相似复用 → 复用者简介降级为兜底文案。
        //    LLM 常把攻略里最详尽的一段（如椰梦长廊"20多公里椰林步道…"）整段复制给同湾其他景点，
        //    主语位判定管不到（地名不在句首、在引号内做同位语），必须按"跨景点文本复用"整体拦截。
        dedupeCrossSpotDescriptions(all, warned);

        if (!warned.isEmpty()) {
            if (itinerary.getSourceNotes() == null) {
                itinerary.setSourceNotes(new ArrayList<>());
            }
            itinerary.getSourceNotes().add("🔧 数据交叉校验：发现并修复 " + warned.size()
                    + " 处景点图片/地址/简介串用（" + String.join("；", warned) + "）");
        }
    }

    /**
     * 简介跨景点复用兜底（dedupeCrossSpotMedia 分支3）：规范化后完全相同 / 高相似的简介，
     * 判定为"后出现者复制了先出现者"，复用者简介替换为 {@link #DESC_FALLBACK} 并在 warned 记录。
     * <ul>
     *   <li>名称互为包含（同一景点不同称呼）不处理；</li>
     *   <li>完全相同要求规范化文本 ≥{@value #DESC_SAME_MIN_LEN} 字符（防通用短句误伤）；</li>
     *   <li>高相似要求 ≥{@value #DESC_SIM_MIN_LEN} 字符且归一化编辑距离相似度 ≥{@value #DESC_SIM_THRESHOLD}，
     *       地址已被标"待核实"的景点（多重异常信号）放宽到 ≥{@value #DESC_SIM_THRESHOLD_WEAK}；</li>
     *   <li>与主语位判定（checkDescriptionMismatch）互补：那里管"一段简介混入别处整句"，
     *       这里管"整段简介被复制到别的景点"，两条独立规则互不替代。</li>
     * </ul>
     */
    private void dedupeCrossSpotDescriptions(List<SpotItem> all, List<String> warned) {
        if (all.size() < 2) {
            return;
        }
        Map<String, SpotItem> exactOwners = new HashMap<>();
        List<String> seenNorms = new ArrayList<>();
        List<SpotItem> seenSpots = new ArrayList<>();
        for (SpotItem spot : all) {
            String desc = spot.getDescription();
            if (desc == null || desc.isBlank() || DESC_FALLBACK.equals(desc)) {
                continue; // 已是兜底文案的简介不参与判定，避免"两个兜底"互相误判
            }
            String norm = normalizeDescription(desc);
            if (norm.length() < DESC_SAME_MIN_LEN) {
                continue; // 太短的简介不参与复用判定（多为通用短句）
            }
            SpotItem exact = exactOwners.get(norm);
            if (exact != null) {
                if (!namesRelated(exact.getName(), spot.getName())) {
                    log.warn("简介串用兜底：{}↔{} 简介完全相同，后者降级", exact.getName(), spot.getName());
                    spot.setDescription(DESC_FALLBACK);
                    warned.add("简介：「" + spot.getName() + "」与「" + exact.getName()
                            + "」简介完全相同，已改为待核实文案");
                }
                continue; // 互含（同一景点不同称呼）不处理，也不覆盖首个基准
            }
            boolean reused = false;
            if (exact == null && norm.length() >= DESC_SIM_MIN_LEN) {
                // 高相似模糊匹配（编辑距离相似度）：地址已待核实的景点阈值放宽
                double threshold = ADDR_PENDING.equals(spot.getAddress())
                        ? DESC_SIM_THRESHOLD_WEAK : DESC_SIM_THRESHOLD;
                for (int i = 0; i < seenNorms.size(); i++) {
                    if (namesRelated(seenSpots.get(i).getName(), spot.getName())) {
                        continue;
                    }
                    if (textSimilarity(norm, seenNorms.get(i)) >= threshold) {
                        log.warn("简介串用兜底：{}↔{} 简介高度相似，后者降级",
                                seenSpots.get(i).getName(), spot.getName());
                        spot.setDescription(DESC_FALLBACK);
                        warned.add("简介：「" + spot.getName() + "」与「" + seenSpots.get(i).getName()
                                + "」简介高度相似，已改为待核实文案");
                        reused = true;
                        break;
                    }
                }
            }
            if (reused) {
                continue; // 已降级，不再作为后续基准
            }
            exactOwners.put(norm, spot);
            seenNorms.add(norm);
            seenSpots.add(spot);
        }
    }

    /** 简介规范化：去空白、全角→半角、去所有标点，仅保留中英文与数字，用于跨景点复用比较 */
    private String normalizeDescription(String desc) {
        if (desc == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(desc.length());
        for (int i = 0; i < desc.length(); i++) {
            char c = desc.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (c >= '\uFF01' && c <= '\uFF5E') {
                c = (char) (c - 0xFEE0); // 全角 → 半角
            }
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 归一化编辑距离相似度 [0,1]（1-编辑距离/较长串长度），用于简介复用模糊判定 */
    private static double textSimilarity(String a, String b) {
        int m = a.length();
        int n = b.length();
        if (m == 0 || n == 0) {
            return 0;
        }
        int[] prev = new int[n + 1];
        int[] cur = new int[n + 1];
        for (int j = 0; j <= n; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= m; i++) {
            cur[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return 1.0 - (double) prev[n] / Math.max(m, n);
    }

    /** 两景点名是否"指向同一地点"（互为包含）→ 视为同一景点，不去重 */
    private boolean namesRelated(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String na = normalize(a), nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return false;
        }
        return na.equals(nb) || na.contains(nb) || nb.contains(na);
    }

    /**
     * 酒店位置合理性校验（通用，不依赖具体城市）：酒店与各景点都有高德经纬度时，
     * 若酒店到"所有"景点的距离都超过阈值（默认 30km），说明酒店与行程景点不在同一片区
     * （如惠州实测：酒店选在惠东巽寮湾海边，景点全在惠城区/博罗县，跨县约 50km+），
     * 主动警示让用户确认，避免"住海边却玩城区"的地理不自洽。
     * 不强制改（用户可能确实想住某片区），只警示；景点/酒店经纬度不足时不误报。
     */
    private void checkHotelLocation(Itinerary itinerary) {
        if (itinerary.getDays() == null) {
            return;
        }
        // 收集有经纬度的景点（高德补全）
        List<SpotItem> located = new ArrayList<>();
        for (DayPlan day : itinerary.getDays()) {
            if (day.getSpots() != null) {
                for (SpotItem s : day.getSpots()) {
                    if (s.getLatitude() != null && s.getLongitude() != null) {
                        located.add(s);
                    }
                }
            }
        }
        // 取第一个有经纬度的酒店
        HotelItem hotel = null;
        for (DayPlan day : itinerary.getDays()) {
            if (day.getHotel() != null && day.getHotel().getLatitude() != null
                    && day.getHotel().getLongitude() != null) {
                hotel = day.getHotel();
                break;
            }
        }
        if (hotel == null || located.size() < 2) {
            return; // 数据不足，不误报
        }

        boolean allFar = true;
        double nearest = Double.MAX_VALUE;
        for (SpotItem s : located) {
            double d = haversineKm(hotel.getLatitude(), hotel.getLongitude(), s.getLatitude(), s.getLongitude());
            nearest = Math.min(nearest, d);
            if (d <= HOTEL_FAR_KM) {
                allFar = false;
                break;
            }
        }
        if (allFar && nearest > HOTEL_FAR_KM) {
            log.warn("酒店位置校验：{} 距行程主要景点约 {:.0f}km，疑似跨片区", hotel.getName(), nearest);
            if (itinerary.getSourceNotes() == null) {
                itinerary.setSourceNotes(new ArrayList<>());
            }
            itinerary.getSourceNotes().add(String.format(
                    "⚠️ 系统检测：酒店「%s」距行程主要景点约 %.0f 公里（可能不在同一片区），请确认是否需要调整位置",
                    hotel.getName(), nearest));
        }
    }

    /** 球面距离（km），用于酒店-景点地理一致性判断 */
    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        final int R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /* ================= Q1：每日就餐空间合理性（餐厅须贴近当天活动区域） ================= */

    /**
     * 餐厅到「当天景点坐标中心」的距离阈值（km）。超过视为跨区就餐 ——
     * 行程物理上做不到（如上午临潼兵马俑、中午回市区吃饭、下午再回临潼华清宫）。
     */
    private static final double MEAL_FAR_KM = 25.0;

    /**
     * 每日就餐空间合理性（Q1）：餐厅候选原先只按口味标签召回，不掌握空间关系，
     * LLM 挑店时也只看店名，于是产出"跨区吃午饭"这类物理上做不到的行程。
     *
     * <p><b>确定性修复</b>（不依赖模型自觉）：
     * <ol>
     *   <li>按天算出「当天景点坐标中心」；</li>
     *   <li>逐个检查当天餐次，超出阈值时优先用候选池里<b>离当天活动区域最近、且未被其它餐次占用</b>
     *       的真实餐厅替换；</li>
     *   <li>无可用候选则如实警示（宁可告知，也不静默给出做不到的行程）。</li>
     * </ol>
     *
     * <p>景点或餐厅缺经纬度时不判定（数据不足不误报）。
     */
    private void checkMealLocation(Itinerary itinerary, CollectedData collectedData,
                                   List<String> mealPool, Set<String> usedMeals) {
        if (itinerary.getDays() == null || collectedData == null) {
            return;
        }
        Map<String, double[]> coordMap = collectPoiCoordMap(collectedData);
        if (coordMap.isEmpty()) {
            return;
        }
        for (DayPlan day : itinerary.getDays()) {
            if (day.getSpots() == null || day.getSpots().isEmpty() || day.getMeals() == null) {
                continue;
            }
            double[] center = spotCenter(day.getSpots());
            if (center == null) {
                continue; // 当天景点无经纬度 → 无从判断
            }
            for (MealItem meal : day.getMeals()) {
                if (meal.getName() == null) {
                    continue;
                }
                double[] pos = lookupCoord(coordMap, meal.getName());
                if (pos == null) {
                    continue; // 餐厅不在 POI 池（LLM 自荐）→ 无坐标，不误报
                }
                double km = haversineKm(pos[0], pos[1], center[0], center[1]);
                if (km <= MEAL_FAR_KM) {
                    continue;
                }
                String replacement = nearestFreeMeal(mealPool, usedMeals, coordMap, center);
                if (replacement != null) {
                    String old = meal.getName();
                    usedMeals.remove(normalize(old));
                    mealPool.removeIf(n -> n != null && normalize(n).equals(normalize(replacement)));
                    replaceMeal(itinerary, meal, replacement);
                    meal.setSource("高德POI·已按当天活动区域就近调整");
                    usedMeals.add(normalize(replacement));
                    log.info("Q1 就餐就近修正：第 {} 天「{}」(距当天景点中心 {:.0f}km) → 「{}」",
                            day.getDayIndex(), old, km, replacement);
                    addSourceNote(itinerary, String.format(
                            "🔧 已把第 %s 天的「%s」调整为「%s」：原餐厅距当天活动区域约 %.0f 公里（跨区就餐不现实）",
                            day.getDayIndex(), old, replacement, km));
                } else {
                    log.warn("Q1 就餐跨区且无近邻候选：第 {} 天「{}」距当天景点中心 {:.0f}km",
                            day.getDayIndex(), meal.getName(), km);
                    addSourceNote(itinerary, String.format(
                            "⚠️ 系统检测：第 %s 天的「%s」距当天主要景点约 %.0f 公里，可能来不及就近用餐，建议自行调整",
                            day.getDayIndex(), meal.getName(), km));
                }
            }
        }
    }

    /** 当天景点坐标中心（所有景点都无经纬度 → null） */
    private double[] spotCenter(List<SpotItem> spots) {
        double latSum = 0;
        double lngSum = 0;
        int n = 0;
        for (SpotItem s : spots) {
            if (s.getLatitude() != null && s.getLongitude() != null) {
                latSum += s.getLatitude();
                lngSum += s.getLongitude();
                n++;
            }
        }
        return n == 0 ? null : new double[]{latSum / n, lngSum / n};
    }

    /** 从 POI 各分类构建「名称（规范化）→ 坐标」映射，供空间一致性判断 */
    private Map<String, double[]> collectPoiCoordMap(CollectedData collectedData) {
        Map<String, double[]> map = new HashMap<>();
        if (collectedData == null || collectedData.getPoiResults() == null) {
            return map;
        }
        for (Object value : collectedData.getPoiResults().values()) {
            if (!(value instanceof List<?> list)) {
                continue;
            }
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                Object n = m.get("name");
                Double lat = asDouble(m.get("latitude"));
                Double lng = asDouble(m.get("longitude"));
                if (n == null || lat == null || lng == null) {
                    continue;
                }
                String key = normalize(n.toString());
                if (!key.isEmpty()) {
                    map.putIfAbsent(key, new double[]{lat, lng});
                }
            }
        }
        return map;
    }

    /**
     * 按名称查坐标：先精确匹配，再按名称包含关系取最长匹配
     * （覆盖"××火锅(博乐里购物广场店)"与"××火锅"这类门店后缀差异）。
     */
    private double[] lookupCoord(Map<String, double[]> map, String name) {
        String key = normalize(name);
        if (key.isEmpty()) {
            return null;
        }
        double[] exact = map.get(key);
        if (exact != null) {
            return exact;
        }
        String bestKey = null;
        for (String k : map.keySet()) {
            if (k.length() < 2) {
                continue;
            }
            if (k.contains(key) || key.contains(k)) {
                if (bestKey == null || k.length() > bestKey.length()) {
                    bestKey = k;
                }
            }
        }
        return bestKey == null ? null : map.get(bestKey);
    }

    /** 候选池里离指定中心最近、且未被其它餐次占用、且本身不跨阈值的餐厅（无则 null） */
    private String nearestFreeMeal(List<String> mealPool, Set<String> usedMeals,
                                   Map<String, double[]> coordMap, double[] center) {
        String best = null;
        double bestKm = Double.MAX_VALUE;
        for (String candidate : mealPool) {
            if (candidate == null || candidate.isBlank() || isDuplicate(normalize(candidate), usedMeals)) {
                continue;
            }
            double[] pos = lookupCoord(coordMap, candidate);
            if (pos == null) {
                continue;
            }
            double km = haversineKm(pos[0], pos[1], center[0], center[1]);
            if (km <= MEAL_FAR_KM && km < bestKm) {
                bestKm = km;
                best = candidate;
            }
        }
        return best;
    }

    private static Double asDouble(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void addSourceNote(Itinerary itinerary, String note) {
        if (itinerary.getSourceNotes() == null) {
            itinerary.setSourceNotes(new ArrayList<>());
        }
        itinerary.getSourceNotes().add(note);
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

    /** 预算硬收敛触发线：总花费超过预算 20% 即本地降档 */
    private static final double OVERSPEND_RATIO = 1.2;
    /** 极端超支线（预算的 5 倍）：超出后走 checkBudgetMismatch 的既有强制降级路径，保持口径不变 */
    private static final double EXTREME_OVERSPEND_RATIO = 5.0;
    /** 住宿压缩下限：最多压到原住宿费的 30%（再低不现实，宁可诚实标注） */
    private static final double HOTEL_FLOOR_RATIO = 0.3;

    /**
     * 预算硬收敛（Q3）：超支 >20% 且住宿是主要可压缩项时，本地（不调 LLM）按预算
     * 反推住宿可用水位，等比压缩每晚酒店价并下调档次标注，把总预算压回用户预算内。
     * 住宿压到下限仍超支时不再硬压，改为在来源说明中诚实告知"预算不足以覆盖当前行程"。
     */
    private void collapseHotelForBudget(Itinerary itinerary, TripRequest request) {
        if (request.getBudget() == null || request.getBudget() <= 0
                || itinerary.getDays() == null || itinerary.getDays().isEmpty()) {
            return;
        }
        double budget = request.getBudget();
        double total = recomputeTotal(itinerary);
        if (total <= budget * OVERSPEND_RATIO) {
            return; // 未超支（或未超 20%），不动
        }
        if (total > budget * EXTREME_OVERSPEND_RATIO) {
            return; // 极端超支交给 checkBudgetMismatch 的既有强制降级路径（200/晚经济型 + 固定话术）
        }

        double hotelTotal = 0;
        double other = 0;
        for (DayPlan day : itinerary.getDays()) {
            if (day.getHotel() != null && day.getHotel().getEstimatedCost() != null) {
                hotelTotal += day.getHotel().getEstimatedCost();
            }
            other += dayCostWithoutHotel(day);
        }
        if (hotelTotal <= 0) {
            addBudgetNote(itinerary, String.format(
                    "⚠️ 行程估算 %.0f 元已超预算 %.0f 元（住宿无可压缩项），请考虑提高预算或减少行程安排",
                    total, budget));
            itinerary.setEstimatedBudget(total);
            return;
        }

        // 住宿可用水位 = 预算 - 非住宿支出；不足时压到住宿下限
        double availForHotel = Math.max(budget - other, hotelTotal * HOTEL_FLOOR_RATIO);
        double factor = availForHotel / hotelTotal;
        if (factor >= 0.95) {
            // 住宿占比较低，压缩住宿解决不了超支 → 诚实告知
            addBudgetNote(itinerary, String.format(
                    "⚠️ 行程估算 %.0f 元已超预算 %.0f 元（超支主要来自门票/餐饮/交通），请考虑提高预算或精简行程",
                    total, budget));
            itinerary.setEstimatedBudget(total);
            return;
        }

        double newPerNight = 0;
        for (DayPlan day : itinerary.getDays()) {
            if (day.getHotel() == null || day.getHotel().getEstimatedCost() == null) {
                continue;
            }
            double cost = day.getHotel().getEstimatedCost() * factor;
            day.getHotel().setEstimatedCost(round2(cost));
            day.getHotel().setLevel(hotelLevelForPrice(cost));
            newPerNight = cost;
        }
        double newTotal = other + hotelTotal * factor;
        itinerary.setEstimatedBudget(round2(newTotal));
        addBudgetNote(itinerary, String.format(
                "⚠️ 原行程估算 %.0f 元超预算 %.0f 元，系统已将住宿压缩至每晚约 %.0f 元（档次调整为 %s）以贴近预算；如需更高住宿标准请提高预算",
                total, budget, newPerNight,
                newPerNight > 0 && !itinerary.getDays().isEmpty()
                        && itinerary.getDays().get(0).getHotel() != null
                        ? itinerary.getDays().get(0).getHotel().getLevel() : "经济型"));
        log.info("预算硬收敛：总 {} -> {} 元，住宿按 {} 倍压缩", total, newTotal, round2(factor));
    }

    /**
     * 按每晚价反推酒店档次标注。
     *
     * <p>⚠️ 价位口径唯一真源是 {@link #TIER_PER_NIGHT}（舒适 380 / 高档 520 / 豪华 760）——
     * 这里用相邻档次的<b>中点</b>判定归属，保证「价格→档次」与「档次→价格」两向自洽，
     * 不再各自维护一套 600/400/200 的魔法数字（曾出现「舒适型」一处 380、一处 300-500、一处 200-400
     * 三套区间漂移，改一张表就崩）。
     *
     * <p>边界推导：经济上限 ~200（低于舒适 380 与高档 520 的中点前取经济）；
     * 舒适/高档中点 = (380+520)/2 = 450；高档/豪华中点 = (520+760)/2 = 640。
     */
    static String hotelLevelForPrice(double perNight) {
        // 舒适/高档中点、高档/豪华中点，由 TIER_PER_NIGHT 推导，杜绝魔法数字漂移
        double comfort = TIER_PER_NIGHT.getOrDefault("舒适型", 380.0);
        double upscale = TIER_PER_NIGHT.getOrDefault("高档型", 520.0);
        double luxury = TIER_PER_NIGHT.getOrDefault("豪华型", 760.0);
        double comfortUpscaleMid = (comfort + upscale) / 2.0;   // 450
        double upscaleLuxuryMid = (upscale + luxury) / 2.0;     // 640
        if (perNight >= upscaleLuxuryMid) {
            return "豪华型";
        }
        if (perNight >= comfortUpscaleMid) {
            return "高档型";
        }
        if (perNight >= comfort / 2.0) {  // 经济/舒适之间约 190，向上取舒适
            return "舒适型";
        }
        return "经济型";
    }

    /** 单日非住宿支出合计（门票+餐饮+交通，口径与预算合计一致） */
    private double dayCostWithoutHotel(DayPlan day) {
        double sum = 0;
        if (day.getSpots() != null) {
            for (SpotItem s : day.getSpots()) {
                if (s.getEstimatedCost() != null) {
                    sum += s.getEstimatedCost();
                }
            }
        }
        if (day.getMeals() != null) {
            for (MealItem m : day.getMeals()) {
                if (m.getEstimatedCost() != null) {
                    sum += m.getEstimatedCost();
                }
            }
        }
        if (day.getTransport() != null) {
            for (TransportItem t : day.getTransport()) {
                if (t.getEstimatedCost() != null) {
                    sum += t.getEstimatedCost();
                }
            }
        }
        return sum;
    }

    /** 重算整份行程总花费（与 ItineraryGenerator.calculateBudget 同口径，供收敛判断用） */
    private double recomputeTotal(Itinerary itinerary) {
        double total = 0;
        if (itinerary.getDays() == null) {
            return 0;
        }
        for (DayPlan day : itinerary.getDays()) {
            total += dayCostWithoutHotel(day);
            if (day.getHotel() != null && day.getHotel().getEstimatedCost() != null) {
                total += day.getHotel().getEstimatedCost();
            }
        }
        return total;
    }

    /** 追加预算相关来源说明（source_notes 懒初始化） */
    private void addBudgetNote(Itinerary itinerary, String note) {
        if (itinerary.getSourceNotes() == null) {
            itinerary.setSourceNotes(new ArrayList<>());
        }
        itinerary.getSourceNotes().add(note);
    }

    /**
     * 交通费估算（Q2，通用全国口径、不针对具体城市）：按出行方式×距离计价。
     * 出租/网约/未知按出租车计价（保守不低估预算）；步行/免费为 0。
     */    static double estimateTransportCost(String mode, double km) {
        String m = mode == null ? "" : mode;
        if (m.contains("步行") || m.contains("免费")) {
            return 0.0;
        }
        if (m.contains("骑行") || m.contains("单车")) {
            return 1.5;
        }
        if (m.contains("地铁")) {
            return Math.min(12, 3 + 0.3 * km);
        }
        if (m.contains("公交") || m.contains("巴士")) {
            return Math.min(8, 2 + 0.2 * km);
        }
        if (m.contains("高铁")) {
            return Math.max(20, 0.45 * km);
        }
        if (m.contains("动车") || m.contains("火车") || m.contains("城际")) {
            return Math.max(12, 0.30 * km);
        }
        if (m.contains("大巴") || m.contains("长途")) {
            return Math.max(10, 0.25 * km);
        }
        if (m.contains("驾车") || m.contains("自驾")) {
            return 1.0 * km; // 油费+过路费的粗略均值
        }
        // 出租/打车/网约/的士及未识别方式：起步 13 元含 3km，之后 2.3 元/km（保守不低估）
        return km <= 3 ? 13.0 : 13.0 + 2.3 * (km - 3);
    }

    /**
     * 无距离时按时长×典型速度折算公里数（Q2）。缺时长返回 null（不猜）。
     */
    static Double estimateKmFromMinutes(String mode, Integer minutes) {
        if (minutes == null || minutes <= 0) {
            return null;
        }
        String m = mode == null ? "" : mode;
        double speedKmh;
        if (m.contains("高铁")) {
            speedKmh = 250;
        } else if (m.contains("动车") || m.contains("火车") || m.contains("城际")) {
            speedKmh = 120;
        } else if (m.contains("大巴") || m.contains("长途")) {
            speedKmh = 80;
        } else if (m.contains("驾车") || m.contains("自驾")) {
            speedKmh = 60;
        } else if (m.contains("地铁")) {
            speedKmh = 35;
        } else if (m.contains("公交") || m.contains("巴士")) {
            speedKmh = 20;
        } else if (m.contains("步行")) {
            speedKmh = 5;
        } else {
            speedKmh = 30; // 出租/打车/未知：城市均速
        }
        return minutes / 60.0 * speedKmh;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /* ===================================================================================
     * 生成后一致性修复（2026-09-18，三亚案例评审）
     *
     * 共同根因只有两条，下面所有方法都是这两条的落地：
     *   ① 「数据被改了，引用它的文案/引用它的行没跟着改」——住宿金额、交通段端点都属此类；
     *   ② 「约束只在候选排序阶段生效，生成后没人校验结构是否闭合」——餐次缺失、
     *      历史去过的地方是否真的没安排，都属此类。
     * =================================================================================== */

    /** 合理中转枢纽：不是当天的景点/餐厅，但作为交通端点完全成立（到达/离开） */
    private static final List<String> TRANSIT_HUB_WORDS =
            List.of("机场", "火车站", "高铁站", "动车站", "地铁站", "汽车站", "客运站",
                    "码头", "港口", "轮渡", "口岸", "服务区");

    /** 金额断言（LLM 写了就会被后续校验改掉，属于过期值） */
    private static final java.util.regex.Pattern MONEY_CLAIM =
            java.util.regex.Pattern.compile("(\\d+\\s*(元|万)|[¥￥]\\s*\\d+)");
    /** 住宿类字样 */
    private static final java.util.regex.Pattern LODGING_WORD =
            java.util.regex.Pattern.compile("(酒店|住宿|房价|房费|公寓|民宿|客栈|旅舍|青旅|招待所)");
    /** 住宿档次字样 */
    private static final java.util.regex.Pattern LODGING_LEVEL_WORD =
            java.util.regex.Pattern.compile("(高档|豪华|舒适型|经济型|轻奢)");

    /**
     * 名称是否指向同一地点：规范化后精确相等，或两者中较短的一个（≥2 字）被另一个包含。
     * 与 {@link #isDuplicate} 同口径，避免"交通段端点判定"和"跨天去重判定"两套标准。
     */
    private boolean samePlace(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return false;
        }
        return na.equals(nb)
                || (Math.min(na.length(), nb.length()) >= 2 && (na.contains(nb) || nb.contains(na)));
    }

    /** 名称是否命中集合中的任一项（同地点判定） */
    private boolean matchesAny(Set<String> names, String name) {
        if (names == null || names.isEmpty() || name == null) {
            return false;
        }
        for (String n : names) {
            if (samePlace(n, name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 改名传播：某个景点/餐次被换成新地点后，把引用旧名称的地方一起改掉——
     * 交通段的起终点、每天的备注文本。
     *
     * <p>不这么做就会出现页面自相矛盾：实测三亚行程把连锁店「绿茶餐厅」替换成了
     * 「朋派烤肉」，餐次名称换了，交通段却仍写「城市乐园 → 绿茶餐厅」，
     * 于是这家"幽灵餐厅"在一趟行程里当了 4 次交通端点、却从来不是任何一餐。
     */
    private void propagateRename(Itinerary itinerary, String oldName, String newName) {
        if (itinerary == null || itinerary.getDays() == null
                || oldName == null || oldName.isBlank() || newName == null || newName.isBlank()
                || samePlace(oldName, newName)) {
            return;
        }
        for (DayPlan day : itinerary.getDays()) {
            if (day.getTransport() != null) {
                for (TransportItem t : day.getTransport()) {
                    if (samePlace(t.getFromPlace(), oldName)) {
                        t.setFromPlace(newName);
                    }
                    if (samePlace(t.getToPlace(), oldName)) {
                        t.setToPlace(newName);
                    }
                }
            }
            if (day.getNotes() != null && oldName.length() >= 3) {
                for (int i = 0; i < day.getNotes().size(); i++) {
                    String n = day.getNotes().get(i);
                    if (n != null && n.contains(oldName)) {
                        day.getNotes().set(i, n.replace(oldName, newName));
                    }
                }
            }
        }
    }

    /**
     * 景点换位（同槽位换地点）：同步改名引用，并用真实数据重建这个景点的属性。
     * 旧地点的简介/图片/个性化理由/业态/价格一律清空——它们描述的是被换掉的那个地方，
     * 留着就是张冠李戴；地址与简介优先用高德 POI 与攻略卡片重新取一次。
     */
    private void replaceSpot(Itinerary itinerary, SpotItem spot, String newName,
                             Map<String, String> poiAddressMap, TripRequest request) {
        if (spot == null || newName == null || newName.isBlank()) {
            return;
        }
        propagateRename(itinerary, spot.getName(), newName);
        spot.setName(newName);
        spot.setSource("高德POI");
        spot.setDescription(null);
        spot.setImageUrl(null);
        spot.setPersonalNote(null);
        spot.setPoiType(null);
        spot.setPoiId(null);
        spot.setLatitude(null);
        spot.setLongitude(null);
        spot.setEstimatedCost(null);
        spot.setAddress(poiAddressMap == null ? null : bestPoiAddress(poiAddressMap, newName));
        if (ragService != null && request != null && request.getDestination() != null) {
            Map<String, String> card = ragService.findSpotCard(request.getDestination(), newName);
            if (card != null) {
                if (card.get("location") != null && !card.get("location").isBlank()) {
                    spot.setAddress(card.get("location"));
                }
                if (card.get("intro") != null && !card.get("intro").isBlank()) {
                    spot.setDescription(card.get("intro"));
                }
                applyCardTicket(spot, card.get("ticket"));
                spot.setSource("本地攻略");
            }
        }
    }

    /**
     * 餐次换位：同步改名引用，并清掉 notes —— 那是"上一家店"的推荐菜。
     * （实测：把「绿茶餐厅」换成「朋派烤肉」后，备注仍写着"推荐绿茶烤鸡、面包诱惑"。）
     */
    private void replaceMeal(Itinerary itinerary, MealItem meal, String newName) {
        if (meal == null || newName == null || newName.isBlank()) {
            return;
        }
        propagateRename(itinerary, meal.getName(), newName);
        meal.setName(newName);
        meal.setSource("高德POI");
        meal.setNotes(null);
        meal.setPersonalNote(null);
    }

    /** 最近历史行程去过的候选地点名（来源：个性化排序落下的候选证据；无证据返回空集） */
    private Set<String> visitedSpotNames(CollectedData collectedData) {
        Set<String> names = new HashSet<>();
        if (collectedData == null || collectedData.getCandidateEvidence() == null) {
            return names;
        }
        for (CandidateEvidence ev : collectedData.getCandidateEvidence()) {
            if (ev != null && ev.getVisited() != null && ev.getVisited() == 1
                    && ev.getItemName() != null && !ev.getItemName().isBlank()) {
                names.add(ev.getItemName());
            }
        }
        return names;
    }

    /**
     * 历史去重落地：「上次去过」要变成"这次不安排"，而不是只降排序权重。
     *
     * <p>只降权会让同类情况得出相反结论——实测三亚：凤凰岛桥头公园、大东海广场因"去过"
     * 被降级排除，鹿回头风景区同样"去过"却照样排进第 4 天，而页面顶部还宣称
     * 「已减少历史行程中出现过的重复景点」。用户看到的不是"规则"，是"规则时灵时不灵"。
     *
     * <p>三条边界：① 用户点名的景点不排除（点名优先于去重）；② 候选池里先把"历史去过的"
     * 剔掉，否则"换掉一个去过的"可能换成另一个去过的；③ 实在换不到就保留并如实说明原因，
     * 不静默粉饰成"已经避开重复"。
     */
    private void excludeVisitedSpots(Itinerary itinerary, CollectedData collectedData,
                                     List<String> spotPool, Set<String> usedSpots,
                                     Map<String, String> poiAddressMap, TripRequest request) {
        if (itinerary == null || itinerary.getDays() == null) {
            return;
        }
        Set<String> visited = visitedSpotNames(collectedData);
        if (visited.isEmpty()) {
            return;
        }
        Set<String> requested = collectedData.getRequestedSpots() == null
                ? new HashSet<>() : new HashSet<>(collectedData.getRequestedSpots());
        // 候选池剔除"历史去过的"，保证换过去的确实是新地点
        if (spotPool != null) {
            spotPool.removeIf(n -> n != null && matchesAny(visited, n));
        }
        for (DayPlan day : itinerary.getDays()) {
            if (day.getSpots() == null) {
                continue;
            }
            for (SpotItem spot : day.getSpots()) {
                String nm = spot.getName();
                if (nm == null || nm.isBlank() || !matchesAny(visited, nm)) {
                    continue;
                }
                if (matchesAny(requested, nm)) {
                    addSourceNote(itinerary, String.format(
                            "↺ 「%s」是你点名的景点，虽然上次行程去过，本次仍按你的要求安排", nm));
                    continue;
                }
                String replacement = spotPool == null ? null : popFree(spotPool, usedSpots);
                if (replacement != null) {
                    replaceSpot(itinerary, spot, replacement, poiAddressMap, request);
                    usedSpots.add(normalize(replacement));
                    log.info("历史去重硬排除：第 {} 天「{}」(历史去过) → 「{}」",
                            day.getDayIndex(), nm, replacement);
                    addSourceNote(itinerary, String.format("↺ 第 %s 天已把历史去过的「%s」换成新地点「%s」",
                            day.getDayIndex(), nm, replacement));
                } else {
                    if (day.getNotes() == null) {
                        day.setNotes(new ArrayList<>());
                    }
                    day.getNotes().add("↺ 「" + nm + "」你上次行程去过；当日候选池里已无新的真实景点可替换，"
                            + "本次保留（如不想重复，可换个目的地或补充候选）");
                    log.warn("历史去过且无替代候选，保留：第 {} 天「{}」", day.getDayIndex(), nm);
                }
            }
        }
    }

    /** 解析 HH:mm / H:mm 为"从 0 点起的分钟数"，无法解析返回 null（不猜） */
    private static Integer parseClockMinutes(String clock) {
        if (clock == null) {
            return null;
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d{1,2})\\s*[:：]\\s*(\\d{2})").matcher(clock);
        if (!m.find()) {
            return null;
        }
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        return (h < 0 || h > 23 || min < 0 || min > 59) ? null : h * 60 + min;
    }

    /**
     * 当天是否已有指定餐次。判定顺序：meal_type 含"午/中"或"晚"；
     * meal_type 缺失时按开始时间落在哪个饭点判断（11-15 午餐 / 17-21 晚餐）。
     */
    private boolean hasMealType(List<MealItem> meals, boolean lunch) {
        if (meals == null) {
            return false;
        }
        for (MealItem m : meals) {
            if (m == null) {
                continue;
            }
            String t = m.getMealType() == null ? "" : m.getMealType().trim();
            if (!t.isEmpty()) {
                if (lunch && (t.contains("午") || t.contains("中"))) {
                    return true;
                }
                if (!lunch && t.contains("晚")) {
                    return true;
                }
                continue;
            }
            Integer start = parseClockMinutes(m.getStartTime());
            if (start == null) {
                continue;
            }
            int hour = start / 60;
            if (lunch && hour >= 11 && hour <= 15) {
                return true;
            }
            if (!lunch && hour >= 17 && hour <= 21) {
                return true;
            }
        }
        return false;
    }

    /** 全程餐费均值（补位餐的金额按均值给；一条餐费数据都没有则返回 0 = 不编价格） */
    private double averageMealCost(List<DayPlan> days) {
        double sum = 0;
        int n = 0;
        for (DayPlan d : days) {
            if (d.getMeals() == null) {
                continue;
            }
            for (MealItem m : d.getMeals()) {
                if (m != null && m.getEstimatedCost() != null && m.getEstimatedCost() > 0) {
                    sum += m.getEstimatedCost();
                    n++;
                }
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /**
     * 每日餐次完整性：午餐/晚餐缺失时补一个真实候选，补不到就如实标注。
     *
     * <p>实测三亚：第 2 天在亚龙湾玩 6 小时没安排午餐，第 3、4 天没安排晚餐——
     * 结构缺口全程没人管，因为"数据是否足够"只看数据源齐不齐，不看行程闭不闭合。
     *
     * <p>补位规则：优先用候选池里"离当天活动区域最近、且未被占用"的真实餐厅（与就餐就近
     * 校验同一套判定，因此补出来必然满足空间自洽）；金额按全程餐费均值给并在来源里写明是估算，
     * 不凭空编一个菜价。返程日（最后一天）不补晚餐。
     */
    private void fillMissingMeals(Itinerary itinerary, CollectedData collectedData,
                                  List<String> mealPool, Set<String> usedMeals) {
        if (itinerary == null || itinerary.getDays() == null || itinerary.getDays().isEmpty()) {
            return;
        }
        List<DayPlan> days = itinerary.getDays();
        Map<String, double[]> coordMap = collectPoiCoordMap(collectedData);
        double avgCost = averageMealCost(days);
        for (int i = 0; i < days.size(); i++) {
            DayPlan day = days.get(i);
            boolean needLunch = !hasMealType(day.getMeals(), true);
            boolean needDinner = (i != days.size() - 1) && !hasMealType(day.getMeals(), false);
            if (!needLunch && !needDinner) {
                continue;
            }
            if (day.getMeals() == null) {
                day.setMeals(new ArrayList<>());
            }
            if (needLunch) {
                fillOneMeal(itinerary, day, "午餐", "12:00", coordMap, mealPool, usedMeals, avgCost);
            }
            if (needDinner) {
                fillOneMeal(itinerary, day, "晚餐", "18:30", coordMap, mealPool, usedMeals, avgCost);
            }
        }
    }

    /** 补齐单个餐次（就近优先；无就近候选则退化为池中任意未占用项；全无则如实标注） */
    private void fillOneMeal(Itinerary itinerary, DayPlan day, String label, String startTime,
                             Map<String, double[]> coordMap, List<String> mealPool,
                             Set<String> usedMeals, double avgCost) {
        double[] center = day.getSpots() == null ? null : spotCenter(day.getSpots());
        String pick = center == null ? null : nearestFreeMeal(mealPool, usedMeals, coordMap, center);
        if (pick == null) {
            pick = popFree(mealPool, usedMeals);
        }
        if (pick == null) {
            if (day.getNotes() == null) {
                day.setNotes(new ArrayList<>());
            }
            day.getNotes().add("⚠️ 系统检测：当日未安排" + label + "，且候选池中已无未占用的真实餐厅可用，"
                    + "请按当天活动区域自行选择就餐地点");
            return;
        }
        MealItem meal = new MealItem();
        meal.setName(pick);
        meal.setMealType(label);
        meal.setStartTime(startTime);
        meal.setSource(avgCost > 0 ? "高德POI·金额按全程餐费均值估算" : "高德POI");
        if (avgCost > 0) {
            meal.setEstimatedCost(round2(avgCost));
        }
        day.getMeals().add(meal);
        usedMeals.add(normalize(pick));
        log.info("餐次补位：第 {} 天补入{}「{}」", day.getDayIndex(), label, pick);
        addSourceNote(itinerary, String.format("🔧 已补入第 %s 天的%s候选「%s」（原生成漏排该餐次）",
                day.getDayIndex(), label, pick));
    }

    /** 当天真实落地的地点集合（酒店 / 景点 / 餐厅）——交通段端点的合法取值 */
    private List<String> dayAnchors(DayPlan day) {
        List<String> anchors = new ArrayList<>();
        if (day.getHotel() != null && day.getHotel().getName() != null
                && !day.getHotel().getName().isBlank()) {
            anchors.add(day.getHotel().getName());
        }
        if (day.getSpots() != null) {
            for (SpotItem s : day.getSpots()) {
                if (s.getName() != null && !s.getName().isBlank()) {
                    anchors.add(s.getName());
                }
            }
        }
        if (day.getMeals() != null) {
            for (MealItem m : day.getMeals()) {
                if (m.getName() != null && !m.getName().isBlank()) {
                    anchors.add(m.getName());
                }
            }
        }
        return anchors;
    }

    /**
     * 交通段端点对齐：每一段的起点/终点必须是"当天真实落地的地点"（酒店/景点/餐厅），
     * 或机场、车站这类合理中转枢纽；未标注端点的松散衔接段不判定。
     *
     * <p>处理顺序：先在当天锚点里按名称相似度找替身（够像才认，避免乱点鸳鸯），
     * 找不到就删掉这一段并如实说明——宁可少一条衔接，也不展示一条不存在的路线。
     */
    private void alignTransportEndpoints(Itinerary itinerary) {
        if (itinerary == null || itinerary.getDays() == null) {
            return;
        }
        for (DayPlan day : itinerary.getDays()) {
            List<TransportItem> legs = day.getTransport();
            if (legs == null || legs.isEmpty()) {
                continue;
            }
            List<String> anchors = dayAnchors(day);
            List<TransportItem> kept = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            for (TransportItem t : legs) {
                boolean fromOk = resolveEndpoint(t, true, anchors);
                boolean toOk = resolveEndpoint(t, false, anchors);
                if (fromOk && toOk) {
                    kept.add(t);
                } else {
                    dropped.add(describeLeg(t));
                    log.warn("交通段端点与当天行程不符，已移除：第 {} 天 {}", day.getDayIndex(), describeLeg(t));
                }
            }
            if (!dropped.isEmpty()) {
                day.setTransport(kept.isEmpty() ? null : kept);
                if (day.getNotes() == null) {
                    day.setNotes(new ArrayList<>());
                }
                day.getNotes().add("🔧 已移除 " + dropped.size()
                        + " 条衔接信息与当天行程不符的交通段（原标注：" + String.join("；", dropped)
                        + "），避免展示不存在的路线");
            }
        }
    }

    /**
     * 尝试让一个端点落在当天锚点里：本来就是锚点/中转枢纽 → true；
     * 不是但能找到足够相似的当天地点 → 改写成它并返回 true；否则 false。
     */
    private boolean resolveEndpoint(TransportItem t, boolean from, List<String> anchors) {
        String place = from ? t.getFromPlace() : t.getToPlace();
        if (place == null || place.isBlank()) {
            return true; // 松散衔接段（未标注端点）不判定
        }
        if (TRANSIT_HUB_WORDS.stream().anyMatch(place::contains)) {
            return true;
        }
        if (matchesAny(new HashSet<>(anchors), place)) {
            return true;
        }
        String sub = bestAnchorSubstitute(place, anchors);
        if (sub == null) {
            return false;
        }
        if (from) {
            t.setFromPlace(sub);
        } else {
            t.setToPlace(sub);
        }
        log.info("交通段端点改写：{} → {}", place, sub);
        return true;
    }

    /** 在当天锚点里找与 place 最像的一个（字符重合度 ≥ 0.34 才认，否则不硬凑） */
    private String bestAnchorSubstitute(String place, List<String> anchors) {
        if (place == null || place.isBlank() || anchors == null || anchors.isEmpty()) {
            return null;
        }
        String best = null;
        double bestRatio = 0;
        for (String a : anchors) {
            double r = charOverlapRatio(place, a);
            if (r > bestRatio) {
                bestRatio = r;
                best = a;
            }
        }
        return bestRatio >= 0.34 ? best : null;
    }

    /**
     * 两个名称的字符重合度（按较短名的字符在较长名中的命中比例）。
     * 只用来判断"这个名字像不像当天某个地点"，不用于去重（去重见 isDuplicate）。
     */
    private double charOverlapRatio(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return 0;
        }
        String shorter = na.length() <= nb.length() ? na : nb;
        String longer = shorter.equals(na) ? nb : na;
        int hit = 0;
        for (int i = 0; i < shorter.length(); i++) {
            if (longer.indexOf(shorter.charAt(i)) >= 0) {
                hit++;
            }
        }
        return (double) hit / shorter.length();
    }

    /** 交通段的可读描述（日志/备注用） */
    private String describeLeg(TransportItem t) {
        String from = t.getFromPlace() == null || t.getFromPlace().isBlank() ? "？" : t.getFromPlace();
        String to = t.getToPlace() == null || t.getToPlace().isBlank() ? "？" : t.getToPlace();
        return "「" + from + " → " + to + "」";
    }

    /** 是不是"住宿金额/档次"类断言（LLM 写的，数据一改就过期） */
    private boolean isStaleLodgingClaim(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (!LODGING_WORD.matcher(text).find()) {
            return false;
        }
        return MONEY_CLAIM.matcher(text).find() || LODGING_LEVEL_WORD.matcher(text).find();
    }

    /**
     * 住宿口径收口：清掉 LLM 在「旅行提示」「每日备注」里写的住宿金额/档次断言，改由系统按
     * 最终数据重述一条。
     *
     * <p>实测三亚同一页面出现两套数字：旅行提示写「高档型…单晚 780 元，4 晚共 3120 元，占预算 39%」，
     * 预算明细却是「¥200/晚、¥800、10%」——因为 4.5/5/6.1 改过酒店数据后，LLM 那段文案没人重算。
     * 处理方式是"让 LLM 别写这类事实"（与 v1.1 起「来源说明由代码拼装」同一思路），
     * 而不是回头再问一次模型（又慢又可能再编）。
     */
    private void cleanStaleLodgingClaims(Itinerary itinerary, TripRequest request) {
        if (itinerary == null) {
            return;
        }
        int removed = 0;
        if (itinerary.getTips() != null && !itinerary.getTips().isEmpty()) {
            List<String> kept = new ArrayList<>();
            for (String tip : itinerary.getTips()) {
                if (isStaleLodgingClaim(tip)) {
                    log.warn("移除 LLM 写的过期住宿断言（旅行提示）：{}", tip);
                    removed++;
                    continue;
                }
                kept.add(tip);
            }
            itinerary.setTips(kept.isEmpty() ? null : kept);
        }
        if (itinerary.getDays() != null) {
            for (DayPlan day : itinerary.getDays()) {
                if (day.getNotes() == null || day.getNotes().isEmpty()) {
                    continue;
                }
                List<String> kept = new ArrayList<>();
                for (String n : day.getNotes()) {
                    if (isStaleLodgingClaim(n)) {
                        log.warn("移除 LLM 写的过期住宿断言（第 {} 天备注）：{}", day.getDayIndex(), n);
                        removed++;
                        continue;
                    }
                    kept.add(n);
                }
                day.setNotes(kept.isEmpty() ? null : kept);
            }
        }
        appendSystemLodgingTip(itinerary, request);
        if (removed > 0) {
            log.info("住宿口径收口：清除 {} 条 LLM 过期住宿断言，已按最终数据重述", removed);
        }
    }

    /** 按最终数据补一条住宿事实（名称/档次/单价/晚数/合计/占预算），与「预算明细」同口径 */
    private void appendSystemLodgingTip(Itinerary itinerary, TripRequest request) {
        if (itinerary.getDays() == null || itinerary.getDays().isEmpty()) {
            return;
        }
        HotelItem hotel = null;
        int nights = 0;
        for (DayPlan day : itinerary.getDays()) {
            if (day.getHotel() == null || day.getHotel().getName() == null) {
                continue;
            }
            if (hotel == null) {
                hotel = day.getHotel();
            }
            if (day.getHotel().getEstimatedCost() != null && day.getHotel().getEstimatedCost() > 0) {
                nights++;
            }
        }
        if (hotel == null) {
            return;
        }
        // 幂等：先移除上一次由系统生成的住宿事实行（数据可能已变），避免同一条被重复追加
        if (itinerary.getTips() != null) {
            itinerary.getTips().removeIf(t -> t != null && t.startsWith("住宿：")
                    && (t.contains("元/晚") || t.contains("未计住宿费用")));
            if (itinerary.getTips().isEmpty()) {
                itinerary.setTips(null);
            }
        }
        StringBuilder sb = new StringBuilder("住宿：").append(hotel.getName());
        if (hotel.getLevel() != null && !hotel.getLevel().isBlank()) {
            sb.append("（").append(hotel.getLevel()).append("）");
        }
        double perNight = hotel.getEstimatedCost() == null ? 0 : hotel.getEstimatedCost();
        if (perNight > 0 && nights > 0) {
            double total = perNight * nights;
            sb.append(String.format("约 %.0f 元/晚 × %d 晚 ≈ %.0f 元", perNight, nights, total));
            Double budget = request == null ? null : request.getBudget();
            if (budget != null && budget > 0) {
                sb.append(String.format("（占预算 %.0f%%）", total / budget * 100));
            }
        } else if (nights == 0) {
            sb.append("（未计住宿费用，请核实）");
        }
        if (itinerary.getTips() == null) {
            itinerary.setTips(new ArrayList<>());
        }
        itinerary.getTips().add(sb.toString());
    }
}
