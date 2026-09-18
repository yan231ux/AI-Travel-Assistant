package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuntu.tripplanner.model.RecommendationIntervention;
import com.yuntu.tripplanner.model.Spot;
import com.yuntu.tripplanner.repository.SpotRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 首页热门景点服务单测（设计方案 §6.4 / §6.5）。
 *
 * <p>公式本身由 {@link SpotTrendingScoreCalculatorTest} 覆盖；这里守的是<b>安全规则与降级</b>：
 * 小样本不能暴露具体人数、黑名单与治理对象不能上首页、映射不到主档的脏数据要剔除、
 * 查询失败必须显式降级而不是"看起来今天没人规划"。
 */
@ExtendWith(MockitoExtension.class)
class SpotTrendingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private SpotRepository spotRepository;
    @Mock
    private RecommendationInterventionService interventionService;

    private SpotTrendingService service;

    @BeforeEach
    void setUp() {
        // 注册实体 TableInfo，否则 LambdaQueryWrapper 的方法引用在无 Spring 环境下解析失败
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Spot.class);
        service = new SpotTrendingService(jdbcTemplate, spotRepository, interventionService);
    }

    /* ---------------- 用例 ---------------- */

    @Test
    void smallSample_hidesExactPlanningUserCount() {
        stubRows(List.of(row("spot_a", "2026-09-11", "上海", 5, 2, 5, 5, 5, 0)));
        stubSpots(List.of(spot("spot_a", "外滩", "上海")));

        Map<String, Object> resp = service.trendingSpots(7, null, 8);

        List<Map<String, Object>> items = items(resp);
        assertEquals(1, items.size());
        assertNull(items.get(0).get("planning_users"), "样本不足 3 人时不暴露具体人数");
        assertEquals(true, items.get(0).get("sample_hidden"));
        assertEquals(5, items.get(0).get("planning_count"), "次数仍可展示");
    }

    @Test
    void enoughSample_showsExactPlanningUserCount() {
        stubRows(List.of(row("spot_a", "2026-09-11", "上海", 5, 9, 5, 5, 5, 0)));
        stubSpots(List.of(spot("spot_a", "外滩", "上海")));

        Map<String, Object> resp = service.trendingSpots(7, null, 8);

        List<Map<String, Object>> items = items(resp);
        assertEquals(9, items.get(0).get("planning_users"));
        assertEquals(false, items.get(0).get("sample_hidden"));
    }

    @Test
    void blacklistedSpot_isFilteredOut() {
        stubRows(List.of(
                row("spot_a", "2026-09-11", "上海", 5, 9, 5, 5, 5, 0),
                row("spot_b", "2026-09-11", "上海", 4, 9, 4, 4, 4, 0)));
        stubSpots(List.of(spot("spot_a", "外滩", "上海"), spot("spot_b", "武康路", "上海")));
        RecommendationIntervention act = new RecommendationIntervention();
        act.setAction(RecommendationIntervention.ACTION_BLACKLIST);
        act.setTargetType(RecommendationIntervention.TARGET_SPOT);
        act.setTargetId("spot_a");
        when(interventionService.activeNow()).thenReturn(List.of(act));

        Map<String, Object> resp = service.trendingSpots(7, null, 8);

        List<Map<String, Object>> items = items(resp);
        assertEquals(1, items.size());
        assertEquals("spot_b", items.get(0).get("spot_id"), "黑名单景点不得进入首页热门");
    }

    @Test
    void inactiveSpot_isFilteredOut() {
        Spot offline = spot("spot_a", "外滩", "上海");
        offline.setStatus("OFFLINE");
        stubRows(List.of(
                row("spot_a", "2026-09-11", "上海", 5, 9, 5, 5, 5, 0),
                row("spot_b", "2026-09-11", "上海", 4, 9, 4, 4, 4, 0)));
        stubSpots(List.of(offline, spot("spot_b", "武康路", "上海")));

        Map<String, Object> resp = service.trendingSpots(7, null, 8);

        List<Map<String, Object>> items = items(resp);
        assertEquals(1, items.size());
        assertEquals("spot_b", items.get(0).get("spot_id"), "已下线景点不得进入首页热门");
    }

    @Test
    void itemWithoutSpotMaster_isFilteredOut() {
        // 预聚合里可能残留 poi_id / name:xxx 口径，映射不到主档就不能上首页
        stubRows(List.of(row("name:某野景点", "2026-09-11", "上海", 5, 9, 5, 5, 5, 0)));
        stubSpots(List.of());

        Map<String, Object> resp = service.trendingSpots(7, null, 8);

        assertTrue(items(resp).isEmpty(), "映射不到主档的条目必须剔除");
        assertEquals(false, resp.get("degraded"), "这是正常的过滤，不算降级");
    }

    @Test
    void queryFailure_degradesExplicitlyInsteadOfLookingEmpty() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new RuntimeException("Table 'spot_trending_daily' doesn't exist"));

        Map<String, Object> resp = service.trendingSpots(7, null, 8);

        assertEquals(true, resp.get("degraded"), "查询失败必须显式降级");
        assertTrue(items(resp).isEmpty());
        assertFalse(((List<?>) resp.get("errors")).isEmpty(), "errors 必须说明失败原因");
    }

    @Test
    void emptyRows_returnsEmptyWithoutDegradation() {
        stubRows(List.of());

        Map<String, Object> resp = service.trendingSpots(7, null, 8);

        assertTrue(items(resp).isEmpty());
        assertEquals(false, resp.get("degraded"), "确实没有数据 ≠ 查询失败");
        assertEquals(7, resp.get("window_days"));
    }

    @Test
    void limitIsApplied() {
        stubRows(List.of(
                row("spot_a", "2026-09-11", "上海", 9, 9, 9, 9, 9, 0),
                row("spot_b", "2026-09-11", "上海", 5, 5, 5, 5, 5, 0),
                row("spot_c", "2026-09-11", "上海", 1, 1, 1, 1, 1, 0)));
        stubSpots(List.of(spot("spot_a", "A", "上海"), spot("spot_b", "B", "上海"),
                spot("spot_c", "C", "上海")));

        Map<String, Object> resp = service.trendingSpots(7, null, 2);

        assertEquals(2, items(resp).size(), "limit 必须生效");
    }

    /* ---------------- helpers ---------------- */

    private void stubRows(List<Map<String, Object>> rows) {
        lenient().when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(rows);
    }

    private void stubSpots(List<Spot> spots) {
        lenient().when(spotRepository.selectList(any())).thenReturn(spots);
    }

    private Spot spot(String spotId, String name, String city) {
        Spot s = new Spot();
        s.setSpotId(spotId);
        s.setName(name);
        s.setCity(city);
        s.setStatus(Spot.STATUS_ONLINE);
        return s;
    }

    private Map<String, Object> row(String itemId, String statDate, String city, int generated,
                                    int planningUsers, int saved, int favorited, int clicks, int dislikes) {
        Map<String, Object> m = new HashMap<>();
        m.put("stat_date", Date.valueOf(LocalDate.parse(statDate)));
        m.put("item_id", itemId);
        m.put("city", city);
        m.put("generated_count", generated);
        m.put("planning_user_count", planningUsers);
        m.put("saved_count", saved);
        m.put("favorited_count", favorited);
        m.put("click_count", clicks);
        m.put("dislike_count", dislikes);
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> resp) {
        return (List<Map<String, Object>>) resp.get("items");
    }
}
