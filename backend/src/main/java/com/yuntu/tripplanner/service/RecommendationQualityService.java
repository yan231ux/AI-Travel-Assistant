package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.model.Spot;
import com.yuntu.tripplanner.model.UserBehavior;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 城市推荐质量（设计方案 §6「城市推荐质量」/admin/recommendations）。
 *
 * <p><b>回答的问题</b>：推荐"有没有货、货好不好、用户买不买账"按城市拆开看。
 * 单一全局读数会被大城市掩盖小城市的空货架问题（某城市只有 2 个 POI_ONLY 景点
 * 却每天被推荐），这个接口把每个城市拆开。
 *
 * <p><b>三个数据源，各自负责一段</b>：
 * <ul>
 *   <li>{@code spot} 主档 → 可推荐景点数 / 有攻略数 / 无攻略数 / 平均质量分 / 最近同步时间。
 *       可见性判定与 {@code SpotVisibility.isActive} 严格同口径（SQL 镜像，含 OUTDATED 豁免、
 *       merged_into 别名剔除），否则会和真实推荐流数量对不上；</li>
 *   <li>{@code rag_index_task} × {@code city_guide} → 最近 RAG 更新时间（只认 READY 的完成时间，
 *       不用攻略编辑时间，避免"改了没索引"被当成已更新）；</li>
 *   <li>{@code spot_feed_log} × {@code user_behavior} → 曝光量 / 覆盖用户数 / 收藏率 / 负反馈率。
 *       反馈口径与 {@link FeedMonitorService} 完全一致：只统计"先曝光过、后产生行为"的
 *       (用户, 景点) 对，同用户同条目同行为只算一次。</li>
 * </ul>
 *
 * <p><b>降级口径</b>（延续看板 P0-4）：任一数据源读失败都显式进 {@code errors} 并置
 * {@code degraded=true}，绝不把"查询故障"显示成"这个城市质量差 / 没有数据"。
 * 景点主档读不到时（城市列表的来源）直接返回空列表 + degraded，不猜城市。
 */
@Slf4j
@Service
public class RecommendationQualityService {

    /** 城市"平均质量分"的可信度权重：VERIFIED 有攻略且人工核验 / GUIDE_MATCHED 攻略收录 / POI_ONLY 仅高德 */
    static final int SCORE_VERIFIED = 100;
    static final int SCORE_GUIDE_MATCHED = 70;
    static final int SCORE_POI_ONLY = 40;

    private static final int MAX_WINDOW_DAYS = 180;
    private static final int MAX_LIMIT = 200;
    private static final int FEED_LOG_LIMIT = 10000;
    private static final int BEHAVIOR_LIMIT = 20000;

    /**
     * 时间输出格式统一走 formatter：LocalDateTime.toString() 在秒为 0 时会省略秒
     * （"2026-09-11T20:15"），按固定长度截取会直接越界，故不依赖 toString 的长度。
     */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 可见性 SQL 镜像（= SpotVisibility.isActive）。
     * 改这里必须同步改 SpotVisibility，否则"可推荐景点数"与真实推荐池会漂移。
     */
    private static final String ACTIVE_PREDICATE =
            "(status IS NULL OR status = '" + Spot.STATUS_ONLINE + "')"
                    + " AND (merged_into IS NULL OR merged_into = '')"
                    + " AND (flag IS NULL OR flag = '' OR flag = '" + Spot.FLAG_OUTDATED + "')";

    private final JdbcTemplate jdbcTemplate;

    public RecommendationQualityService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 城市级推荐质量报告。
     *
     * @param days  反馈统计窗口天数（1~180）；景点存量指标不受窗口影响（是当前存量）
     * @param limit 返回城市数上限（1~200），按曝光量降序取
     */
    public Map<String, Object> cityQuality(int days, int limit) {
        int window = clamp(days <= 0 ? 30 : days, 1, MAX_WINDOW_DAYS);
        int size = clamp(limit <= 0 ? 50 : limit, 1, MAX_LIMIT);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(window);
        List<String> errors = new ArrayList<>();

        // 1) 景点存量（城市列表的唯一来源：没有景点的城市谈不上推荐质量）
        List<Map<String, Object>> spotRows = spotStats(errors);
        if (spotRows.isEmpty()) {
            return report(new ArrayList<>(), window, errors);
        }

        Map<String, Map<String, Object>> byCity = new LinkedHashMap<>();
        for (Map<String, Object> r : spotRows) {
            String city = asString(r.get("city"));
            if (city == null || city.isBlank()) {
                continue;
            }
            int recommendable = asInt(r.get("recommendable"));
            int guideBacked = asInt(r.get("guide_backed"));
            Map<String, Object> m = base(city);
            m.put("recommendable_spots", recommendable);
            m.put("guide_backed_spots", guideBacked);
            // 无攻略 = 存量 - 有攻略（不单独查一次 POI_ONLY，保证两个数永远自洽）
            m.put("poi_only_spots", Math.max(0, recommendable - guideBacked));
            m.put("avg_quality_score", round1(asDouble(r.get("avg_quality"))));
            m.put("last_synced_at", ts(r.get("last_synced")));
            byCity.put(city, m);
        }

        // 2) 最近 RAG 更新时间（攻略侧，读失败只影响该列）
        Map<String, String> ragAt = ragUpdatedAt(errors);
        // 3) 曝光 + 反馈（行为侧）
        applyFeedback(cutoff, byCity, errors);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> m : byCity.values()) {
            String city = (String) m.get("city");
            m.put("last_rag_updated_at", ragAt.get(city));
            items.add(m);
        }
        // 排序：曝光多的城市在前（说明它真的在被推荐），曝光相同再看景点存量
        items.sort((a, b) -> {
            long ea = asLong(a.get("exposures"));
            long eb = asLong(b.get("exposures"));
            if (ea != eb) {
                return Long.compare(eb, ea);
            }
            return Integer.compare(asInt(b.get("recommendable_spots")),
                    asInt(a.get("recommendable_spots")));
        });
        if (items.size() > size) {
            items = new ArrayList<>(items.subList(0, size));
        }
        return report(items, window, errors);
    }

    /* ================= 数据源 1：景点存量 ================= */

    private List<Map<String, Object>> spotStats(List<String> errors) {
        String sql = "SELECT city, COUNT(*) AS recommendable, "
                + "SUM(CASE WHEN data_quality IN ('" + Spot.QUALITY_GUIDE_MATCHED + "','"
                + Spot.QUALITY_VERIFIED + "') THEN 1 ELSE 0 END) AS guide_backed, "
                + "AVG(CASE data_quality WHEN '" + Spot.QUALITY_VERIFIED + "' THEN " + SCORE_VERIFIED
                + " WHEN '" + Spot.QUALITY_GUIDE_MATCHED + "' THEN " + SCORE_GUIDE_MATCHED
                + " ELSE " + SCORE_POI_ONLY + " END) AS avg_quality, "
                + "MAX(last_synced_at) AS last_synced "
                + "FROM spot WHERE " + ACTIVE_PREDICATE + " GROUP BY city";
        try {
            return jdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            log.warn("城市景点存量查询失败: {}", e.getMessage());
            errors.add("spot: " + e.getMessage());
            return List.of();
        }
    }

    /* ================= 数据源 2：RAG 索引时间 ================= */

    private Map<String, String> ragUpdatedAt(List<String> errors) {
        String sql = "SELECT g.city AS city, MAX(t.finished_at) AS last_rag "
                + "FROM rag_index_task t JOIN city_guide g ON g.id = t.guide_id "
                + "WHERE t.status = 'READY' GROUP BY g.city";
        Map<String, String> out = new HashMap<>();
        try {
            for (Map<String, Object> r : jdbcTemplate.queryForList(sql)) {
                String city = asString(r.get("city"));
                if (city != null && !city.isBlank()) {
                    out.put(city, ts(r.get("last_rag")));
                }
            }
        } catch (Exception e) {
            log.warn("城市 RAG 更新时间查询失败: {}", e.getMessage());
            errors.add("rag_index_task: " + e.getMessage());
        }
        return out;
    }

    /* ================= 数据源 3：曝光 × 行为 ================= */

    /**
     * 按城市算曝光与反馈率。
     *
     * <p>做法与 {@link FeedMonitorService} 同构：先把窗口内的曝光行按城市建
     * {@code 用户|景点} 键集合，再把行为落到键所属城市上——**只统计曝光之后的行为**，
     * 否则"用户自己搜到并收藏"会被误记成推荐效果。
     */
    private void applyFeedback(LocalDateTime cutoff, Map<String, Map<String, Object>> byCity,
                               List<String> errors) {
        List<Map<String, Object>> feedRows = feedLogs(cutoff, errors);
        // 反向索引：曝光键 → 城市。同一用户同一景点只可能属于一个城市；若历史脏数据
        // 让同键出现在多个城市，取先出现者（first-wins），保证一条曝光只被算进一个城市。
        Map<String, String> keyCity = new HashMap<>();
        Map<String, Set<String>> cityUsers = new HashMap<>();

        for (Map<String, Object> r : feedRows) {
            String city = asString(r.get("city"));
            if (city == null || city.isBlank() || !byCity.containsKey(city)) {
                continue; // 未在景点存量中的城市（无景点可推）不计入
            }
            Map<String, Object> m = byCity.get(city);
            m.put("exposures", asLong(m.get("exposures")) + 1);
            String userId = asString(r.get("user_id"));
            if (userId != null) {
                cityUsers.computeIfAbsent(city, k -> new HashSet<>()).add(userId);
                String spotId = asString(r.get("spot_id"));
                String poiId = asString(r.get("poi_id"));
                if (spotId != null) {
                    keyCity.putIfAbsent(userId + "|" + spotId, city);
                }
                if (poiId != null) {
                    keyCity.putIfAbsent(userId + "|" + poiId, city);
                }
            }
        }

        if (!feedRows.isEmpty()) {
            for (Map.Entry<String, Set<String>> e : cityUsers.entrySet()) {
                byCity.get(e.getKey()).put("users", e.getValue().size());
            }
        }

        List<Map<String, Object>> behaviors = behaviors(cutoff, errors);
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> b : behaviors) {
            String userId = asString(b.get("user_id"));
            String itemId = asString(b.get("item_id"));
            String action = asString(b.get("action_type"));
            if (userId == null || itemId == null || action == null) {
                continue;
            }
            String key = userId + "|" + itemId;
            String city = keyCity.get(key);
            if (city == null) {
                continue; // 未曝光过 → 不进推荐效果口径
            }
            if (!seen.add(key + "|" + action)) {
                continue; // 同用户同条目同行为只算一次
            }
            Map<String, Object> m = byCity.get(city);
            if (UserBehavior.ACTION_SAVE.equals(action)) {
                m.put("save_count", asLong(m.get("save_count")) + 1);
            } else {
                m.put("dislike_count", asLong(m.get("dislike_count")) + 1);
            }
        }

        // 率：分母是曝光行数（与 FeedMonitorService 口径一致），曝光为 0 → 0%
        for (Map<String, Object> m : byCity.values()) {
            long exposures = asLong(m.get("exposures"));
            m.put("save_rate", rate(asLong(m.get("save_count")), exposures));
            m.put("dislike_rate", rate(asLong(m.get("dislike_count")), exposures));
        }
    }

    private List<Map<String, Object>> feedLogs(LocalDateTime cutoff, List<String> errors) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT city, user_id, spot_id, poi_id FROM spot_feed_log "
                            + "WHERE created_at >= ? LIMIT " + FEED_LOG_LIMIT, cutoff);
        } catch (Exception e) {
            log.warn("曝光日志查询失败（城市反馈率降级为 0）: {}", e.getMessage());
            errors.add("spot_feed_log: " + e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> behaviors(LocalDateTime cutoff, List<String> errors) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT user_id, item_id, action_type FROM user_behavior "
                            + "WHERE created_at >= ? AND item_type = ? AND action_type IN (?, ?) "
                            + "LIMIT " + BEHAVIOR_LIMIT,
                    cutoff, UserBehavior.ITEM_TYPE_SPOT,
                    UserBehavior.ACTION_SAVE, UserBehavior.ACTION_DISLIKE);
        } catch (Exception e) {
            log.warn("用户行为查询失败（城市反馈率降级为 0）: {}", e.getMessage());
            errors.add("user_behavior: " + e.getMessage());
            return List.of();
        }
    }

    /* ================= 载具 ================= */

    private Map<String, Object> base(String city) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("city", city);
        m.put("recommendable_spots", 0);
        m.put("guide_backed_spots", 0);
        m.put("poi_only_spots", 0);
        m.put("avg_quality_score", 0.0);
        m.put("exposures", 0L);
        m.put("users", 0);
        m.put("save_count", 0L);
        m.put("dislike_count", 0L);
        m.put("save_rate", 0.0);
        m.put("dislike_rate", 0.0);
        m.put("last_synced_at", null);
        m.put("last_rag_updated_at", null);
        return m;
    }

    private Map<String, Object> report(List<Map<String, Object>> items, int window, List<String> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("window_days", window);
        body.put("generated_at", LocalDateTime.now().format(TS));
        body.put("degraded", !errors.isEmpty());
        body.put("errors", errors);
        body.put("notes", List.of(
                "可推荐景点数 = 在线且未被治理标记的景点（与推荐池同口径，OUTDATED 不算下线）",
                "有攻略景点 = 可信度 GUIDE_MATCHED / VERIFIED；无攻略 = 可推荐数 − 有攻略数",
                "平均质量分口径 = 可信度加权（VERIFIED 100 / GUIDE_MATCHED 70 / POI_ONLY 40），"
                        + "衡量数据可信度，不是攻略内容分",
                "收藏率/负反馈率 = 窗口内「先曝光、后行为」的去重计数 ÷ 该城市曝光行数"
                        + "（与推荐流监控同口径；曝光为 0 时率显示 0，不代表质量差）",
                "最近 RAG 更新时间 = 只取索引任务 READY 的完成时间，攻略改了但没索引成功不会显示为已更新",
                "推荐失败率未采集：当前只在曝光时写日志，空结果/降级请求不落库，"
                        + "需要给推荐入口加请求级埋点后才能给出真实读数（不估算）"));
        return body;
    }

    /* ================= 小工具 ================= */

    private static double rate(long numerator, long exposures) {
        return exposures <= 0 ? 0.0 : Math.round(numerator * 1000.0 / exposures) / 10.0;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, max));
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static int asInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** 时间列统一成 "yyyy-MM-dd HH:mm:ss" 字符串；无值为 null（前端据此显示"暂无"） */
    private static String ts(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime().format(TS);
        }
        if (o instanceof LocalDateTime t) {
            return t.format(TS);
        }
        return o.toString();
    }
}
