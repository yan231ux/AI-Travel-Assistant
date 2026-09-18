package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.model.UserBehavior;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

/**
 * 城市推荐质量单测（设计方案 §6「城市推荐质量」/admin/recommendations）。
 *
 * <p>这是一个"多数据源拼装 + 口径对齐"的服务，测试重点不在算术，而在四条容易出错的规则：
 * <ol>
 *   <li>无攻略数 = 可推荐数 − 有攻略数（两个数永远自洽，不靠第二次查询）；</li>
 *   <li>反馈只认「先曝光、后行为」，且同用户同条目同行为只算一次；</li>
 *   <li>任一数据源读失败必须显式 degraded，绝不显示成"这个城市质量差"；</li>
 *   <li>未采集的指标（推荐失败率）要在 notes 里写明，不留静默缺口。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class RecommendationQualityServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private RecommendationQualityService service;

    @BeforeEach
    void setUp() {
        service = new RecommendationQualityService(jdbcTemplate);
    }

    /* ---------------- 数据构造 ---------------- */

    private Map<String, Object> spotRow(String city, int recommendable, int guideBacked,
                                        double avgQuality, Timestamp lastSynced) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("city", city);
        m.put("recommendable", recommendable);
        m.put("guide_backed", guideBacked);
        m.put("avg_quality", avgQuality);
        m.put("last_synced", lastSynced);
        return m;
    }

    private Map<String, Object> feedRow(String city, String user, String spotId, String poiId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("city", city);
        m.put("user_id", user);
        m.put("spot_id", spotId);
        m.put("poi_id", poiId);
        return m;
    }

    private Map<String, Object> behaviorRow(String user, String itemId, String action) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", user);
        m.put("item_id", itemId);
        m.put("action_type", action);
        return m;
    }

    private Map<String, Object> ragRow(String city, Timestamp lastRag) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("city", city);
        m.put("last_rag", lastRag);
        return m;
    }

    /** 按 SQL 特征分发 stub：景点存量 / RAG 时间 / 曝光 / 行为（各数据源互相独立） */
    private void stub(List<Map<String, Object>> spots, List<Map<String, Object>> rag,
                      List<Map<String, Object>> feeds, List<Map<String, Object>> behaviors) {
        when(jdbcTemplate.queryForList(contains("FROM spot WHERE "))).thenReturn(spots);
        when(jdbcTemplate.queryForList(contains("JOIN city_guide"))).thenReturn(rag);
        when(jdbcTemplate.queryForList(contains("FROM spot_feed_log"), any(Object[].class)))
                .thenReturn(feeds);
        when(jdbcTemplate.queryForList(contains("FROM user_behavior"), any(Object[].class)))
                .thenReturn(behaviors);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemsOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }

    private Map<String, Object> cityOf(Map<String, Object> body, String city) {
        for (Map<String, Object> m : itemsOf(body)) {
            if (city.equals(m.get("city"))) {
                return m;
            }
        }
        return null;
    }

    /* ---------------- 存量与质量构成 ---------------- */

    @Test
    void inventory_poiOnlyIsDerivedFromRecommendableMinusGuideBacked() {
        stub(List.of(
                        spotRow("上海", 10, 4, 64.0, Timestamp.valueOf("2026-09-10 08:00:00")),
                        spotRow("大理", 3, 3, 100.0, Timestamp.valueOf("2026-09-09 08:00:00"))),
                List.of(), List.of(), List.of());

        Map<String, Object> body = service.cityQuality(30, 50);

        Map<String, Object> sh = cityOf(body, "上海");
        assertEquals(10, sh.get("recommendable_spots"));
        assertEquals(4, sh.get("guide_backed_spots"));
        assertEquals(6, sh.get("poi_only_spots"));
        assertEquals(64.0, (Double) sh.get("avg_quality_score"), 1e-6);
        assertEquals("2026-09-10 08:00:00", sh.get("last_synced_at"));

        // 全部有攻略 → 无攻略数必须是 0，不能出现负数
        Map<String, Object> dl = cityOf(body, "大理");
        assertEquals(0, dl.get("poi_only_spots"));
    }

    @Test
    void inventory_guideBackedExceedingRecommendable_neverYieldsNegativePoiOnly() {
        // 脏数据防御：有攻略数大于可推荐数（攻略关联了后来被下线的景点）→ 仍为 0
        stub(List.of(spotRow("上海", 2, 5, 80.0, null)), List.of(), List.of(), List.of());

        Map<String, Object> sh = cityOf(service.cityQuality(30, 50), "上海");

        assertEquals(0, sh.get("poi_only_spots"));
        assertNull(sh.get("last_synced_at")); // 从未同步 → null（前端显示"暂无"）
    }

    /* ---------------- 反馈漏斗 ---------------- */

    @Test
    void feedback_onlyCountsExposedPairsAndDeduplicates() {
        stub(List.of(spotRow("上海", 5, 2, 62.0, null)),
                List.of(),
                List.of(
                        feedRow("上海", "u1", "spot_上海_a", "poi_a"),
                        feedRow("上海", "u1", "spot_上海_b", "poi_b"),
                        feedRow("上海", "u2", "spot_上海_c", "poi_c")),
                List.of(
                        // u1 曝光过 a → 收藏算数
                        behaviorRow("u1", "poi_a", UserBehavior.ACTION_SAVE),
                        // 重复上报同一条 → 只算一次
                        behaviorRow("u1", "poi_a", UserBehavior.ACTION_SAVE),
                        // u2 曝光过 c → 不感兴趣算数
                        behaviorRow("u2", "poi_c", UserBehavior.ACTION_DISLIKE),
                        // u9 从未曝光 → 不进推荐效果口径
                        behaviorRow("u9", "poi_z", UserBehavior.ACTION_SAVE)));

        Map<String, Object> sh = cityOf(service.cityQuality(30, 50), "上海");

        assertEquals(3L, sh.get("exposures"));
        assertEquals(2, sh.get("users"));       // u1 曝光两行仍只算一个用户
        assertEquals(1L, sh.get("save_count"));
        assertEquals(1L, sh.get("dislike_count"));
        assertEquals(33.3, (Double) sh.get("save_rate"), 1e-6);     // 1/3
        assertEquals(33.3, (Double) sh.get("dislike_rate"), 1e-6);
    }

    @Test
    void feedback_matchesBySpotIdWhenPoiIdMissing() {
        // 景点底座允许 poi_id 为空：行为按系统 spot_id 上报也要能进漏斗
        stub(List.of(spotRow("大理", 3, 1, 50.0, null)),
                List.of(),
                List.of(feedRow("大理", "u1", "spot_大理_manual001", null)),
                List.of(behaviorRow("u1", "spot_大理_manual001", UserBehavior.ACTION_SAVE)));

        Map<String, Object> dl = cityOf(service.cityQuality(30, 50), "大理");

        assertEquals(1L, dl.get("save_count"));
        assertEquals(100.0, (Double) dl.get("save_rate"), 1e-6);
    }

    @Test
    void feedback_exposureOfCityWithoutSpotsIsIgnored() {
        // 曝光城市不在景点存量里（该城景点已全部下线）→ 不计入任何城市行
        stub(List.of(spotRow("上海", 5, 2, 62.0, null)),
                List.of(),
                List.of(feedRow("拉萨", "u1", "spot_拉萨_x", "poi_x")),
                List.of(behaviorRow("u1", "poi_x", UserBehavior.ACTION_SAVE)));

        Map<String, Object> body = service.cityQuality(30, 50);

        assertEquals(1, itemsOf(body).size());
        assertEquals(0L, cityOf(body, "上海").get("exposures"));
        assertNull(cityOf(body, "拉萨"));
    }

    @Test
    void zeroExposures_ratesAreZeroButNotDegraded() {
        stub(List.of(spotRow("上海", 5, 2, 62.0, null)), List.of(), List.of(), List.of());

        Map<String, Object> body = service.cityQuality(30, 50);

        Map<String, Object> sh = cityOf(body, "上海");
        assertEquals(0.0, (Double) sh.get("save_rate"), 1e-6);
        assertEquals(0.0, (Double) sh.get("dislike_rate"), 1e-6);
        // 没有曝光 ≠ 故障：不能置 degraded
        assertFalse((Boolean) body.get("degraded"));
        assertTrue(((List<?>) body.get("errors")).isEmpty());
    }

    /* ---------------- 排序与 RAG 时间 ---------------- */

    @Test
    void sortsByExposuresDescThenByInventory() {
        stub(List.of(
                        spotRow("上海", 5, 2, 62.0, null),
                        spotRow("丽江", 20, 3, 45.0, null),
                        spotRow("大理", 3, 1, 50.0, null)),
                List.of(),
                List.of(feedRow("上海", "u1", "s1", "p1")),
                List.of());

        List<Map<String, Object>> items = itemsOf(service.cityQuality(30, 50));

        // 上海有曝光排第一；丽江(20) 大理(3) 都无曝光 → 按存量降序
        assertEquals("上海", items.get(0).get("city"));
        assertEquals("丽江", items.get(1).get("city"));
        assertEquals("大理", items.get(2).get("city"));
    }

    @Test
    void ragUpdatedAt_onlyFromReadyIndexTasks() {
        stub(List.of(spotRow("上海", 5, 2, 62.0, null)),
                List.of(ragRow("上海", Timestamp.valueOf("2026-09-11 07:30:00"))),
                List.of(), List.of());

        Map<String, Object> sh = cityOf(service.cityQuality(30, 50), "上海");

        assertEquals("2026-09-11 07:30:00", sh.get("last_rag_updated_at"));
    }

    /* ---------------- 降级口径 ---------------- */

    @Test
    void spotQueryFailure_returnsEmptyItemsAndDegraded() {
        when(jdbcTemplate.queryForList(contains("FROM spot WHERE ")))
                .thenThrow(new RuntimeException("table missing"));

        Map<String, Object> body = service.cityQuality(30, 50);

        // 城市列表的唯一来源失败 → 不猜城市，直接空列表 + 显式降级
        assertTrue(itemsOf(body).isEmpty());
        assertTrue((Boolean) body.get("degraded"));
        assertEquals(1, ((List<?>) body.get("errors")).size());
        assertTrue(body.get("errors").toString().contains("spot"));
    }

    @Test
    void ragQueryFailure_onlyDegradesThatColumn_otherDataStillReturned() {
        when(jdbcTemplate.queryForList(contains("FROM spot WHERE ")))
                .thenReturn(List.of(spotRow("上海", 5, 2, 62.0, null)));
        when(jdbcTemplate.queryForList(contains("JOIN city_guide")))
                .thenThrow(new RuntimeException("rag_index_task missing"));
        when(jdbcTemplate.queryForList(contains("FROM spot_feed_log"), any(Object[].class)))
                .thenReturn(List.of(feedRow("上海", "u1", "s1", "p1")));
        when(jdbcTemplate.queryForList(contains("FROM user_behavior"), any(Object[].class)))
                .thenReturn(List.of());

        Map<String, Object> body = service.cityQuality(30, 50);

        Map<String, Object> sh = cityOf(body, "上海");
        assertNotNull(sh);
        assertEquals(1L, sh.get("exposures"));          // 其他数据源不受影响
        assertNull(sh.get("last_rag_updated_at"));      // 这一列如实为空
        assertTrue((Boolean) body.get("degraded"));
        assertTrue(body.get("errors").toString().contains("rag_index_task"));
    }

    @Test
    void feedbackQueryFailure_degradesButInventoryStays() {
        when(jdbcTemplate.queryForList(contains("FROM spot WHERE ")))
                .thenReturn(List.of(spotRow("上海", 5, 2, 62.0, null)));
        when(jdbcTemplate.queryForList(contains("JOIN city_guide"))).thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("FROM spot_feed_log"), any(Object[].class)))
                .thenThrow(new RuntimeException("log missing"));
        when(jdbcTemplate.queryForList(contains("FROM user_behavior"), any(Object[].class)))
                .thenReturn(List.of());

        Map<String, Object> body = service.cityQuality(30, 50);

        // 曝光读不到 → 反馈列降级，但景点存量仍然可用（不因为一段失败就整表空）
        assertEquals(5, cityOf(body, "上海").get("recommendable_spots"));
        assertTrue((Boolean) body.get("degraded"));
        assertTrue(body.get("errors").toString().contains("spot_feed_log"));
    }

    /* ---------------- 参数与诚实缺口 ---------------- */

    @Test
    void windowAndLimitAreClamped() {
        stub(List.of(spotRow("上海", 5, 2, 62.0, null)), List.of(), List.of(), List.of());

        assertEquals(180, service.cityQuality(9999, 50).get("window_days"));
        // days<=0 = 未指定 → 用默认窗口 30 天（不是被压成 1 天，否则窗口内什么都看不到）
        assertEquals(30, service.cityQuality(0, 50).get("window_days"));
        // limit<=0 = 未指定 → 用默认 50；本次只有 1 个城市，故仍返回 1 行
        assertEquals(1, itemsOf(service.cityQuality(30, 0)).size());
    }

    @Test
    void notes_discloseUnmeasuredFailureRateAndQualityWeightCaliber() {
        stub(List.of(spotRow("上海", 5, 2, 62.0, null)), List.of(), List.of(), List.of());

        Map<String, Object> body = service.cityQuality(30, 50);
        String notes = body.get("notes").toString();

        // 未采集的指标必须显式说明，不能被静默省略
        assertTrue(notes.contains("推荐失败率未采集"), notes);
        // 平均质量分口径必须写明是可信度加权，不是攻略内容分
        assertTrue(notes.contains("可信度加权"), notes);
        assertNotNull(body.get("generated_at"));
    }

    @Test
    void emptySpotTable_isNotAnError() {
        // 景点主档为空 → 城市列表无来源，此时**不应**再去查 RAG/曝光/行为（没有城市可归属）
        when(jdbcTemplate.queryForList(contains("FROM spot WHERE "))).thenReturn(new ArrayList<>());

        Map<String, Object> body = service.cityQuality(30, 50);

        assertTrue(itemsOf(body).isEmpty());
        assertFalse((Boolean) body.get("degraded")); // 库里真没景点 ≠ 查询故障
    }
}
