package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.model.DayPlan;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.MealItem;
import com.yuntu.tripplanner.model.TripRecord;
import com.yuntu.tripplanner.model.TripRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户长期记忆画像服务（方向二：个性化）。
 *
 * <p>基于用户已保存的历史行程（trip_record）做确定性统计，产出用于注入生成提示词的
 * 画像文本——让模型生成"记得你"的行程（延续偏好、避免重复推荐）。
 * 统计规则全部确定性、零 LLM 调用、失败静默降级（无历史/解析失败 → 返回 null，不注入）。
 */
@Slf4j
@Service
public class UserProfileService {

    /** 画像最多读取最近多少条行程 */
    private static final int RECENT_LIMIT = 5;
    /** 最近行程摘要最多展示条数 */
    private static final int SUMMARY_SHOW = 3;
    /** 画像文本最大长度（避免撑爆提示词上下文） */
    private static final int MAX_TEXT_LENGTH = 500;

    /** 节奏关键词 → 规范节奏名 */
    private static final Map<String, String> PACE_KEYWORDS = Map.ofEntries(
            Map.entry("紧凑", "紧凑"), Map.entry("充实", "紧凑"),
            Map.entry("轻松", "轻松"), Map.entry("休闲", "轻松"), Map.entry("慢", "轻松"),
            Map.entry("适中", "适中"), Map.entry("标准", "适中"));

    /** 餐饮口味关键词（命中餐厅名/描述即计数） */
    private static final List<String> DIET_KEYWORDS = List.of(
            "火锅", "海鲜", "本帮菜", "川菜", "粤菜", "小吃", "烧烤", "面食", "甜品",
            "咖啡", "辣", "清淡", "烤肉", "日料", "泰餐");

    private final TripRecordService tripRecordService;

    public UserProfileService(TripRecordService tripRecordService) {
        this.tripRecordService = tripRecordService;
    }

    /**
     * 构建用户记忆画像文本；该用户暂无历史行程时返回 null（调用方不注入）。
     */
    public String buildMemoryText(String userId, TripRequest request) {
        List<TripRecord> recent = tripRecordService.getRecentTrips(userId, RECENT_LIMIT);
        if (recent.isEmpty()) {
            return null;
        }
        List<String> cities = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        Map<String, Integer> paceCount = new HashMap<>();
        Map<String, Integer> hotelCount = new HashMap<>();
        Map<String, Integer> dietCount = new HashMap<>();
        double budgetSum = 0;
        int budgetN = 0;

        for (TripRecord r : recent) {
            if (r.getDestination() != null && !r.getDestination().isBlank()
                    && !cities.contains(r.getDestination())) {
                cities.add(r.getDestination());
            }
            Itinerary it = r.getItinerary();
            if (it == null) {
                continue;
            }
            if (it.getEstimatedBudget() != null && it.getEstimatedBudget() > 0) {
                budgetSum += it.getEstimatedBudget();
                budgetN++;
            }
            // 节奏：从 summary + 每日主题文本匹配关键词
            String text = (it.getSummary() == null ? "" : it.getSummary()) + " "
                    + (it.getDays() == null ? "" : it.getDays().stream()
                            .map(d -> d.getTheme() == null ? "" : d.getTheme())
                            .reduce("", (a, b) -> a + " " + b));
            for (Map.Entry<String, String> e : PACE_KEYWORDS.entrySet()) {
                if (text.contains(e.getKey())) {
                    paceCount.merge(e.getValue(), 1, Integer::sum);
                }
            }
            // 住宿档次
            if (it.getDays() != null) {
                for (DayPlan d : it.getDays()) {
                    if (d.getHotel() != null && d.getHotel().getLevel() != null
                            && !d.getHotel().getLevel().isBlank()) {
                        hotelCount.merge(d.getHotel().getLevel(), 1, Integer::sum);
                    }
                }
            }
            // 餐饮线索
            if (it.getDays() != null) {
                for (DayPlan d : it.getDays()) {
                    if (d.getMeals() == null) {
                        continue;
                    }
                    for (MealItem m : d.getMeals()) {
                        if (m.getName() == null) {
                            continue;
                        }
                        for (String kw : DIET_KEYWORDS) {
                            if (m.getName().contains(kw)) {
                                dietCount.merge(kw, 1, Integer::sum);
                                break;
                            }
                        }
                    }
                }
            }
            // 最近摘要（截断）
            if (summaries.size() < SUMMARY_SHOW) {
                String s = it.getSummary();
                if (s != null && !s.isBlank()) {
                    summaries.add((r.getDestination() == null ? "" : r.getDestination())
                            + "：" + (s.length() > 60 ? s.substring(0, 60) + "…" : s));
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("该用户的历史旅行（只供参考，未列出的经历不得编造）：\n");
        if (!cities.isEmpty()) {
            sb.append("- 曾去过：").append(String.join("、", cities)).append("\n");
        }
        if (!summaries.isEmpty()) {
            sb.append("- 最近行程：\n");
            for (String s : summaries) {
                sb.append("  · ").append(s).append("\n");
            }
        }
        String topPace = topKey(paceCount);
        if (topPace != null) {
            sb.append("- 节奏偏好：").append(topPace).append("\n");
        }
        String topHotel = topKey(hotelCount);
        if (topHotel != null) {
            sb.append("- 住宿偏好：").append(topHotel).append("\n");
        }
        List<String> topDiets = topKeys(dietCount, 3);
        if (!topDiets.isEmpty()) {
            sb.append("- 餐饮偏好：").append(String.join("、", topDiets)).append("\n");
        }
        if (budgetN > 0) {
            sb.append(String.format("- 历史平均预算：约 %.0f 元/次%n", budgetSum / budgetN));
        }

        String text = sb.toString().trim();
        return text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH) + "…" : text;
    }

    /** 计数最高的 key（无则 null） */
    private String topKey(Map<String, Integer> map) {
        return map.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** 按计数降序取前 n 个 key */
    private List<String> topKeys(Map<String, Integer> map, int n) {
        return map.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(n)
                .map(Map.Entry::getKey)
                .toList();
    }
}
