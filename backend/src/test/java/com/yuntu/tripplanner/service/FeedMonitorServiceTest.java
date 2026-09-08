package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuntu.tripplanner.model.PostFeedLog;
import com.yuntu.tripplanner.model.Spot;
import com.yuntu.tripplanner.model.SpotFeedLog;
import com.yuntu.tripplanner.model.UserBehavior;
import com.yuntu.tripplanner.repository.PostFeedLogRepository;
import com.yuntu.tripplanner.repository.SpotFeedLogRepository;
import com.yuntu.tripplanner.repository.UserBehaviorRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 推荐流监控单测（阶段四任务 7）：
 * 曝光/命中率/均分聚合；按 A/B 变体与攻略质量分组；
 * 反馈漏斗只统计"曝光过"条目的收藏/不感兴趣（未曝光不计数、同用户同条目去重）。
 */
@ExtendWith(MockitoExtension.class)
class FeedMonitorServiceTest {

    @Mock
    private SpotFeedLogRepository spotFeedLogRepository;
    @Mock
    private PostFeedLogRepository postFeedLogRepository;
    @Mock
    private UserBehaviorRepository userBehaviorRepository;

    private FeedMonitorService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SpotFeedLog.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PostFeedLog.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserBehavior.class);
        service = new FeedMonitorService(spotFeedLogRepository, postFeedLogRepository,
                userBehaviorRepository);
    }

    private SpotFeedLog spotLog(String user, String poiId, int hit, String quality,
                                String abVariant, double score) {
        SpotFeedLog l = new SpotFeedLog();
        l.setUserId(user);
        l.setSpotId("spot_上海_" + poiId);
        l.setPoiId(poiId);
        l.setCity("上海");
        l.setSort("recommended");
        l.setPosition(0);
        l.setHitPreference(hit);
        l.setDataQuality(quality);
        l.setAbVariant(abVariant);
        l.setFinalScore(score);
        l.setCreatedAt(LocalDateTime.now());
        return l;
    }

    private PostFeedLog postLog(String user, Long postId, int hit, String abVariant) {
        PostFeedLog l = new PostFeedLog();
        l.setUserId(user);
        l.setPostId(postId);
        l.setSort("recommended");
        l.setPosition(0);
        l.setHitPreference(hit);
        l.setAbVariant(abVariant);
        l.setFinalScore(0.8);
        l.setCreatedAt(LocalDateTime.now());
        return l;
    }

    private UserBehavior behavior(String user, String itemType, String itemId, String action) {
        UserBehavior b = new UserBehavior();
        b.setUserId(user);
        b.setItemType(itemType);
        b.setItemId(itemId);
        b.setActionType(action);
        b.setCreatedAt(LocalDateTime.now());
        return b;
    }

    @Test
    void spotFeed_aggregatesExposuresHitRateQualityAndVariant() {
        when(spotFeedLogRepository.selectList(any())).thenReturn(List.of(
                spotLog("u1", "poi_a", 1, Spot.QUALITY_GUIDE_MATCHED, "CONTROL", 0.9),
                spotLog("u1", "poi_b", 0, Spot.QUALITY_POI_ONLY, "CONTROL", 0.4),
                spotLog("u2", "poi_c", 1, Spot.QUALITY_GUIDE_MATCHED, "TREATMENT", 0.8)));
        when(postFeedLogRepository.selectList(any())).thenReturn(List.of());
        when(userBehaviorRepository.selectList(any())).thenReturn(List.of());

        var report = service.report(7);

        var spot = report.feeds().get(FeedMonitorService.FEED_SPOT);
        assertEquals(3, spot.exposures);
        assertEquals(2, spot.users);
        assertEquals(2, spot.hits);
        assertEquals(200.0 / 3.0, spot.hitRate * 100, 1e-6); // 2/3 命中
        assertEquals(0.7, spot.avgScore, 1e-6);
        assertEquals(2L, spot.byVariant.get("CONTROL"));
        assertEquals(1L, spot.byVariant.get("TREATMENT"));
        assertEquals(2L, spot.byQuality.get(Spot.QUALITY_GUIDE_MATCHED));
        assertEquals(1L, spot.byQuality.get(Spot.QUALITY_POI_ONLY));
    }

    @Test
    void feedbackFunnel_onlyCountsInteractionsOnExposedItems() {
        // u1 曝光 poi_a（收藏算数），u3 对 poi_x 的收藏未曝光过 → 不进漏斗
        when(spotFeedLogRepository.selectList(any())).thenReturn(List.of(
                spotLog("u1", "poi_a", 1, Spot.QUALITY_GUIDE_MATCHED, null, 0.9)));
        when(postFeedLogRepository.selectList(any())).thenReturn(List.of());
        when(userBehaviorRepository.selectList(any())).thenReturn(List.of(
                behavior("u1", UserBehavior.ITEM_TYPE_SPOT, "poi_a", UserBehavior.ACTION_SAVE),
                behavior("u3", UserBehavior.ITEM_TYPE_SPOT, "poi_x", UserBehavior.ACTION_SAVE),
                behavior("u1", UserBehavior.ITEM_TYPE_SPOT, "poi_b", UserBehavior.ACTION_DISLIKE)));

        var spot = service.report(7).feeds().get(FeedMonitorService.FEED_SPOT);

        assertEquals(1L, spot.feedbacks.get("save"));
        assertEquals(0L, spot.feedbacks.get("dislike"));
        assertEquals(100.0, spot.saveRate * 100, 1e-6);
    }

    @Test
    void feedbackFunnel_spotWithoutPoiId_stillAssociatesBySpotId() {
        // P1-6（审查报告）：poi_id 为空但系统 spot_id 稳定存在 → 行为按 spot_id 上报仍能进漏斗，
        // 不会因缺高德 POI ID 而统计失真
        SpotFeedLog noPoi = spotLog("u1", "poi_z", 1, Spot.QUALITY_POI_ONLY, null, 0.9);
        noPoi.setPoiId(null);
        noPoi.setSpotId("spot_大理_manual001");
        when(spotFeedLogRepository.selectList(any())).thenReturn(List.of(noPoi));
        when(postFeedLogRepository.selectList(any())).thenReturn(List.of());
        when(userBehaviorRepository.selectList(any())).thenReturn(List.of(
                behavior("u1", UserBehavior.ITEM_TYPE_SPOT, "spot_大理_manual001", UserBehavior.ACTION_SAVE)));

        var spot = service.report(7).feeds().get(FeedMonitorService.FEED_SPOT);

        assertEquals(1L, spot.feedbacks.get("save"));
        assertEquals(100.0, spot.saveRate * 100, 1e-6);
    }

    @Test
    void postFeed_saveRateUsesPostIdAsBehaviorKey() {
        when(spotFeedLogRepository.selectList(any())).thenReturn(List.of());
        when(postFeedLogRepository.selectList(any())).thenReturn(List.of(
                postLog("u1", 101L, 1, null),
                postLog("u1", 102L, 0, null)));
        // 行为 item_id 为帖子 ID 字符串（与 post 曝光日志同键）
        when(userBehaviorRepository.selectList(any())).thenReturn(List.of(
                behavior("u1", UserBehavior.ITEM_TYPE_POST, "101", UserBehavior.ACTION_SAVE),
                behavior("u1", UserBehavior.ITEM_TYPE_POST, "999", UserBehavior.ACTION_SAVE)));

        var post = service.report(7).feeds().get(FeedMonitorService.FEED_POST);

        assertEquals(2, post.exposures);
        assertEquals(1L, post.feedbacks.get("save"));
        assertEquals(50.0, post.saveRate * 100, 1e-6);
    }

    @Test
    void emptyWindow_reportsZerosNotErrors() {
        when(spotFeedLogRepository.selectList(any())).thenReturn(List.of());
        when(postFeedLogRepository.selectList(any())).thenReturn(List.of());
        when(userBehaviorRepository.selectList(any())).thenReturn(List.of());

        var report = service.report(7);

        assertEquals(0, report.feeds().get(FeedMonitorService.FEED_SPOT).exposures);
        assertEquals(0.0, report.feeds().get(FeedMonitorService.FEED_POST).hitRate, 1e-6);
    }
}
