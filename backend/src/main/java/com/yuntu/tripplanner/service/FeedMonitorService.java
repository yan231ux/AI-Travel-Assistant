package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuntu.tripplanner.model.PostFeedLog;
import com.yuntu.tripplanner.model.SpotFeedLog;
import com.yuntu.tripplanner.model.UserBehavior;
import com.yuntu.tripplanner.repository.PostFeedLogRepository;
import com.yuntu.tripplanner.repository.SpotFeedLogRepository;
import com.yuntu.tripplanner.repository.UserBehaviorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 推荐流监控（阶段四任务 7）：聚合两条推荐流的曝光日志，输出可对照的监控读数。
 *
 * <p>输入只有三类日志，全部为确定性统计，零 LLM：
 * <ul>
 *   <li>曝光分母：spot_feed_log / post_feed_log（仅个性化推荐写日志，热门/最新浏览不写，
 *       口径与产品化阶段三「帖子推荐效果统计」一致）；</li>
 *   <li>反馈分子：user_behavior 中与该用户实际曝光过的条目（同 userId + item 键）相交的
 *       收藏/不感兴趣（SPOT 行为 item_id=poi_id，POST 行为 item_id=帖子ID，均与曝光日志同键）；</li>
 *   <li>A/B 变体：曝光日志的 ab_variant（CONTROL/TREATMENT/NONE），
 *       {@code by_variant} 只给曝光分布，{@code by_variant_metrics} 按变体各自算
 *       命中率/收藏率/负反馈率——实验效果判定看后者。</li>
 * </ul>
 * 全部结果由 {@link #report} 一次返回，供管理后台「推荐流监控」标签展示。
 */
@Slf4j
@Service
public class FeedMonitorService {

    public static final String FEED_SPOT = "SPOT_FEED";
    public static final String FEED_POST = "POST_FEED";

    private final SpotFeedLogRepository spotFeedLogRepository;
    private final PostFeedLogRepository postFeedLogRepository;
    private final UserBehaviorRepository userBehaviorRepository;

    public FeedMonitorService(SpotFeedLogRepository spotFeedLogRepository,
                              PostFeedLogRepository postFeedLogRepository,
                              UserBehaviorRepository userBehaviorRepository) {
        this.spotFeedLogRepository = spotFeedLogRepository;
        this.postFeedLogRepository = postFeedLogRepository;
        this.userBehaviorRepository = userBehaviorRepository;
    }

    /** 时间窗内推荐流监控报告（两条流各自的曝光/命中/质量构成/A-B 对照/反馈漏斗） */
    public MonitorReport report(int days) {
        int window = Math.max(1, Math.min(days <= 0 ? 7 : days, 90));
        LocalDateTime cutoff = LocalDateTime.now().minusDays(window);
        // 记录读取失败的数据源：监控读数"全 0"和"真的没有数据"必须可区分（P0-1 同模式）
        List<String> failures = new ArrayList<>();
        List<SpotFeedLog> spotLogs = spotLogsSince(cutoff, failures);
        List<PostFeedLog> postLogs = postLogsSince(cutoff, failures);
        List<UserBehavior> behaviors = behaviorsSince(cutoff, failures);

        Map<String, FeedMetrics> feeds = new LinkedHashMap<>();
        feeds.put(FEED_SPOT, metricsOfSpot(spotLogs, behaviors));
        feeds.put(FEED_POST, metricsOfPost(postLogs, behaviors));
        return new MonitorReport(window, feeds, failures);
    }

    /* ================= 单条流的指标 ================= */

    private FeedMetrics metricsOfSpot(List<SpotFeedLog> logs, List<UserBehavior> behaviors) {
        // 曝光-行为关联键（审查报告 P1-6）：以系统稳定 spot_id 为主、poi_id 为辅双别名，
        // 避免 poi_id 缺失的景点（景点底座允许 poi_id 为空）行为永远进不了反馈漏斗。
        java.util.Set<String> spotKeys = new java.util.HashSet<>();
        java.util.Set<String> poiKeys = new java.util.HashSet<>();
        // 曝光键 → 变体：把"看到之后的行为"归到该用户当时所在的变体上（A/B 对照必需）
        Map<String, String> keyVariant = new LinkedHashMap<>();
        FeedAgg agg = new FeedAgg();
        for (SpotFeedLog row : logs) {
            agg.exposures++;
            agg.userSet.merge(row.getUserId(), 1L, Long::sum);
            String vk = variantKey(row.getAbVariant());
            boolean hit = Integer.valueOf(1).equals(row.getHitPreference());
            if (hit) {
                agg.hits++;
            }
            agg.scoreSum += row.getFinalScore() == null ? 0 : row.getFinalScore();
            agg.byVariant.merge(vk, 1L, Long::sum);
            VariantMetrics vm = agg.variantOf(vk);
            vm.exposures++;
            if (hit) {
                vm.hits++;
            }
            agg.byQuality.merge(qualityKey(row.getDataQuality()), 1L, Long::sum);
            agg.byCity.merge(cityKey(row.getCity()), 1L, Long::sum);
            if (row.getUserId() != null) {
                if (row.getSpotId() != null) {
                    String key = row.getUserId() + "|" + row.getSpotId();
                    spotKeys.add(key);
                    keyVariant.put(key, vk);
                }
                if (row.getPoiId() != null) {
                    String key = row.getUserId() + "|" + row.getPoiId();
                    poiKeys.add(key);
                    keyVariant.put(key, vk);
                }
            }
        }
        agg.feedbacks.putAll(feedbackCounts(behaviors, UserBehavior.ITEM_TYPE_SPOT, spotKeys, poiKeys,
                keyVariant, agg));
        return agg.toMetrics(agg.exposures == 0 ? 0 : agg.scoreSum / agg.exposures);
    }

    private FeedMetrics metricsOfPost(List<PostFeedLog> logs, List<UserBehavior> behaviors) {
        java.util.Set<String> postKeys = new java.util.HashSet<>();
        Map<String, String> keyVariant = new LinkedHashMap<>();
        FeedAgg agg = new FeedAgg();
        for (PostFeedLog row : logs) {
            agg.exposures++;
            agg.userSet.merge(row.getUserId(), 1L, Long::sum);
            String vk = variantKey(row.getAbVariant());
            boolean hit = Integer.valueOf(1).equals(row.getHitPreference());
            if (hit) {
                agg.hits++;
            }
            agg.scoreSum += row.getFinalScore() == null ? 0 : row.getFinalScore();
            agg.byVariant.merge(vk, 1L, Long::sum);
            VariantMetrics vm = agg.variantOf(vk);
            vm.exposures++;
            if (hit) {
                vm.hits++;
            }
            agg.byQuality.merge("POST", 1L, Long::sum); // 帖子无攻略质量维度，占位保持结构一致
            if (row.getUserId() != null && row.getPostId() != null) {
                String key = row.getUserId() + "|" + row.getPostId();
                postKeys.add(key);
                keyVariant.put(key, vk);
            }
        }
        agg.feedbacks.putAll(feedbackCounts(behaviors, UserBehavior.ITEM_TYPE_POST, postKeys,
                java.util.Set.of(), keyVariant, agg));
        return agg.toMetrics(agg.exposures == 0 ? 0 : agg.scoreSum / agg.exposures);
    }

    /**
     * 反馈漏斗：行为 (item_type, item_id) 命中同一用户曝光键才计数（只统计"看到之后"的行为）。
     * 行为 item_id 与曝光键匹配：POST=帖子ID；SPOT=spot_id 或 poi_id（双键任一命中即可，
     * 兼容旧数据只存 poi_id 与 poi_id 为空但存 spot_id 的新口径）。
     *
     * <p>计数同时写入所属变体（keyVariant）：A/B 对照需要把反馈率也按变体拆开，
     * 否则对照/处理组只能看曝光量，无法回答"新策略到底有没有让人更爱收藏"。
     */
    private Map<String, Long> feedbackCounts(List<UserBehavior> behaviors, String itemType,
                                             java.util.Set<String> exposedKeys,
                                             java.util.Set<String> aliasKeys,
                                             Map<String, String> keyVariant, FeedAgg agg) {
        Map<String, Long> out = new LinkedHashMap<>();
        long save = 0, dislike = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (UserBehavior b : behaviors) {
            if (b == null || !itemType.equals(b.getItemType()) || b.getItemId() == null
                    || b.getUserId() == null || b.getActionType() == null) {
                continue;
            }
            if (!UserBehavior.ACTION_SAVE.equals(b.getActionType())
                    && !UserBehavior.ACTION_DISLIKE.equals(b.getActionType())) {
                continue;
            }
            String key = b.getUserId() + "|" + b.getItemId();
            if ((!exposedKeys.contains(key) && !aliasKeys.contains(key))
                    || !seen.add(key + "|" + b.getActionType())) {
                continue; // 未曝光过 / 同用户同条目同行为只算一次
            }
            boolean isSave = UserBehavior.ACTION_SAVE.equals(b.getActionType());
            if (isSave) {
                save++;
            } else {
                dislike++;
            }
            String vk = keyVariant.get(key);
            if (vk != null) {
                VariantMetrics vm = agg.variantOf(vk);
                if (isSave) {
                    vm.save++;
                } else {
                    vm.dislike++;
                }
            }
        }
        out.put("save", save);
        out.put("dislike", dislike);
        return out;
    }

    /* ================= 数据读取 ================= */

    private List<SpotFeedLog> spotLogsSince(LocalDateTime cutoff, List<String> failures) {
        try {
            return spotFeedLogRepository.selectList(new LambdaQueryWrapper<SpotFeedLog>()
                    .ge(SpotFeedLog::getCreatedAt, cutoff)
                    .orderByAsc(SpotFeedLog::getCreatedAt)
                    .last("LIMIT 10000"));
        } catch (Exception e) {
            log.warn("spot_feed_log 读取失败（监控降级为空）: {}", e.getMessage());
            failures.add("spot_feed_log");
            return List.of();
        }
    }

    private List<PostFeedLog> postLogsSince(LocalDateTime cutoff, List<String> failures) {
        try {
            return postFeedLogRepository.selectList(new LambdaQueryWrapper<PostFeedLog>()
                    .ge(PostFeedLog::getCreatedAt, cutoff)
                    .orderByAsc(PostFeedLog::getCreatedAt)
                    .last("LIMIT 10000"));
        } catch (Exception e) {
            log.warn("post_feed_log 读取失败（监控降级为空）: {}", e.getMessage());
            failures.add("post_feed_log");
            return List.of();
        }
    }

    private List<UserBehavior> behaviorsSince(LocalDateTime cutoff, List<String> failures) {
        try {
            return userBehaviorRepository.selectList(new LambdaQueryWrapper<UserBehavior>()
                    .ge(UserBehavior::getCreatedAt, cutoff)
                    .in(UserBehavior::getActionType,
                            UserBehavior.ACTION_SAVE, UserBehavior.ACTION_DISLIKE)
                    .last("LIMIT 20000"));
        } catch (Exception e) {
            log.warn("user_behavior 读取失败（反馈漏斗降级为 0）: {}", e.getMessage());
            failures.add("user_behavior");
            return List.of();
        }
    }

    /* ================= 展示载体 ================= */

    /** 时间窗内两条推荐流的监控报告；failures 非空表示部分数据源读取失败，读数不可信 */
    public record MonitorReport(int days, Map<String, FeedMetrics> feeds, List<String> failures) {
    }

    /** 单条流的监控读数 */
    public static class FeedMetrics {
        public long exposures;
        public long users;
        public long hits;
        public double avgScore;
        /** 命中率 = 命中偏好的曝光行 / 总曝光 */
        public double hitRate;
        /** 按 A/B 变体（CONTROL/TREATMENT/NONE）的曝光分布 */
        public Map<String, Long> byVariant = new LinkedHashMap<>();
        /** 内容质量构成（SPOT：GUIDE_MATCHED/POI_ONLY/VERIFIED 占比口径） */
        public Map<String, Long> byQuality = new LinkedHashMap<>();
        /**
         * 各城市推荐量（设计方案 §6「推荐流概览」）：按曝光行的 city 聚合，值=曝光行数。
         * 帖子流无城市维度，保持空表（不编造城市分布）。
         */
        public Map<String, Long> byCity = new LinkedHashMap<>();
        /** 反馈漏斗：save/dislike（=与曝光相交的用户行为计数）与相对曝光率 */
        public Map<String, Long> feedbacks = new LinkedHashMap<>();
        public double saveRate;
        public double dislikeRate;
        /**
         * A/B 变体对照读数（CONTROL/TREATMENT/NONE）：每个变体各自算命中率与反馈率，
         * 而不是只给曝光分布。这是"实验到底有没有效果"的唯一可判定数据——
         * byVariant 只有数量，无法回答对照与处理组谁更好。
         */
        public Map<String, VariantMetrics> byVariantMetrics = new LinkedHashMap<>();

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("exposures", exposures);
            m.put("users", users);
            m.put("hits", hits);
            m.put("hit_rate", Math.round(hitRate * 1000) / 10.0);
            m.put("avg_score", Math.round(avgScore * 1000) / 1000.0);
            m.put("by_variant", byVariant);
            m.put("by_variant_metrics", variantMetricsToMap());
            m.put("by_quality", byQuality);
            m.put("by_city", byCity);
            m.put("feedbacks", feedbacks);
            m.put("save_rate", Math.round(saveRate * 1000) / 10.0);
            m.put("dislike_rate", Math.round(dislikeRate * 1000) / 10.0);
            return m;
        }

        private Map<String, Object> variantMetricsToMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            byVariantMetrics.forEach((k, v) -> out.put(k, v.toMap()));
            return out;
        }
    }

    /** 单变体读数：曝光/命中/反馈全部按变体拆开，供 A/B 对照 */
    public static class VariantMetrics {
        public long exposures;
        public long hits;
        /** 命中率 = 该变体命中偏好曝光 / 该变体总曝光 */
        public double hitRate;
        public long save;
        public long dislike;
        public double saveRate;
        public double dislikeRate;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("exposures", exposures);
            m.put("hits", hits);
            m.put("hit_rate", Math.round(hitRate * 1000) / 10.0);
            m.put("save", save);
            m.put("dislike", dislike);
            m.put("save_rate", Math.round(saveRate * 1000) / 10.0);
            m.put("dislike_rate", Math.round(dislikeRate * 1000) / 10.0);
            return m;
        }

        void recomputeRates() {
            hitRate = exposures == 0 ? 0 : (double) hits / exposures;
            saveRate = exposures == 0 ? 0 : (double) save / exposures;
            dislikeRate = exposures == 0 ? 0 : (double) dislike / exposures;
        }
    }

    /** 聚合临时桶 */
    private static class FeedAgg {
        long exposures;
        long hits;
        double scoreSum;
        Map<String, Long> userSet = new LinkedHashMap<>();
        Map<String, Long> byVariant = new LinkedHashMap<>();
        Map<String, Long> byQuality = new LinkedHashMap<>();
        Map<String, Long> byCity = new LinkedHashMap<>();
        Map<String, Long> feedbacks = new LinkedHashMap<>();
        Map<String, VariantMetrics> variantMetrics = new LinkedHashMap<>();

        /** 取（或建）某变体的对照桶 */
        VariantMetrics variantOf(String variant) {
            return variantMetrics.computeIfAbsent(variant, k -> new VariantMetrics());
        }

        FeedMetrics toMetrics(double avgScore) {
            FeedMetrics m = new FeedMetrics();
            m.exposures = exposures;
            m.users = userSet.size();
            m.hits = hits;
            m.avgScore = avgScore;
            m.hitRate = exposures == 0 ? 0 : (double) hits / exposures;
            m.byVariant = byVariant;
            m.byQuality = byQuality;
            m.byCity = byCity;
            m.feedbacks = feedbacks;
            long save = feedbacks.getOrDefault("save", 0L);
            long dislike = feedbacks.getOrDefault("dislike", 0L);
            m.saveRate = exposures == 0 ? 0 : (double) save / exposures;
            m.dislikeRate = exposures == 0 ? 0 : (double) dislike / exposures;
            variantMetrics.values().forEach(VariantMetrics::recomputeRates);
            m.byVariantMetrics = variantMetrics;
            return m;
        }
    }

    private static String variantKey(String abVariant) {
        return abVariant == null || abVariant.isBlank() ? "NONE" : abVariant;
    }

    private static String qualityKey(String quality) {
        return quality == null || quality.isBlank() ? "UNKNOWN" : quality;
    }

    /** 城市分组键：曝光行 city 为空归入 UNKNOWN（不能因为缺城市就把曝光算丢） */
    private static String cityKey(String city) {
        return city == null || city.isBlank() ? "UNKNOWN" : city;
    }
}
