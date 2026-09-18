package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.service.SpotTrendingScoreCalculator.DailyMetrics;
import com.yuntu.tripplanner.service.SpotTrendingScoreCalculator.ItemSeries;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 热度分算法单测（设计方案 §6.3）。
 *
 * <p>这里守的是两件最容易做错、也最容易被追问的事：
 * ① 归一化必须在城市内做（否则大城市按绝对量霸榜）；
 * ② 衰减要按天施加（否则一周前的一次爆发会被当成新鲜热度）。
 */
class SpotTrendingScoreCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 11);

    private DailyMetrics day(LocalDate d, String city, int generated, int planningUsers,
                             int saved, int favorited, int clicks, int dislikes) {
        return new DailyMetrics(d, city, generated, planningUsers, saved, favorited, clicks, dislikes);
    }

    /** 一个"全指标等值"的景点，便于隔离单变量 */
    private ItemSeries uniform(String id, LocalDate d, String city, int value) {
        return new ItemSeries(id, List.of(day(d, city, value, value, value, value, value, 0)));
    }

    @Test
    void halfLife_meansHalfAfter72Hours() {
        assertEquals(1.0, SpotTrendingScoreCalculator.decay(0), 1e-9);
        assertEquals(0.5, SpotTrendingScoreCalculator.decay(72), 1e-9, "一个半衰期后应恰好减半");
        assertEquals(0.25, SpotTrendingScoreCalculator.decay(144), 1e-9, "两个半衰期后应剩四分之一");
    }

    @Test
    void sameCity_higherMetricsScoreHigher() {
        Map<String, Double> s = SpotTrendingScoreCalculator.scoreAll(
                List.of(uniform("strong", TODAY, "上海", 5), uniform("weak", TODAY, "上海", 1)), TODAY);

        assertTrue(s.get("strong") > s.get("weak"), "同城内指标更高者得分更高");
    }

    /**
     * 核心规则：按城市归一化，防止大城市凭数据量长期霸榜。
     * 大城市头名与第二名绝对量都极大，小城市头名绝对量很小——
     * 归一化后两个头名应当同分，且小城市头名要能压过大城市第二名。
     */
    @Test
    void crossCity_normalizationKeepsSmallCityHeadCompetitive() {
        ItemSeries bigTop = uniform("bigTop", TODAY, "上海", 1000);
        ItemSeries bigSecond = uniform("bigSecond", TODAY, "上海", 800);
        ItemSeries smallTop = uniform("smallTop", TODAY, "大理", 20);

        Map<String, Double> s = SpotTrendingScoreCalculator.scoreAll(
                List.of(bigTop, bigSecond, smallTop), TODAY);

        assertEquals(s.get("bigTop"), s.get("smallTop"), 1e-9,
                "不同城市的头名归一化后应同分，否则大城市可凭绝对量霸榜");
        assertTrue(s.get("smallTop") > s.get("bigSecond"),
                "小城市头名应能压过大城市第二名（这正是按城市归一化的目的）");
    }

    @Test
    void dislikeLowersScore() {
        ItemSeries clean = uniform("clean", TODAY, "上海", 5);
        ItemSeries disliked = new ItemSeries("disliked",
                List.of(day(TODAY, "上海", 5, 5, 5, 5, 5, 5)));

        Map<String, Double> s = SpotTrendingScoreCalculator.scoreAll(List.of(clean, disliked), TODAY);

        assertTrue(s.get("disliked") < s.get("clean"), "同水平正向指标下，负反馈更多者得分更低");
    }

    @Test
    void decayIsAppliedPerDay_notAfterAggregation() {
        ItemSeries fresh = uniform("fresh", TODAY, "上海", 5);
        ItemSeries threeDaysOld = uniform("old", TODAY.minusDays(3), "上海", 5);

        Map<String, Double> s = SpotTrendingScoreCalculator.scoreAll(List.of(fresh, threeDaysOld), TODAY);

        assertTrue(s.get("fresh") > s.get("old"), "同样指标，越久远的日期贡献越小");
        assertEquals(s.get("fresh") * 0.5, s.get("old"), 1e-9,
                "相差恰好一个半衰期（3 天）时，旧数据应折半");
    }

    /** 极端负反馈可把得分压到负数，调用方必须 clamp 后再展示（服务层已做）。 */
    @Test
    void extremeNegativeFeedback_scoreCanGoNegative() {
        ItemSeries bad = new ItemSeries("bad",
                List.of(day(TODAY, "上海", 0, 0, 0, 0, 0, 5)));

        Map<String, Double> s = SpotTrendingScoreCalculator.scoreAll(List.of(bad), TODAY);

        assertTrue(s.get("bad") < 0, "纯负反馈应产生负分，交由调用方 clamp");
    }

    /** 多天数据应累加：连续三天都有热度 > 只有一天。 */
    @Test
    void multipleDaysAccumulate() {
        ItemSeries oneDay = uniform("oneDay", TODAY, "上海", 5);
        ItemSeries threeDays = new ItemSeries("threeDays", List.of(
                day(TODAY, "上海", 5, 5, 5, 5, 5, 0),
                day(TODAY.minusDays(1), "上海", 5, 5, 5, 5, 5, 0),
                day(TODAY.minusDays(2), "上海", 5, 5, 5, 5, 5, 0)));

        Map<String, Double> s = SpotTrendingScoreCalculator.scoreAll(List.of(oneDay, threeDays), TODAY);

        assertTrue(s.get("threeDays") > s.get("oneDay"), "持续有热度的应高于只热一天");
    }
}
