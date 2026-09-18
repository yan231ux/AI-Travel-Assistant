package com.yuntu.tripplanner.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 景点热度分计算（设计方案 §6.3）。纯函数、无 Spring 依赖，便于单测锁定公式。
 *
 * <pre>
 * hot_score = 0.40 × norm(规划人数) + 0.25 × norm(保存次数) + 0.15 × norm(收藏次数)
 *           + 0.10 × norm(详情点击) − 0.10 × norm(负反馈)
 * decayed   = hot_score × exp(−age_hours / half_life_hours)
 * </pre>
 *
 * <p><b>两条关键口径（都出自文档，也是最容易做错的地方）</b>：
 * <ol>
 *   <li><b>归一化必须在「城市内」做</b>：否则数据量大的城市会凭绝对量长期霸榜。
 *       这里用最大值归一化（x ÷ 城市内同指标最大值），城市内头名恒为 1.0，
 *       使小城市的头部景点能和大城市的头部同台竞争。</li>
 *   <li><b>衰减要按天施加</b>：窗口内每天的日热度分各自按「距今天数」衰减后再累加，
 *       而不是先把 7 天累加、再统一衰减一次——否则一周前的一次爆发会被当成"新鲜热度"。</li>
 * </ol>
 *
 * <p>结果可能为负（负反馈权重），调用方需 clamp 到非负再展示。
 */
public final class SpotTrendingScoreCalculator {

    /** 景点热度半衰期（文档 §6.3 建议：景点 72 小时） */
    public static final double HALF_LIFE_HOURS = 72.0;

    /** ln2：把"半衰期"换算成指数衰减系数（见 {@link #decay} 的说明） */
    private static final double LN2 = Math.log(2);

    private static final double W_PLANNING_USERS = 0.40;
    private static final double W_SAVED = 0.25;
    private static final double W_FAVORITED = 0.15;
    private static final double W_CLICK = 0.10;
    private static final double W_DISLIKE = 0.10;

    private SpotTrendingScoreCalculator() {
    }

    /**
     * 单个景点在某一天的原始指标（来自 spot_trending_daily 的一行）。
     *
     * <p>{@code generatedCount} 只用于展示与趋势比较，<b>不参与热度分公式</b>——
     * 公式里的「规划」口径是人次（planningUsers）而非次数，两者不可混用。
     */
    public record DailyMetrics(
            LocalDate statDate,
            String city,
            int generatedCount,
            int planningUsers,
            int savedCount,
            int favoritedCount,
            int clickCount,
            int dislikeCount) {
    }

    /** 一个景点在窗口内的日级序列 */
    public record ItemSeries(String itemId, List<DailyMetrics> days) {
    }

    /**
     * 计算窗口内全部景点的衰减后热度分。
     *
     * @param items 各景点的日级明细（窗口内）
     * @param today 计算基准日，用于推算每一天的年龄
     * @return item_id → 得分（未 clamp，可能为负）
     */
    public static Map<String, Double> scoreAll(List<ItemSeries> items, LocalDate today) {
        // 第一遍：收集「同一天 + 同一城市」各指标的归一化分母（最大值）
        Map<String, int[]> denominator = new LinkedHashMap<>();
        for (ItemSeries series : items) {
            for (DailyMetrics d : series.days()) {
                int[] max = denominator.computeIfAbsent(dayCityKey(d), k -> new int[5]);
                max[0] = Math.max(max[0], d.planningUsers());
                max[1] = Math.max(max[1], d.savedCount());
                max[2] = Math.max(max[2], d.favoritedCount());
                max[3] = Math.max(max[3], d.clickCount());
                max[4] = Math.max(max[4], d.dislikeCount());
            }
        }
        // 第二遍：逐天算分并按年龄衰减后累加
        Map<String, Double> scored = new LinkedHashMap<>();
        for (ItemSeries series : items) {
            double total = 0;
            for (DailyMetrics d : series.days()) {
                int[] max = denominator.get(dayCityKey(d));
                double hot = W_PLANNING_USERS * normalize(d.planningUsers(), max[0])
                        + W_SAVED * normalize(d.savedCount(), max[1])
                        + W_FAVORITED * normalize(d.favoritedCount(), max[2])
                        + W_CLICK * normalize(d.clickCount(), max[3])
                        - W_DISLIKE * normalize(d.dislikeCount(), max[4]);
                total += hot * decay(ageHours(d.statDate(), today));
            }
            scored.put(series.itemId(), total);
        }
        return scored;
    }

    /**
     * 指数时间衰减。
     *
     * <p><b>与文档字面公式的一处偏差（有意为之）</b>：文档 §6.3 写的是
     * {@code exp(−age_hours / half_life_hours)}，但同一句又把 72 小时称作"半衰期"——
     * 这两个说法在数学上不等价：{@code exp(−age/T)} 是<b>时间常数</b>写法，
     * 此时 T 小时后只衰减到 36.8%，并非"减半"。
     *
     * <p>这里按"半衰期"的字面语义实现：经过 T 小时热度恰好降到一半，
     * 即 {@code exp(−ln2 · age / T)}（T=72h → 0.5，144h → 0.25），符合直觉与命名。
     * 若要与文档公式逐字对齐，只需把下面的 ln2 去掉。
     */
    public static double decay(double ageHours) {
        return Math.exp(-LN2 * ageHours / HALF_LIFE_HOURS);
    }

    /** 最大值归一化；分母为 0（该天该城市无数据）时返回 0，避免除零 */
    private static double normalize(int value, int max) {
        return max <= 0 ? 0.0 : (double) value / max;
    }

    private static double ageHours(LocalDate statDate, LocalDate today) {
        long days = ChronoUnit.DAYS.between(statDate, today);
        return Math.max(0, days) * 24.0;
    }

    private static String dayCityKey(DailyMetrics d) {
        return d.statDate() + "|" + (d.city() == null ? "" : d.city());
    }
}
