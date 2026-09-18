package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuntu.tripplanner.common.SpotVisibility;
import com.yuntu.tripplanner.model.RecommendationIntervention;
import com.yuntu.tripplanner.model.Spot;
import com.yuntu.tripplanner.repository.SpotRepository;
import com.yuntu.tripplanner.service.SpotTrendingScoreCalculator.DailyMetrics;
import com.yuntu.tripplanner.service.SpotTrendingScoreCalculator.ItemSeries;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 首页「大家最近在规划」热门景点服务（设计方案 §6.2 / §6.4）。
 *
 * <p><b>定位与边界</b>：这是<b>社会热度</b>，不是"适合你"。文档 §6.1 明确要求
 * 首页必须区分「热门规划 / 个性化推荐 / 城市精选 / 攻略收录」，不能把全站热门
 * 伪装成个性化推荐；因此本服务不引入任何画像字段，个性化匹配由调用方单独叠加。
 *
 * <p><b>数据来源</b>：只读 {@code spot_trending_daily}（日级预聚合），不在请求路径上
 * 扫描全量事件表——这是 §7.3「首页读取预聚合结果」的落地。
 *
 * <p><b>安全规则（§6.5）</b>：仅收录可见（ONLINE 且非治理标记）景点、剔除人工黑名单、
 * 必须有有效名称与城市、小样本（规划用户 &lt; {@value #MIN_SAMPLE_USERS}）时不展示具体人数。
 */
@Slf4j
@Service
public class SpotTrendingService {

    /** 小样本保护阈值（§6.5）：低于该规划用户数时不展示具体人数，避免小样本泄露个体行为 */
    static final int MIN_SAMPLE_USERS = 3;

    /** 历史事件的合理窗口上限（防一次拉全量） */
    private static final int MAX_WINDOW_DAYS = 30;
    private static final int MAX_LIMIT = 20;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final SpotRepository spotRepository;
    private final RecommendationInterventionService interventionService;

    public SpotTrendingService(JdbcTemplate jdbcTemplate,
                               SpotRepository spotRepository,
                               RecommendationInterventionService interventionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.spotRepository = spotRepository;
        this.interventionService = interventionService;
    }

    /**
     * 查询窗口内热门景点。
     *
     * @param days  统计窗口天数（1~30）
     * @param city  可选城市过滤；为空表示跨城市（此时按城市内归一化后比较，避免大城市霸榜）
     * @param limit 返回条数（1~20）
     */
    public Map<String, Object> trendingSpots(int days, String city, int limit) {
        int window = clamp(days, 1, MAX_WINDOW_DAYS);
        int size = clamp(limit, 1, MAX_LIMIT);
        LocalDate today = LocalDate.now();
        LocalDate since = today.minusDays(window - 1L);

        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(
                    "SELECT stat_date, item_id, city, generated_count, planning_user_count, "
                            + "saved_count, favorited_count, click_count, dislike_count "
                            + "FROM spot_trending_daily "
                            + "WHERE stat_date >= ? AND stat_date <= ? "
                            + (city == null || city.isBlank() ? "" : "AND city = ? ")
                            + "ORDER BY item_id, stat_date",
                    city == null || city.isBlank()
                            ? new Object[]{since, today}
                            : new Object[]{since, today, city});
        } catch (Exception e) {
            // 沿袭看板口径：查询失败显式降级，绝不伪装成"近期没有热门"
            log.warn("热门景点查询失败: {}", e.getMessage());
            errors.add("trendingSpots: " + e.getMessage());
            return response(List.of(), window, errors);
        }

        if (rows.isEmpty()) {
            return response(List.of(), window, errors);
        }

        // 1) 明细行 → 按景点分组的日级序列
        Map<String, List<DailyMetrics>> byItem = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String itemId = asString(r.get("item_id"));
            byItem.computeIfAbsent(itemId, k -> new ArrayList<>()).add(new DailyMetrics(
                    asDate(r.get("stat_date")),
                    asString(r.get("city")),
                    asInt(r.get("generated_count")),
                    asInt(r.get("planning_user_count")),
                    asInt(r.get("saved_count")),
                    asInt(r.get("favorited_count")),
                    asInt(r.get("click_count")),
                    asInt(r.get("dislike_count"))));
        }

        // 2) item_id → 景点主档（item_id 口径与事件一致，可能是 spot_id 也可能是 poi_id）
        Map<String, Spot> spotByItem = loadSpotMapping(byItem.keySet());
        Set<String> blacklist = blacklistSpotIds();

        // 3) 安全过滤（§6.5）
        List<ItemSeries> candidates = new ArrayList<>();
        Map<String, Spot> keptSpot = new HashMap<>();
        for (Map.Entry<String, List<DailyMetrics>> e : byItem.entrySet()) {
            Spot s = spotByItem.get(e.getKey());
            if (s == null) {
                continue;                                   // 映射不到主档 → 不上首页
            }
            if (!SpotVisibility.isActive(s)) {
                continue;                                   // 非 ONLINE 或带治理标记
            }
            if (blacklist.contains(s.getSpotId())) {
                continue;                                   // 人工推荐黑名单
            }
            if (isBlank(s.getName()) || isBlank(s.getCity())) {
                continue;                                   // 缺有效名称/城市
            }
            candidates.add(new ItemSeries(e.getKey(), e.getValue()));
            keptSpot.put(e.getKey(), s);
        }

        // 4) 打分（城市内归一化 + 按天衰减）
        Map<String, Double> scores = SpotTrendingScoreCalculator.scoreAll(candidates, today);

        List<String> ordered = candidates.stream()
                .map(ItemSeries::itemId)
                .sorted(Comparator.comparingDouble(
                        (String id) -> Math.max(0.0, scores.getOrDefault(id, 0.0))).reversed())
                .limit(size)
                .toList();

        List<Map<String, Object>> items = new ArrayList<>();
        for (String itemId : ordered) {
            items.add(toItem(itemId, byItem.get(itemId), keptSpot.get(itemId), today, window,
                    Math.max(0.0, scores.getOrDefault(itemId, 0.0))));
        }
        return response(items, window, errors);
    }

    /* ---------------- 组装 ---------------- */

    private Map<String, Object> toItem(String itemId, List<DailyMetrics> days, Spot spot,
                                       LocalDate today, int window, double score) {
        int planningUsers = days.stream().mapToInt(DailyMetrics::planningUsers).max().orElse(0);
        int planningCount = days.stream().mapToInt(DailyMetrics::generatedCount).sum();
        int savedCount = days.stream().mapToInt(DailyMetrics::savedCount).sum();
        int favoritedCount = days.stream().mapToInt(DailyMetrics::favoritedCount).sum();

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("spot_id", spot.getSpotId());
        item.put("item_id", itemId);
        item.put("name", spot.getName());
        item.put("city", spot.getCity());
        item.put("image_url", spot.getImageUrl());
        item.put("data_quality", spot.getDataQuality());
        // 小样本保护：不足阈值时不暴露具体人数，前端据此显示"近期有人规划"
        boolean sampleHidden = planningUsers < MIN_SAMPLE_USERS;
        item.put("planning_users", sampleHidden ? null : planningUsers);
        item.put("planning_count", planningCount);
        item.put("saved_trip_count", savedCount);
        item.put("favorite_count", favoritedCount);
        item.put("sample_hidden", sampleHidden);
        item.put("trend", trendOf(days, today, window));
        // 热度分：跨城市比较时它已按各自城市内归一化，故不同城市之间可比
        item.put("hot_score", Math.round(score * 10000) / 10000.0);
        return item;
    }

    /**
     * 趋势方向：近半窗口 vs 前半窗口的规划采用次数。
     * 用次数而非人数，避免"人少但反复规划"造成的抖动。
     */
    private String trendOf(List<DailyMetrics> days, LocalDate today, int window) {
        LocalDate split = today.minusDays(Math.max(1, window / 2) - 1L);
        int recent = 0;
        int earlier = 0;
        for (DailyMetrics d : days) {
            if (d.statDate().isBefore(split)) {
                earlier += d.generatedCount();
            } else {
                recent += d.generatedCount();
            }
        }
        if (recent > earlier) {
            return "UP";
        }
        return recent < earlier ? "DOWN" : "FLAT";
    }

    private Map<String, Object> response(List<Map<String, Object>> items, int window, List<String> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("window_days", window);
        body.put("generated_at", LocalDateTime.now().format(TS));
        body.put("degraded", !errors.isEmpty());
        body.put("errors", errors);
        return body;
    }

    /* ---------------- 依赖查询 ---------------- */

    /** item_id（可能是 spot_id 或 poi_id）→ 景点主档 */
    private Map<String, Spot> loadSpotMapping(Set<String> itemIds) {
        List<Spot> spots = spotRepository.selectList(new LambdaQueryWrapper<Spot>()
                .and(w -> w.in(Spot::getSpotId, itemIds).or().in(Spot::getPoiId, itemIds)));
        Map<String, Spot> mapping = new HashMap<>();
        for (Spot s : spots) {
            if (s.getSpotId() != null) {
                mapping.putIfAbsent(s.getSpotId(), s);
            }
            if (s.getPoiId() != null && !s.getPoiId().isBlank()) {
                mapping.putIfAbsent(s.getPoiId(), s);
            }
        }
        return mapping;
    }

    /** 当前生效的人工推荐黑名单（§6.3 干预的只读消费，运营与算法分层） */
    private Set<String> blacklistSpotIds() {
        Set<String> ids = new HashSet<>();
        try {
            for (RecommendationIntervention act : interventionService.activeNow()) {
                if (RecommendationIntervention.ACTION_BLACKLIST.equals(act.getAction())
                        && RecommendationIntervention.TARGET_SPOT.equals(act.getTargetType())) {
                    ids.add(act.getTargetId());
                }
            }
        } catch (Exception e) {
            // 黑名单读不到时宁可少拦，不能因此让整个首页热门挂掉
            log.warn("读取推荐黑名单失败（本次热门不做黑名单过滤）: {}", e.getMessage());
        }
        return ids;
    }

    /* ---------------- 小工具 ---------------- */

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, max));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static int asInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static LocalDate asDate(Object o) {
        if (o instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (o instanceof LocalDate d) {
            return d;
        }
        return LocalDate.parse(o.toString());
    }
}
