package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.model.PostFeedLog;
import com.yuntu.tripplanner.model.PostTag;
import com.yuntu.tripplanner.model.TravelPost;
import com.yuntu.tripplanner.model.UserPreference;
import com.yuntu.tripplanner.repository.PostFeedLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 帖子推荐引擎（产品化阶段三：社区"为你推荐"的排序与曝光证据记录）。
 *
 * <p>只回答两件事：
 * <ol>
 *   <li><b>排序</b>：登录且有正偏好画像 → 按 {@link PersonalizedScoreCalculator#evaluatePost}
 *       多域打分（风格/口味/节奏/城市，与景点共用同一画像行与回避阈值），同分按互动热度与
 *       发布时间破平；无画像/匿名 → 调用方降级热门（本引擎不产出空流，保证新用户可见内容）；</li>
 *   <li><b>证据</b>：每次向登录用户曝光一页，把 位置/得分/命中/理由/画像版本/算法版本 写入
 *       {@link PostFeedLog}（阶段三任务 5），作为「推荐可解释、可追踪」与效果统计（任务 8）分母。</li>
 * </ol>
 * 不做 CRUD/权限/分页 —— 那是 PostService 的职责，本引擎只做排序与日志的纯计算（便于单测）。
 */
@Slf4j
@Service
public class PostFeedEngine {

    private final PostFeedLogRepository postFeedLogRepository;

    public PostFeedEngine(PostFeedLogRepository postFeedLogRepository) {
        this.postFeedLogRepository = postFeedLogRepository;
    }

    /** 排序结果（position 由曝光页内位置决定，见 logExposures） */
    public record RankedPost(Long postId, double score, String reason, List<String> matchedTags,
                             boolean hitPreference) {
    }

    /**
     * 是否具备真正的个性化条件：画像里有任一帖子弹域（风格/口味/节奏/城市）的
     * 正偏好行（问卷恒正；行为/推断权重 ≥ 阈值）。与景点流的降级语义一致：
     * 无正偏好 → 不做个性化排序（调用方走热门降级，避免"假个性化"）。
     */
    public boolean personalizedOf(List<UserPreference> prefs) {
        if (prefs == null || prefs.isEmpty()) {
            return false;
        }
        for (UserPreference p : prefs) {
            if (p == null || p.getTag() == null || p.getTag().isBlank()) {
                continue;
            }
            if (!PersonalizedScoreCalculator.POST_POSITIVE_DOMAINS.contains(p.getCategory())) {
                continue;
            }
            double weight = p.getWeight() == null ? 0.5 : p.getWeight();
            if (UserPreference.SOURCE_QUESTIONNAIRE.equals(p.getSource()) || weight >= UserProfileService.POSITIVE_WEIGHT_MIN) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对候选帖子全量打分排序（个性化分支用；调用方负责分页切片）。
     *
     * @param posts      候选帖子（PUBLISHED，已按城市/类型过滤，全量）
     * @param tagsByPost postId → 帖子画像标签（跨域：风格/口味/节奏/城市；由 PostTagService 批量补齐）
     * @param prefs      用户画像偏好全量
     */
    public List<RankedPost> rank(List<TravelPost> posts, Map<Long, List<PostTag>> tagsByPost,
                                 List<UserPreference> prefs) {
        if (posts == null || posts.isEmpty()) {
            return List.of();
        }
        Map<Long, TravelPost> byId = posts.stream()
                .filter(p -> p != null && p.getId() != null)
                .collect(Collectors.toMap(TravelPost::getId, p -> p, (a, b) -> a));
        List<RankedPost> out = new ArrayList<>();
        for (TravelPost p : byId.values()) {
            List<PostTag> tags = tagsByPost == null ? List.of() : tagsByPost.getOrDefault(p.getId(), List.of());
            ScoreDetail d = PersonalizedScoreCalculator.evaluatePost(
                    tags.stream().map(PostTag::getTag).filter(t -> t != null && !t.isBlank()).toList(),
                    prefs);
            String reason = d.explanation();
            if (reason == null && !d.hardAvoid()) {
                // 兜底文案=未命中任何画像标签时的占位理由，与"热度"无关：
                // 真实热度（popularityOf = 点赞+2×收藏+评论）当前只用于同分破平，
                // 既无阈值也无时间窗与最小样本保护 —— 因此这里绝不能写"热门"，
                // 否则 0 赞 0 收藏的新帖一发布就被打上"社区热门内容"（用户已实测到该误导）。
                // 若将来要做"热门榜"，须先给真实门槛，再在专门的榜单里使用"热门"字样。
                reason = "为你推荐";
            }
            out.add(new RankedPost(p.getId(), d.finalScore(), reason,
                    List.copyOf(d.matchedTags()), !d.matchedTags().isEmpty()));
        }
        // 得分倒序 → 互动热度破平 → 新发在前（Comparator 链保证稳定性）
        out.sort(Comparator.comparingDouble(RankedPost::score).reversed()
                .thenComparing(post -> -popularityOf(byId.get(post.postId())))
                .thenComparing(post -> -publishedEpoch(byId.get(post.postId()))));
        return out;
    }

    /**
     * 曝光日志：把本次返回给登录用户的一页个性化推荐写入 post_feed_log。
     * 仅在 personalized 分支调用（热门/最新不写，避免污染推荐效果统计口径）。
     *
     * @param shown 该页已按位置排好的推荐（位置即下标）
     */
    public void logExposures(String userId, int profileVersion, List<RankedPost> shown) {
        logExposures(userId, profileVersion, null, shown);
    }

    /**
     * 曝光日志（A/B 版本，阶段四任务 6）：额外记录用户所属实验变体
     * （CONTROL/TREATMENT；无实验 → null），供监控按变体对照帖子推荐命中率与反馈率。
     */
    public void logExposures(String userId, int profileVersion, String abVariant,
                             List<RankedPost> shown) {
        logExposures(userId, profileVersion, abVariant, null, shown);
    }

    /**
     * 曝光日志（P1-5 审查报告版本）：feedTraceId 为前端一次页面会话的幂等键。
     * 同一 (user, trace, post) 已写过 → 跳过（页面重试/组件重复渲染/接口重放不会双写曝光，
     * 避免分母被稀释）；trace 为空 = 旧客户端，按原逻辑各落一条。
     */
    public void logExposures(String userId, int profileVersion, String abVariant,
                             String feedTraceId, List<RankedPost> shown) {
        if (userId == null || userId.isBlank() || shown == null || shown.isEmpty()) {
            return;
        }
        int position = 0;
        for (RankedPost rp : shown) {
            if (rp == null || rp.postId() == null) {
                continue;
            }
            if (feedTraceId != null && !feedTraceId.isBlank()
                    && alreadyExposed(userId, feedTraceId, rp.postId())) {
                continue; // 同一次页面会话已曝光过 → 幂等跳过
            }
            try {
                PostFeedLog logRow = new PostFeedLog();
                logRow.setUserId(userId);
                logRow.setPostId(rp.postId());
                logRow.setSort("recommended");
                logRow.setPosition(position++);
                logRow.setFinalScore(rp.score());
                logRow.setHitPreference(rp.hitPreference() ? 1 : 0);
                logRow.setRecommendReason(truncate(rp.reason(), 300));
                logRow.setProfileVersion(profileVersion);
                logRow.setRankingVersion(PersonalizedScoreCalculator.POST_FEED_RANKING_VERSION);
                logRow.setAbVariant(abVariant);
                logRow.setFeedTraceId(feedTraceId);
                postFeedLogRepository.insert(logRow);
            } catch (Exception e) {
                log.debug("帖子曝光日志写入失败（不影响推荐返回）: {}", e.getMessage());
            }
        }
    }

    /** 同一 (user, trace, post) 是否已曝光（P1-5 幂等键去重） */
    private boolean alreadyExposed(String userId, String traceId, Long postId) {
        try {
            Long n = postFeedLogRepository.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PostFeedLog>()
                            .eq(PostFeedLog::getUserId, userId)
                            .eq(PostFeedLog::getFeedTraceId, traceId)
                            .eq(PostFeedLog::getPostId, postId));
            return n != null && n > 0;
        } catch (Exception e) {
            log.debug("帖子曝光判重查询失败（放行写入）: {}", e.getMessage());
            return false;
        }
    }

    /* ================= 破平统计 ================= */

    /** 互动热度（与社区热门口径一致：点赞 + 2×收藏 + 评论） */
    private int popularityOf(TravelPost p) {
        if (p == null) {
            return 0;
        }
        return nvl(p.getLikeCount()) + 2 * nvl(p.getFavoriteCount()) + nvl(p.getCommentCount());
    }

    private long publishedEpoch(TravelPost p) {
        return p == null || p.getPublishedAt() == null ? 0L
                : p.getPublishedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static int nvl(Integer v) {
        return v == null ? 0 : v;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
