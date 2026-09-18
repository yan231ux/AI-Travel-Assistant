package com.yuntu.tripplanner.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据看板聚合单测（设计方案 §5.3 六张图）。
 *
 * <p>核心口径与 {@link AdminDashboardServiceTest} 一致（P0 反模式修复）：
 * 任一部分查询失败 → 该图 data 显式置 null + 记入 errors + degraded=true，
 * <b>绝不把"查询失败"伪装成业务上的 0 或空集合</b>，否则管理员会把数据库故障
 * 误读成"系统本来就没数据"。
 *
 * <p>另覆盖：spot-adopt 的口径是 SPOT_GENERATED（收藏不计入）、city 过滤参数、
 * funnel 空结果不返回半成品对象、ragStatus 部分失败不影响另一部分。
 */
@ExtendWith(MockitoExtension.class)
class AdminAnalyticsServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AdminAnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new AdminAnalyticsService(jdbcTemplate);
    }

    /* ---------------- 通用壳：data / degraded / errors 三件套 ---------------- */

    @Test
    void everyResponseCarriesDataDegradedAndErrorsKeys() {
        stubQueryForList(List.of());

        Map<String, Object> resp = service.spotAdoption(7, null);

        assertTrue(resp.containsKey("data"), "响应必须带 data");
        assertTrue(resp.containsKey("degraded"), "响应必须带 degraded");
        assertTrue(resp.containsKey("errors"), "响应必须带 errors");
        assertEquals(false, resp.get("degraded"));
        assertTrue(((List<?>) resp.get("errors")).isEmpty());
    }

    /* ---------------- 图表一：景点采用排行 ---------------- */

    @Test
    void spotAdoption_healthy_returnsRowsWithoutDegradation() {
        Map<String, Object> row = Map.of("item_id", "spot_上海_A", "item_name", "外滩",
                "city", "上海", "adopt_count", 3L, "adopt_users", 2L);
        stubQueryForList(List.of(row));

        Map<String, Object> resp = service.spotAdoption(7, null);

        assertEquals(false, resp.get("degraded"), "查询成功时 degraded 必须为 false");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
        assertEquals(1, data.size());
        assertEquals("外滩", data.get(0).get("item_name"));
    }

    /** 口径锁定：采用只认 SPOT_GENERATED，收藏(SPOT_FAVORITED)不能混入采用数。 */
    @Test
    void spotAdoption_sqlCountsOnlySpotGeneratedNotFavorite() {
        stubQueryForList(List.of());

        service.spotAdoption(7, null);

        String sql = captureFirstSqlWithArgs();
        assertTrue(sql.contains("SPOT_GENERATED"),
                "采用数必须按 SPOT_GENERATED 统计，实际 SQL: " + sql);
        assertFalse(sql.contains("SPOT_FAVORITED"),
                "收藏不应出现在采用排行里（trend 才分开统计 favorites）");
        assertTrue(sql.contains("HAVING adopt_count > 0"),
                "零采用的行不应进入榜单");
    }

    /** city 过滤必须作为 SQL 参数下推，而不是拼进 SQL 文本（防注入 + 命中索引）。 */
    @Test
    void spotAdoption_withCity_pushesCityAsQueryParameter() {
        stubQueryForList(List.of());

        service.spotAdoption(7, "上海");

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(anyString(), captor.capture());
        Object[] args = captor.getValue();
        assertEquals(2, args.length, "应带 时间窗口 + 城市 两个参数");
        assertEquals("上海", args[1], "城市必须以参数形式下推");
    }

    /** 核心：查询失败 → data 为 null 且 errors 点名，绝不是空数组。 */
    @Test
    void spotAdoption_queryFailure_isExplicitlyUnavailableInsteadOfEmpty() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new RuntimeException("db down"));

        Map<String, Object> resp = service.spotAdoption(7, null);

        assertEquals(true, resp.get("degraded"), "查询失败时 degraded 必须为 true");
        assertNull(resp.get("data"), "失败项必须是 null（不可用），绝不能是空数组");
        List<?> errors = (List<?>) resp.get("errors");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).toString().startsWith("spotAdoption"),
                "errors 必须点名失败的图表，实际: " + errors.get(0));
    }

    /* ---------------- 图表二：城市热度 ---------------- */

    @Test
    void cityHeat_healthy_returnsCityRows() {
        Map<String, Object> row = Map.of("city", "上海", "trip_generated", 1L,
                "trip_saved", 0L, "spot_adopt", 6L, "active_users", 1L);
        stubQueryForList(List.of(row));

        Map<String, Object> resp = service.cityHeat(30);

        assertEquals(false, resp.get("degraded"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
        assertEquals("上海", data.get(0).get("city"));
    }

    @Test
    void cityHeat_queryFailure_isExplicitlyUnavailable() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new RuntimeException("db down"));

        Map<String, Object> resp = service.cityHeat(30);

        assertEquals(true, resp.get("degraded"));
        assertNull(resp.get("data"));
    }

    /* ---------------- 图表三：审核漏斗 ---------------- */

    @Test
    void moderationFunnel_healthy_returnsAggregateRow() {
        Map<String, Object> row = Map.of("total", 1L, "auto_passed", 1L, "review", 0L,
                "ai_failed", 0L, "human_approved", 1L, "human_rejected", 0L, "rule_hits", 0L);
        stubQueryForList(List.of(row));

        Map<String, Object> resp = service.moderationFunnel(30);

        assertEquals(false, resp.get("degraded"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertNotNull(data);
        assertEquals(1L, data.get("total"));
    }

    /** COUNT 查询永不返回空集合；真出现空行时应显式不可用，而非返回半成品对象。 */
    @Test
    void moderationFunnel_emptyResult_isUnavailableRatherThanHalfBuiltObject() {
        stubQueryForList(List.of());

        Map<String, Object> resp = service.moderationFunnel(30);

        assertNull(resp.get("data"),
                "聚合行缺失时必须是 null，不能返回一个全字段为空的假对象");
    }

    @Test
    void moderationFunnel_queryFailure_isExplicitlyUnavailable() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new RuntimeException("db down"));

        Map<String, Object> resp = service.moderationFunnel(30);

        assertEquals(true, resp.get("degraded"));
        assertNull(resp.get("data"));
    }

    /* ---------------- 图表四：风险构成 ---------------- */

    @Test
    void riskBreakdown_healthy_returnsLevelCounts() {
        stubQueryForList(List.of(Map.of("risk_level", "LOW", "cnt", 1L)));

        Map<String, Object> resp = service.riskBreakdown(30);

        assertEquals(false, resp.get("degraded"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
        assertEquals("LOW", data.get(0).get("risk_level"));
    }

    @Test
    void riskBreakdown_queryFailure_isExplicitlyUnavailable() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new RuntimeException("db down"));

        Map<String, Object> resp = service.riskBreakdown(30);

        assertEquals(true, resp.get("degraded"));
        assertNull(resp.get("data"));
    }

    /* ---------------- 图表五：趋势 ---------------- */

    /** 趋势图四种事件必须分开统计：采用与收藏是不同列，不能合并。 */
    @Test
    void trend_healthy_returnsDailyRowsAndKeepsAdoptFavouriteSeparate() {
        stubQueryForList(List.of(Map.of("stat_date", "2026-09-11", "trips", 1L,
                "saves", 0L, "adopts", 6L, "favorites", 1L)));

        Map<String, Object> resp = service.trend(14);

        assertEquals(false, resp.get("degraded"));
        String sql = captureFirstSqlWithArgs();
        assertTrue(sql.contains("AS adopts") && sql.contains("AS favorites"),
                "采用与收藏必须是两个独立列，实际 SQL: " + sql);
    }

    @Test
    void trend_queryFailure_isExplicitlyUnavailable() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new RuntimeException("db down"));

        Map<String, Object> resp = service.trend(14);

        assertEquals(true, resp.get("degraded"));
        assertNull(resp.get("data"));
    }

    /* ---------------- 图表六：RAG 状态（两段查询，独立降级） ---------------- */

    @Test
    void ragStatus_healthy_returnsBothGuideAndTaskCounts() {
        when(jdbcTemplate.queryForList(anyString()))
                .thenReturn(List.of(Map.of("rag_status", "READY", "cnt", 10L)))
                .thenReturn(List.of(Map.of("status", "READY", "cnt", 7L)));

        Map<String, Object> resp = service.ragStatus();

        assertEquals(false, resp.get("degraded"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertNotNull(data.get("guides"));
        assertNotNull(data.get("tasks"));
    }

    /** 部分失败：攻略计数可用、任务计数不可用，二者互不污染。 */
    @Test
    void ragStatus_partialFailure_keepsHealthyPartAndFlagsDegraded() {
        when(jdbcTemplate.queryForList(anyString()))
                .thenReturn(List.of(Map.of("rag_status", "READY", "cnt", 10L)))
                .thenThrow(new RuntimeException("db down"));

        Map<String, Object> resp = service.ragStatus();

        assertEquals(true, resp.get("degraded"), "任一半失败即 degraded");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertNotNull(data.get("guides"), "攻略计数成功部分必须保留");
        assertNull(data.get("tasks"), "失败部分必须是 null，不能伪装成空集合");
        List<?> errors = (List<?>) resp.get("errors");
        assertTrue(errors.get(0).toString().startsWith("ragTaskStatus"),
                "errors 必须点名失败的那一半，实际: " + errors.get(0));
    }

    /* ---------------- helpers ---------------- */

    private void stubQueryForList(List<Map<String, Object>> rows) {
        lenient().when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(rows);
    }

    /** 捕获首个带参数查询的 SQL 文本（用于核对统计口径）。 */
    private String captureFirstSqlWithArgs() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), argsCaptor.capture());
        return sqlCaptor.getValue();
    }
}
