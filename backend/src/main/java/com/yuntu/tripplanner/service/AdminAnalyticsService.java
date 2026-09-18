package com.yuntu.tripplanner.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 管理端数据看板聚合服务（设计方案 §5.3 六张图，阶段四可视化）。
 *
 * <p>统计口径与降级语义沿袭 AdminDashboardService（P0 反模式修复）：
 * 任一部分查询失败 → 该图数据显式置 null + 记入 errors，响应带 degraded 标记，
 * <b>绝不把"查询失败"伪装成"业务上的 0/空"</b>；前端据此渲染错误态/重试。
 *
 * <p>数据源：travel_event（阶段二事件表）按时间窗口聚合 —— 有索引、有窗口，
 * 不做全表扫描；审核漏斗/风险构成读 content_moderation_task；RAG 状态读
 * city_guide/rag_index_task 状态计数。
 */
@Slf4j
@Service
public class AdminAnalyticsService {

    private final JdbcTemplate jdbcTemplate;

    public AdminAnalyticsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 图表一：景点规划采用排行（横向柱状图：采用次数 + 去重用户数） */
    public Map<String, Object> spotAdoption(int days, String city) {
        List<String> errors = new ArrayList<>();
        LocalDate since = LocalDate.now().minusDays(days - 1L);
        StringBuilder sql = new StringBuilder(
                "SELECT e.item_id, MAX(e.item_name) AS item_name, MAX(e.city) AS city, "
                        + "SUM(e.event_type = 'SPOT_GENERATED') AS adopt_count, "
                        + "COUNT(DISTINCT CASE WHEN e.event_type = 'SPOT_GENERATED' THEN e.user_id END) AS adopt_users "
                        + "FROM travel_event e WHERE e.item_type = 'SPOT' AND e.stat_date >= ? ");
        List<Object> args = new ArrayList<>(List.of(since));
        if (city != null && !city.isBlank()) {
            sql.append("AND e.city = ? ");
            args.add(city);
        }
        sql.append("GROUP BY e.item_id HAVING adopt_count > 0 ORDER BY adopt_count DESC LIMIT 10");
        List<Map<String, Object>> rows = count("spotAdoption", errors,
                () -> jdbcTemplate.queryForList(sql.toString(), args.toArray()));
        return result(rows, errors);
    }

    /** 图表二：城市热度排行（行程生成/保存/景点采用/活跃用户） */
    public Map<String, Object> cityHeat(int days) {
        List<String> errors = new ArrayList<>();
        LocalDate since = LocalDate.now().minusDays(days - 1L);
        List<Map<String, Object>> rows = count("cityHeat", errors,
                () -> jdbcTemplate.queryForList(
                        "SELECT e.city, "
                                + "SUM(e.event_type = 'TRIP_GENERATED') AS trip_generated, "
                                + "SUM(e.event_type = 'TRIP_SAVED') AS trip_saved, "
                                + "SUM(e.event_type = 'SPOT_GENERATED') AS spot_adopt, "
                                + "COUNT(DISTINCT e.user_id) AS active_users "
                                + "FROM travel_event e WHERE e.stat_date >= ? AND e.city IS NOT NULL AND e.city <> '' "
                                + "GROUP BY e.city ORDER BY trip_generated DESC LIMIT 15", since));
        return result(rows, errors);
    }

    /** 图表三：内容审核漏斗（任务量 → 自动放行/待复核/失败 → 人工通过/拒绝） */
    public Map<String, Object> moderationFunnel(int days) {
        List<String> errors = new ArrayList<>();
        java.time.LocalDateTime since = LocalDate.now().minusDays(days - 1L).atStartOfDay();
        List<Map<String, Object>> rows = count("moderationFunnel", errors,
                () -> jdbcTemplate.queryForList(
                        "SELECT COUNT(*) AS total, "
                                + "SUM(status = 'PASSED') AS auto_passed, "
                                + "SUM(status = 'REVIEW') AS review, "
                                + "SUM(status = 'FAILED') AS ai_failed, "
                                + "SUM(decision = 'APPROVE') AS human_approved, "
                                + "SUM(decision = 'REJECT') AS human_rejected, "
                                + "SUM(rule_hit_count > 0) AS rule_hits "
                                + "FROM content_moderation_task WHERE created_at >= ?", since));
        return result(rows == null || rows.isEmpty() ? null : rows.get(0), errors);
    }

    /** 图表四：内容风险构成（环形图，按风险等级） */
    public Map<String, Object> riskBreakdown(int days) {
        List<String> errors = new ArrayList<>();
        java.time.LocalDateTime since = LocalDate.now().minusDays(days - 1L).atStartOfDay();
        List<Map<String, Object>> rows = count("riskBreakdown", errors,
                () -> jdbcTemplate.queryForList(
                        "SELECT risk_level, COUNT(*) AS cnt FROM content_moderation_task "
                                + "WHERE risk_level IS NOT NULL AND created_at >= ? "
                                + "GROUP BY risk_level", since));
        return result(rows, errors);
    }

    /** 图表五：推荐/规划趋势（折线图：按天 行程生成/保存/景点采用/收藏） */
    public Map<String, Object> trend(int days) {
        List<String> errors = new ArrayList<>();
        LocalDate since = LocalDate.now().minusDays(days - 1L);
        List<Map<String, Object>> rows = count("trend", errors,
                () -> jdbcTemplate.queryForList(
                        "SELECT stat_date, "
                                + "SUM(event_type = 'TRIP_GENERATED') AS trips, "
                                + "SUM(event_type = 'TRIP_SAVED') AS saves, "
                                + "SUM(event_type = 'SPOT_GENERATED') AS adopts, "
                                + "SUM(event_type = 'SPOT_FAVORITED') AS favorites "
                                + "FROM travel_event WHERE stat_date >= ? "
                                + "GROUP BY stat_date ORDER BY stat_date", since));
        return result(rows, errors);
    }

    /** 图表六：RAG 索引状态（攻略 rag_status 计数 + 索引任务状态计数） */
    public Map<String, Object> ragStatus() {
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> guides = count("ragGuideStatus", errors,
                () -> jdbcTemplate.queryForList(
                        "SELECT rag_status, COUNT(*) AS cnt FROM city_guide GROUP BY rag_status"));
        List<Map<String, Object>> tasks = count("ragTaskStatus", errors,
                () -> jdbcTemplate.queryForList(
                        "SELECT status, COUNT(*) AS cnt FROM rag_index_task GROUP BY status"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("guides", guides);
        data.put("tasks", tasks);
        return result(data, errors);
    }

    /** 查询包装：失败记入 errors 并返回 null（绝不静默变 0/空集合） */
    private <T> T count(String key, List<String> errors, Supplier<T> query) {
        try {
            return query.get();
        } catch (Exception e) {
            log.warn("看板聚合查询失败（{}）: {}", key, e.getMessage());
            errors.add(key + ": " + e.getMessage());
            return null;
        }
    }

    /** 统一响应壳：data + degraded + errors（degraded = 任一部分失败） */
    private Map<String, Object> result(Object data, List<String> errors) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("data", data);
        m.put("degraded", !errors.isEmpty());
        m.put("errors", errors);
        return m;
    }
}
