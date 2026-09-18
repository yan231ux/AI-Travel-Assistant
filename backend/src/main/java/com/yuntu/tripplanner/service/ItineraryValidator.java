package com.yuntu.tripplanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.agent.CollectedData;
import com.yuntu.tripplanner.client.LlmClient;
import com.yuntu.tripplanner.common.SightseeingFilter;
import com.yuntu.tripplanner.common.SpotText;
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
                        meal.setName(replacement);
                        meal.setSource("高德POI");
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

        // 3. Plan B：恶劣天气日 → 替换为室内候选（无候选则备注警示）
        applyPlanB(itinerary, collectedData, poiSpotNames, usedSpots, spotPool, ragText);

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

        // 4.5 预算硬收敛（Q3）：超支 >20% 时先本地降住宿档次重算（确定性、零 token），
        //     压回预算后再交给 repairBudget 处理残余偏差，避免"LLM 修不动→超支放行"
        collapseHotelForBudget(itinerary, request);

        // 5. 预算合理性：偏差过大 → 回传 LLM 按预算修正一次；修正后若仍不符用户预算则诚实告知
        repairBudget(itinerary, request);
        checkBudgetMismatch(itinerary, request);

        // 6. 酒店晚数自检：行程天数 vs 有价格的酒店天数，明显少算晚数时给出警示
        checkHotelNights(itinerary);

        // 6.1 酒店档次与类型一致性：高档型不能住青旅（通用校验）
        validateHotelLevelMatch(itinerary);

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
                        // Plan B 替换真实发生：把被替换的户外景点记录进 filtered_candidates（WEATHER_API/HARD），
                        // 支撑结果页「为什么没安排这些」（OPTIMIZATION_TODO P0③；TripGenerationFinalizer 收尾合并展示）
                        addWeatherFiltered(itinerary, day, nm, badWeather);
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
                    meal.setName(replacement);
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

    /** 按每晚价反推酒店档次标注（与生成提示词中的价位口径一致：经济 150-250 / 舒适 300-500 / 豪华 600+） */
    static String hotelLevelForPrice(double perNight) {
        if (perNight >= 600) {
            return "豪华型";
        }
        if (perNight >= 400) {
            return "高档型";
        }
        if (perNight >= 200) {
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
}
