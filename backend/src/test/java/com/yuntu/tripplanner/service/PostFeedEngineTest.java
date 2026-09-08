package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuntu.tripplanner.model.PostFeedLog;
import com.yuntu.tripplanner.model.PostTag;
import com.yuntu.tripplanner.model.TravelPost;
import com.yuntu.tripplanner.model.UserPreference;
import com.yuntu.tripplanner.repository.PostFeedLogRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 帖子推荐引擎单测（阶段三：个性化判定 / 多域打分排序 / 回避沉底 / 曝光日志）。
 * 打分口径 = PersonalizedScoreCalculator#evaluatePost（与景点共用画像行与回避阈值）。
 */
@ExtendWith(MockitoExtension.class)
class PostFeedEngineTest {

    @Mock
    private PostFeedLogRepository postFeedLogRepository;

    private PostFeedEngine engine;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PostFeedLog.class);
        engine = new PostFeedEngine(postFeedLogRepository);
    }

    /* ---------------- 个性化判定（与无画像降级热门一致） ---------------- */

    @Test
    void personalizedOf_trueWhenQuestionnaireOrStrongPositivePref() {
        UserPreference q = pref("u1", UserPreference.SOURCE_QUESTIONNAIRE,
                UserPreference.CATEGORY_TRAVEL_STYLE, "历史文化", 0.9, 0.95);
        UserPreference fb = pref("u1", UserPreference.SOURCE_FEEDBACK,
                UserPreference.CATEGORY_PACE, "轻松", 0.6, 0.6);
        UserPreference weak = pref("u1", UserPreference.SOURCE_FEEDBACK,
                UserPreference.CATEGORY_TRAVEL_STYLE, "购物", 0.2, 0.6);
        UserPreference hotel = pref("u1", UserPreference.SOURCE_QUESTIONNAIRE,
                UserPreference.CATEGORY_HOTEL, "舒适型", 0.9, 0.95);
        assertTrue(engine.personalizedOf(List.of(q)));
        assertTrue(engine.personalizedOf(List.of(fb)));
        // 只有回避信号或无关域画像 → 不做假个性化
        assertFalse(engine.personalizedOf(List.of(weak)));
        assertFalse(engine.personalizedOf(List.of(hotel)));
        assertFalse(engine.personalizedOf(List.of()));
        assertFalse(engine.personalizedOf(null));
    }

    /* ---------------- 多域打分排序 ---------------- */

    @Test
    void rank_prefersPostMatchingProfile_andExplainsReason() {
        List<UserPreference> prefs = List.of(
                pref("u1", UserPreference.SOURCE_QUESTIONNAIRE,
                        UserPreference.CATEGORY_TRAVEL_STYLE, "历史文化", 0.9, 0.95),
                pref("u1", UserPreference.SOURCE_FEEDBACK,
                        UserPreference.CATEGORY_PACE, "轻松", 0.6, 0.6));

        TravelPost history = post(1L, "大理古镇两日慢游");
        TravelPost nature = post(2L, "三亚看海流水账");
        Map<Long, List<PostTag>> tags = new HashMap<>();
        tags.put(1L, List.of(tag(1L, "travel_style", "历史文化"), tag(1L, "pace", "轻松"), tag(1L, "city", "大理")));
        tags.put(2L, List.of(tag(2L, "travel_style", "自然风景"), tag(2L, "city", "三亚")));

        List<PostFeedEngine.RankedPost> ranked = engine.rank(List.of(nature, history), tags, prefs);
        assertEquals(2, ranked.size());
        // 命中历史文化+轻松的帖子排第一，且给出可解释理由
        assertEquals(1L, ranked.get(0).postId());
        assertTrue(ranked.get(0).score() > ranked.get(1).score());
        assertTrue(ranked.get(0).reason().contains("匹配你的偏好"));
        assertTrue(ranked.get(0).hitPreference());
        // 未命中帖子仍可展示（降级文案，不返回空）
        assertEquals(2L, ranked.get(1).postId());
        assertNotNull(ranked.get(1).reason());
    }

    @Test
    void rank_dislikedTagPostSinksToZero() {
        // 用户对"购物"连续不感兴趣 → 权重跌破阈值（0.2 回避信号行）
        List<UserPreference> prefs = List.of(
                pref("u1", UserPreference.SOURCE_FEEDBACK,
                        UserPreference.CATEGORY_TRAVEL_STYLE, "购物", 0.2, 0.6));

        TravelPost shopping = post(3L, "免税店买买买攻略");
        TravelPost nature = post(4L, "森林公园徒步指南");
        Map<Long, List<PostTag>> tags = new HashMap<>();
        tags.put(3L, List.of(tag(3L, "travel_style", "购物")));
        tags.put(4L, List.of(tag(4L, "travel_style", "户外运动")));

        List<PostFeedEngine.RankedPost> ranked = engine.rank(List.of(shopping, nature), tags, prefs);
        assertEquals(2, ranked.size());
        // 命中回避标签 → 0 分沉底，且理由说明
        PostFeedEngine.RankedPost avoided = ranked.stream()
                .filter(r -> r.postId() == 3L).findFirst().orElseThrow();
        assertEquals(0.0, avoided.score());
        assertTrue(avoided.reason().contains("不感兴趣"));
    }

    /* ---------------- 曝光证据日志（阶段三任务 5/8） ---------------- */

    @Test
    void logExposures_writesPositionScoreHitAndVersions() {
        List<PostFeedEngine.RankedPost> shown = List.of(
                new PostFeedEngine.RankedPost(1L, 0.86, "匹配你的偏好：历史文化", List.of("历史文化"), true),
                new PostFeedEngine.RankedPost(2L, 0.5, "社区热门内容", List.of(), false));

        engine.logExposures("u1", 7, shown);

        ArgumentCaptor<PostFeedLog> captor = ArgumentCaptor.forClass(PostFeedLog.class);
        verify(postFeedLogRepository, times(2)).insert(captor.capture());
        List<PostFeedLog> rows = captor.getAllValues();
        assertEquals(1L, rows.get(0).getPostId());
        assertEquals(0, rows.get(0).getPosition());
        assertEquals(0.86, rows.get(0).getFinalScore(), 1e-9);
        assertEquals(1, rows.get(0).getHitPreference());
        assertEquals("匹配你的偏好：历史文化", rows.get(0).getRecommendReason());
        assertEquals(7, rows.get(0).getProfileVersion());
        assertEquals(PersonalizedScoreCalculator.POST_FEED_RANKING_VERSION,
                rows.get(0).getRankingVersion());
        assertEquals(1, rows.get(1).getPosition());
        assertEquals(0, rows.get(1).getHitPreference());
    }

    @Test
    void logExposures_anonymous_skips() {
        engine.logExposures(null, 0, List.of(new PostFeedEngine.RankedPost(1L, 0.5, "r", List.of(), false)));
        engine.logExposures("u1", 0, List.of());
        verify(postFeedLogRepository, times(0)).insert(any());
    }

    /* ---------------- P1-5 曝光幂等（feedTraceId 页面会话键） ---------------- */

    @Test
    void logExposures_sameTraceRepeatedCall_deduplicatesInserts() {
        List<PostFeedEngine.RankedPost> shown = List.of(
                new PostFeedEngine.RankedPost(1L, 0.8, "r1", List.of(), true),
                new PostFeedEngine.RankedPost(2L, 0.6, "r2", List.of(), false));
        // 首次调用判重全部未曝光(0,0) → 落 2 条；第二次同 trace 判重已曝光(1,1) → 幂等跳过
        when(postFeedLogRepository.selectCount(any())).thenReturn(0L, 0L, 1L, 1L);

        engine.logExposures("u1", 3, null, "trace-page-1", shown);
        engine.logExposures("u1", 3, null, "trace-page-1", shown);

        ArgumentCaptor<PostFeedLog> captor = ArgumentCaptor.forClass(PostFeedLog.class);
        verify(postFeedLogRepository, times(2)).insert(captor.capture());
        assertEquals(2, captor.getAllValues().size());
        // 幂等键随曝光行落库，供 (user, trace, post) 判重
        assertTrue(captor.getAllValues().stream()
                .allMatch(r -> "trace-page-1".equals(r.getFeedTraceId())));
        assertEquals(1L, captor.getAllValues().get(0).getPostId());
        assertEquals(2L, captor.getAllValues().get(1).getPostId());
    }

    @Test
    void logExposures_nullTrace_legacyWritesEveryCall() {
        List<PostFeedEngine.RankedPost> shown = List.of(
                new PostFeedEngine.RankedPost(1L, 0.8, "r1", List.of(), true));

        engine.logExposures("u1", 3, null, null, shown);
        engine.logExposures("u1", 3, null, null, shown);

        // 旧客户端不带 trace → 不做判重，每次曝光各落一条（兼容原口径）
        verify(postFeedLogRepository, times(2)).insert(any(PostFeedLog.class));
        verify(postFeedLogRepository, times(0)).selectCount(any());
    }

    /* ---------------- 工具 ---------------- */

    private UserPreference pref(String user, String source, String category, String tag,
                                double weight, double conf) {
        UserPreference p = new UserPreference();
        p.setUserId(user);
        p.setSource(source);
        p.setCategory(category);
        p.setTag(tag);
        p.setWeight(weight);
        p.setConfidence(conf);
        return p;
    }

    private TravelPost post(Long id, String title) {
        TravelPost p = new TravelPost();
        p.setId(id);
        p.setTitle(title);
        p.setStatus(TravelPost.STATUS_PUBLISHED);
        p.setPublishedAt(LocalDateTime.now());
        p.setLikeCount(0);
        p.setFavoriteCount(0);
        p.setCommentCount(0);
        return p;
    }

    private PostTag tag(Long postId, String category, String tag) {
        PostTag t = new PostTag();
        t.setPostId(postId);
        t.setCategory(category);
        t.setTag(tag);
        return t;
    }
}
