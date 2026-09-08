package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuntu.tripplanner.model.CandidateEvidence;
import com.yuntu.tripplanner.model.DayPlan;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.PostFeedLog;
import com.yuntu.tripplanner.model.RecommendationLog;
import com.yuntu.tripplanner.model.SpotItem;
import com.yuntu.tripplanner.model.UserBehavior;
import com.yuntu.tripplanner.model.UserPreference;
import com.yuntu.tripplanner.repository.PostFeedLogRepository;
import com.yuntu.tripplanner.repository.RecommendationLogRepository;
import com.yuntu.tripplanner.repository.UserBehaviorRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 推荐日志与推荐理由单测（个性化阶段四）：
 * 命中偏好 → 回填 personalNote 并落 hit=1 日志 / 未命中 → 只落日志不写徽标 /
 * 无画像或无偏好 → 跳过 / 统计：偏好命中率、负反馈率、满意度均值。
 */
@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock
    private UserProfileService userProfileService;
    @Mock
    private RecommendationLogRepository recommendationLogRepository;
    @Mock
    private UserBehaviorRepository userBehaviorRepository;
    @Mock
    private PostFeedLogRepository postFeedLogRepository;

    private RecommendationService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), RecommendationLog.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserBehavior.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PostFeedLog.class);
        service = new RecommendationService(userProfileService, recommendationLogRepository,
                userBehaviorRepository, postFeedLogRepository);
    }

    private Itinerary tripWithSpots(SpotItem... spots) {
        Itinerary it = new Itinerary();
        it.setTripId("trip-1");
        DayPlan day = new DayPlan();
        day.setDayIndex(1);
        day.setSpots(List.of(spots));
        it.setDays(List.of(day));
        return it;
    }

    private SpotItem spot(String name, String poiType) {
        SpotItem s = new SpotItem();
        s.setName(name);
        s.setPoiType(poiType);
        return s;
    }

    @Test
    void evidenceMatchedByPoiId_reusesRealScoreWhenNameDiffers() {
        // P0②（OPTIMIZATION_TODO）：校验层替换后最终景点名可能与候选证据不同，
        // 但 poi_id 相同 → 推荐日志必须仍复用候选阶段的真实得分，而不是按名称重算成 1.0/0.0 标签
        when(userProfileService.listPreferences("user-1")).thenReturn(List.of(
                stylePref("历史文化", 0.9, UserPreference.SOURCE_QUESTIONNAIRE)));

        SpotItem finalSpot = spot("故宫博物院", "科教文化;博物馆");
        finalSpot.setPoiId("B0FFH1234");
        Itinerary it = tripWithSpots(finalSpot);

        CandidateEvidence ev = new CandidateEvidence();
        ev.setBucket(CandidateEvidence.BUCKET_SPOT);
        ev.setItemName("故宫博物院（午门入口）"); // 与最终景点名不同，但同 poi_id
        ev.setPoiId("B0FFH1234");
        ev.setPreferenceScore(0.81);
        ev.setNoveltyScore(1.0);
        ev.setDistancePenalty(0.0);
        ev.setFinalScore(0.75);
        ev.setMatchedTags("历史文化");
        ev.setHardAvoid(0);
        ev.setVisited(0);

        int count = service.logTripRecommendations("user-1", it, List.of(ev));

        assertEquals(1, count);
        assertEquals("匹配你的偏好：历史文化", it.getDays().get(0).getSpots().get(0).getPersonalNote(),
                "poi_id 命中候选证据 → 复用其真实得分回填理由");

        ArgumentCaptor<RecommendationLog> captor = ArgumentCaptor.forClass(RecommendationLog.class);
        verify(recommendationLogRepository).insert(captor.capture());
        assertEquals(0.81, captor.getValue().getPreferenceScore(), 0.001, "preference 复用候选证据分");
        assertEquals(0.75, captor.getValue().getFinalScore(), 0.001, "final 复用候选证据分");
    }

    private UserPreference stylePref(String tag, double weight, String source) {
        UserPreference p = new UserPreference();
        p.setUserId("user-1");
        p.setCategory(UserPreference.CATEGORY_TRAVEL_STYLE);
        p.setTag(tag);
        p.setWeight(weight);
        p.setConfidence(0.9);
        p.setSource(source);
        return p;
    }

    @Test
    void matchedSpot_backfillsPersonalNoteAndLogsHit() {
        when(userProfileService.listPreferences("user-1")).thenReturn(List.of(
                stylePref("历史文化", 0.9, UserPreference.SOURCE_QUESTIONNAIRE)));

        Itinerary it = tripWithSpots(spot("故宫博物院", "科教文化;博物馆"));

        int count = service.logTripRecommendations("user-1", it);

        assertEquals(1, count);
        assertEquals("匹配你的偏好：历史文化", it.getDays().get(0).getSpots().get(0).getPersonalNote(),
                "命中偏好需回填可读推荐理由");

        ArgumentCaptor<RecommendationLog> captor = ArgumentCaptor.forClass(RecommendationLog.class);
        verify(recommendationLogRepository).insert(captor.capture());
        RecommendationLog logRow = captor.getValue();
        assertEquals("故宫博物院", logRow.getItemName());
        assertEquals(1, logRow.getHitPreference());
        assertNotNull(logRow.getExplanation());
        assertEquals("trip-1", logRow.getTripId());
        assertEquals("user-1", logRow.getUserId());
        // 0.9 * 0.9 = 0.81
        assertEquals(0.81, logRow.getPreferenceScore(), 0.001);
    }

    @Test
    void unmatchedSpot_logsWithoutPersonalNote() {
        // 用户偏好自然风景，但行程安排的是购物中心 → 不命中：不写徽标，日志 hit=0
        when(userProfileService.listPreferences("user-1")).thenReturn(List.of(
                stylePref("自然风景", 0.9, UserPreference.SOURCE_QUESTIONNAIRE)));

        Itinerary it = tripWithSpots(spot("星光购物中心", "购物服务;商场"));

        int count = service.logTripRecommendations("user-1", it);

        assertEquals(1, count);
        assertNull(it.getDays().get(0).getSpots().get(0).getPersonalNote(), "未命中不显示推荐理由");

        ArgumentCaptor<RecommendationLog> captor = ArgumentCaptor.forClass(RecommendationLog.class);
        verify(recommendationLogRepository).insert(captor.capture());
        assertEquals(0, captor.getValue().getHitPreference());
    }

    @Test
    void noUserIdOrNoStylePrefs_skips() {
        // 用户只有节奏偏好（pace），没有景点域偏好 → 无推荐理由可写，跳过
        UserPreference pacePref = new UserPreference();
        pacePref.setUserId("user-1");
        pacePref.setCategory(UserPreference.CATEGORY_PACE);
        pacePref.setTag("轻松");
        pacePref.setWeight(0.9);
        pacePref.setConfidence(0.9);
        pacePref.setSource(UserPreference.SOURCE_QUESTIONNAIRE);
        when(userProfileService.listPreferences("user-1")).thenReturn(List.of(pacePref));
        Itinerary it = tripWithSpots(spot("故宫博物院", "科教文化;博物馆"));

        assertEquals(0, service.logTripRecommendations("user-1", it), "无景点域偏好 → 跳过");
        assertEquals(0, service.logTripRecommendations(null, it), "无 userId → 跳过");
        verify(recommendationLogRepository, never()).insert(any());
    }

    @Test
    void lowWeightFeedbackTag_notCountedAsHit() {
        // 行为反馈压低的回避标签（w0.2 非问卷）命中 → 不 bump 分、不算正命中
        when(userProfileService.listPreferences("user-1")).thenReturn(List.of(
                stylePref("购物", 0.2, UserPreference.SOURCE_FEEDBACK)));

        Itinerary it = tripWithSpots(spot("星光购物中心", "购物服务;商场"));

        service.logTripRecommendations("user-1", it);

        ArgumentCaptor<RecommendationLog> captor = ArgumentCaptor.forClass(RecommendationLog.class);
        verify(recommendationLogRepository).insert(captor.capture());
        assertEquals(0, captor.getValue().getHitPreference(), "回避标签不算偏好命中");
    }

    @Test
    void collectStats_computesHitRateDislikeRateAndAvgRating() {
        // recommendation_log：2 条总、1 条命中 → 命中率 50%
        when(recommendationLogRepository.selectCount(any())).thenReturn(2L, 1L);
        // user_behavior：SAVE/DISLIKE/DISLIKE/RATE(4)/RATE(2) → 负反馈率 40%、满意度 3.0
        when(userBehaviorRepository.selectList(any())).thenReturn(List.of(
                behavior(UserBehavior.ACTION_SAVE),
                behavior(UserBehavior.ACTION_DISLIKE),
                behavior(UserBehavior.ACTION_DISLIKE),
                rated(4),
                rated(2)));

        RecommendationService.ProfileStats stats = service.collectStats("user-1");

        assertEquals(2, stats.getRecommendationCount());
        assertEquals(50.0, stats.getPreferenceHitRate(), 0.001, "命中率 = 1/2");
        assertEquals(40.0, stats.getDislikeRate(), 0.001, "负反馈率 = 2/5");
        assertEquals(3.0, stats.getAvgRating(), 0.001, "满意度均值 = (4+2)/2");
    }

    @Test
    void collectStats_countsPostRecommendationRates() {
        // 帖子推荐曝光 10 条；POST 行为：点击 4 / 收藏 2 / 不感兴趣 3 → 各率以曝光为分母
        when(postFeedLogRepository.selectCount(any())).thenReturn(10L);
        when(userBehaviorRepository.selectList(any())).thenReturn(List.of(
                postBehavior(UserBehavior.ACTION_CLICK),
                postBehavior(UserBehavior.ACTION_CLICK),
                postBehavior(UserBehavior.ACTION_CLICK),
                postBehavior(UserBehavior.ACTION_CLICK),
                postBehavior(UserBehavior.ACTION_SAVE),
                postBehavior(UserBehavior.ACTION_SAVE),
                postBehavior(UserBehavior.ACTION_DISLIKE),
                postBehavior(UserBehavior.ACTION_DISLIKE),
                postBehavior(UserBehavior.ACTION_DISLIKE),
                behavior(UserBehavior.ACTION_SAVE)));

        RecommendationService.ProfileStats stats = service.collectStats("user-1");

        assertEquals(10, stats.getPostExposureCount());
        assertEquals(40.0, stats.getPostClickRate(), 0.001, "帖子点击率 = 4/10");
        assertEquals(20.0, stats.getPostFavoriteRate(), 0.001, "帖子收藏率 = 2/10");
        assertEquals(30.0, stats.getPostDislikeRate(), 0.001, "帖子负反馈率 = 3/10");
    }

    private UserBehavior behavior(String action) {
        UserBehavior b = new UserBehavior();
        b.setUserId("user-1");
        b.setActionType(action);
        return b;
    }

    private UserBehavior postBehavior(String action) {
        UserBehavior b = behavior(action);
        b.setItemType(UserBehavior.ITEM_TYPE_POST);
        return b;
    }

    private UserBehavior rated(int rating) {
        UserBehavior b = behavior(UserBehavior.ACTION_RATE);
        b.setRating(rating);
        return b;
    }
}
