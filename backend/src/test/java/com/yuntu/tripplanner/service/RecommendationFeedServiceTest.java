package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuntu.tripplanner.client.AmapClient;
import com.yuntu.tripplanner.model.Spot;
import com.yuntu.tripplanner.model.SpotFavorite;
import com.yuntu.tripplanner.model.SpotFeedLog;
import com.yuntu.tripplanner.model.UserPreference;
import com.yuntu.tripplanner.repository.SpotFavoriteRepository;
import com.yuntu.tripplanner.repository.SpotFeedLogRepository;
import com.yuntu.tripplanner.repository.SpotRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * 推荐景点流单测（产品化阶段一）：
 * 无画像 → 降级热门（GUIDE_MATCHED 排前，不返回空列表）；
 * 有 travel_style 画像 → 命中偏好景点置顶且 personalized=true；
 * 景点被收藏 → isCollected 标记正确。
 */
@ExtendWith(MockitoExtension.class)
class RecommendationFeedServiceTest {

    @Mock
    private SpotRepository spotRepository;
    @Mock
    private SpotFavoriteRepository spotFavoriteRepository;
    @Mock
    private SpotFeedLogRepository spotFeedLogRepository;
    @Mock
    private AmapClient amapClient;
    @Mock
    private RagService ragService;
    @Mock
    private UserProfileService userProfileService;
    @Mock
    private TripRecordService tripRecordService;
    @Mock
    private AbExperimentService abExperimentService;

    private RecommendationFeedService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Spot.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SpotFavorite.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SpotFeedLog.class);
        service = new RecommendationFeedService(spotRepository, spotFavoriteRepository,
                spotFeedLogRepository, amapClient, ragService, userProfileService,
                tripRecordService, abExperimentService);
    }

    private Spot spot(String name, String poiType, String quality) {
        Spot s = new Spot();
        s.setSpotId("spot_上海_" + name);
        s.setPoiId("poi_" + name);
        s.setName(name);
        s.setCity("上海");
        s.setCategory(poiType);
        s.setDataQuality(quality);
        s.setLastSyncedAt(LocalDateTime.now()); // 数量已足时默认视为"新鲜"，不触发同步
        return s;
    }

    private UserPreference stylePref(String tag, double weight) {
        UserPreference p = new UserPreference();
        p.setUserId("u1");
        p.setCategory(UserPreference.CATEGORY_TRAVEL_STYLE);
        p.setTag(tag);
        p.setWeight(weight);
        p.setConfidence(0.8);
        p.setSource(UserPreference.SOURCE_QUESTIONNAIRE);
        return p;
    }

    /** 无画像用户：应降级热门（quality 高的排前），personalized=false */
    @Test
    void noProfile_fallsBackToPopular_guideMatchedFirst() {
        when(spotRepository.selectCount(any())).thenReturn(50L); // 缓存充足不触发同步
        Spot poiOnly = spot("普通商场", "购物服务", Spot.QUALITY_POI_ONLY);
        Spot guide = spot("外滩", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED);
        when(spotRepository.selectList(any())).thenReturn(List.of(poiOnly, guide));
        when(userProfileService.listPreferences("u1")).thenReturn(List.of());

        var feed = service.feed("u1", "上海", 1, 12, "popular");

        assertFalse(feed.getPersonalized());
        assertEquals(2, feed.getTotal());
        // 热门降级：GUIDE_MATCHED（外滩）应排在 POI_ONLY（普通商场）前面
        assertEquals("外滩", feed.getItems().get(0).getName());
        // POI_ONLY 的兜底理由不伪造"匹配偏好"
        assertFalse(feed.getItems().get(1).getRecommendReason().contains("匹配你的偏好"));
    }

    /** 有 travel_style 画像：命中偏好景点 personalized=true 且排最前 */
    @Test
    void withStyleProfile_personalizedRanking_hitPrefFirst() {
        when(spotRepository.selectCount(any())).thenReturn(50L);
        Spot nature = spot("西湖", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED);
        Spot mall = spot("银泰百货", "购物服务;商场", Spot.QUALITY_POI_ONLY);
        when(spotRepository.selectList(any())).thenReturn(List.of(mall, nature));
        when(userProfileService.listPreferences("u1")).thenReturn(
                List.of(stylePref("自然风景", 0.9)));
        when(userProfileService.getProfileVersion("u1")).thenReturn(3);

        var feed = service.feed("u1", "杭州", 1, 12, "personalized");

        assertTrue(feed.getPersonalized());
        assertEquals(3, feed.getProfileVersion());
        // 命中"自然风景"偏好的西湖 finalScore 更高 → 排最前
        assertEquals("西湖", feed.getItems().get(0).getName());
        assertTrue(feed.getItems().get(0).getRecommendReason().contains("匹配你的偏好"));
    }

    /** 用户收藏过的景点 → isCollected=true */
    @Test
    void collectedSpot_isMarked() {
        when(spotRepository.selectCount(any())).thenReturn(50L);
        Spot nature = spot("外滩", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED);
        when(spotRepository.selectList(any())).thenReturn(List.of(nature));
        SpotFavorite fav = new SpotFavorite();
        fav.setSpotId("spot_上海_外滩");
        when(spotFavoriteRepository.selectList(any())).thenReturn(List.of(fav));
        when(userProfileService.listPreferences("u1")).thenReturn(List.of());

        var feed = service.feed("u1", "上海", 1, 12, "popular");

        assertTrue(feed.getItems().get(0).getCollected());
    }

    /**
     * Review P2-6：显式 sort=personalized 但用户无 travel_style 画像时，
     * 应降级为攻略质量排序（personalized=false 与排序语义一致），不再按评分排。
     */
    @Test
    void explicitPersonalized_withoutStyleProfile_fallsBackToGuideQualityOrder() {
        when(spotRepository.selectCount(any())).thenReturn(50L); // 缓存充足不触发同步
        Spot poiOnly = spot("普通商场", "购物服务", Spot.QUALITY_POI_ONLY);
        Spot guide = spot("外滩", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED);
        when(spotRepository.selectList(any())).thenReturn(List.of(poiOnly, guide));
        when(userProfileService.listPreferences("u1")).thenReturn(List.of());

        var feed = service.feed("u1", "上海", 1, 12, "personalized");

        assertFalse(feed.getPersonalized());
        // 与 popular 语义一致：GUIDE_MATCHED 排在 POI_ONLY 前
        assertEquals("外滩", feed.getItems().get(0).getName());
    }

    /**
     * Review P2-4：已有 POI 记录在重新同步时也会执行 RAG 增强；
     * 命中攻略卡片 → data_quality 升级为 GUIDE_MATCHED，且整段重建简介（幂等不重复拼接门票）。
     */
    @Test
    void existingPoiOnly_reSynced_hitsGuide_enrichedIdempotently() {
        // 缓存不足（<20）→ 触发按需同步
        when(spotRepository.selectCount(any())).thenReturn(10L);
        Spot existing = spot("外滩", "风景名胜;风景名胜", Spot.QUALITY_POI_ONLY);
        existing.setPoiId("poi_外滩");
        existing.setDescription(null);
        // 同步循环里按 (city, poi_id) 查已存在 → 走"更新 + RAG 增强"分支
        when(spotRepository.selectOne(any())).thenReturn(existing);
        when(spotRepository.selectList(any())).thenReturn(List.of(existing));
        when(amapClient.searchPoi("上海", "景点")).thenReturn(List.of(Map.of(
                "name", "外滩", "poi_id", "poi_外滩", "type", "风景名胜;风景名胜",
                "address", "中山东一路", "image_url", "http://img/1")));
        when(ragService.findSpotCard("上海", "外滩"))
                .thenReturn(Map.of("intro", "外滩位于黄浦江畔", "ticket", "免费开放"));
        when(userProfileService.listPreferences("u1")).thenReturn(List.of());

        // 同一城市连续同步两次：简介应整段重建，不产生 "门票：…；门票：…" 重复拼接
        service.feed("u1", "上海", 1, 12, "popular");
        service.feed("u1", "上海", 1, 12, "popular");

        var captor = ArgumentCaptor.forClass(Spot.class);
        verify(spotRepository, times(2)).updateById(captor.capture());
        Spot updated = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(Spot.QUALITY_GUIDE_MATCHED, updated.getDataQuality());
        assertEquals(Spot.SOURCE_AMAP_AND_RAG, updated.getSource());
        assertNotNull(updated.getDescription());
        assertEquals(1, countOccurrences(updated.getDescription(), "门票："));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /**
     * Review 复查 #4：数量已足（>=20）但全城最近同步时间超过 7 天 → 仍触发重同步
     * （刷新图片/地址/坐标/RAG 简介），避免"满 20 条就永不更新"。
     */
    @Test
    void enoughRows_butStaleOver7Days_triggersReSync() {
        when(spotRepository.selectCount(any())).thenReturn(50L);
        Spot stale = spot("外滩", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED);
        stale.setPoiId("poi_外滩");
        stale.setLastSyncedAt(LocalDateTime.now().minusDays(8)); // 超过 7 天
        // syncDue 的"最新一条"查询与 feed 主查询共用同一 mock：均返回该过期记录
        when(spotRepository.selectList(any())).thenReturn(List.of(stale));
        when(spotRepository.selectOne(any())).thenReturn(stale);
        when(amapClient.searchPoi("上海", "景点")).thenReturn(List.of(Map.of(
                "name", "外滩", "poi_id", "poi_外滩", "type", "风景名胜;风景名胜",
                "address", "中山东一路", "image_url", "http://img/1")));
        when(userProfileService.listPreferences("u1")).thenReturn(List.of());

        service.feed("u1", "上海", 1, 12, "popular");

        // 过期 → 走了高德重同步 → 更新了该行
        var captor = ArgumentCaptor.forClass(Spot.class);
        verify(spotRepository, times(1)).updateById(captor.capture());
        assertNotNull(captor.getValue().getLastSyncedAt());
    }

    /* ================= 阶段四任务 6/7：A/B 质量门 + 曝光日志 ================= */

    /**
     * A/B 处理组（TREATMENT）：候选池只保留有真实攻略的景点；
     * 真正个性化页还会把每张卡片写曝光日志并带上 ab_variant（供监控对照）。
     */
    @Test
    void abTreatment_qualityGate_filtersPoiOnly_andLogsVariantExposure() {
        when(spotRepository.selectCount(any())).thenReturn(50L);
        List<Spot> spots = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            spots.add(spot("攻略景" + i, "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED));
        }
        for (int i = 0; i < 4; i++) {
            spots.add(spot("纯POI" + i, "购物服务;商场", Spot.QUALITY_POI_ONLY));
        }
        when(spotRepository.selectList(any())).thenReturn(spots);
        when(abExperimentService.resolveVariant(eq("u1"),
                eq(AbExperimentService.EXP_SPOT_QUALITY_GATE))).thenReturn("TREATMENT");
        when(userProfileService.listPreferences("u1")).thenReturn(
                List.of(stylePref("自然风景", 0.9)));
        when(userProfileService.getProfileVersion("u1")).thenReturn(3);

        var feed = service.feed("u1", "上海", 1, 12, "personalized");

        // 处理组：10 候选 → 只留 6 个攻略候选；POI_ONLY 全部被剔除
        assertEquals(6, feed.getTotal());
        for (var item : feed.getItems()) {
            assertNotEquals(Spot.QUALITY_POI_ONLY, item.getDataQuality());
        }
        // 个性化曝光日志：每卡一行，带变体与画像版本
        ArgumentCaptor<SpotFeedLog> captor = ArgumentCaptor.forClass(SpotFeedLog.class);
        verify(spotFeedLogRepository, times(6)).insert(captor.capture());
        assertEquals(6, captor.getAllValues().size());
        assertEquals("TREATMENT", captor.getAllValues().get(0).getAbVariant());
        assertEquals(Integer.valueOf(3), captor.getAllValues().get(0).getProfileVersion());
    }

    /* ---------------- P1-5 曝光幂等（feedTraceId 页面会话键） ---------------- */

    /**
     * 同一页面会话 trace 下重复请求推荐流：同 (user, trace, spot) 判重，
     * 第二次不再写入曝光（防重试/重复渲染稀释分母）；幂等键随行落库。
     */
    @Test
    void personalizedFeed_sameTraceRepeatedCall_deduplicatesSpotExposures() {
        when(spotRepository.selectCount(any())).thenReturn(50L);
        when(spotRepository.selectList(any())).thenReturn(List.of(
                spot("外滩", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED),
                spot("豫园", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED)));
        when(userProfileService.listPreferences("u1")).thenReturn(
                List.of(stylePref("自然风景", 0.9)));
        when(userProfileService.getProfileVersion("u1")).thenReturn(3);
        // 判重查询：首次调用两条都未曝光(0,0)；第二次调用两条都已曝光(1,1)
        when(spotFeedLogRepository.selectCount(any())).thenReturn(0L, 0L, 1L, 1L);

        service.feed("u1", "上海", 1, 12, "personalized", "trace-page-1");
        service.feed("u1", "上海", 1, 12, "personalized", "trace-page-1");

        ArgumentCaptor<SpotFeedLog> captor = ArgumentCaptor.forClass(SpotFeedLog.class);
        verify(spotFeedLogRepository, times(2)).insert(captor.capture());
        assertEquals(2, captor.getAllValues().size());
        assertTrue(captor.getAllValues().stream()
                .allMatch(r -> "trace-page-1".equals(r.getFeedTraceId())));
    }

    /** 旧客户端不带 trace → 不做幂等判重，每次请求都落曝光（兼容原口径） */
    @Test
    void personalizedFeed_nullTrace_legacyWritesEveryCall() {
        when(spotRepository.selectCount(any())).thenReturn(50L);
        when(spotRepository.selectList(any())).thenReturn(List.of(
                spot("外滩", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED)));
        when(userProfileService.listPreferences("u1")).thenReturn(
                List.of(stylePref("自然风景", 0.9)));
        when(userProfileService.getProfileVersion("u1")).thenReturn(3);

        service.feed("u1", "上海", 1, 12, "personalized", null);
        service.feed("u1", "上海", 1, 12, "personalized", null);

        verify(spotFeedLogRepository, times(2)).insert(any(SpotFeedLog.class));
        verify(spotFeedLogRepository, times(0)).selectCount(any());
    }

    /** A/B 处理组但攻略候选不足下限（<6）：整体回退全量，保证数据稀疏城市不空流 */
    @Test
    void abTreatment_qualityGate_fallsBackWhenGuidedPoolTooSmall() {
        when(spotRepository.selectCount(any())).thenReturn(50L);
        Spot guide = spot("外滩", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED);
        List<Spot> spots = new ArrayList<>();
        spots.add(guide);
        for (int i = 0; i < 8; i++) {
            spots.add(spot("纯POI" + i, "购物服务;商场", Spot.QUALITY_POI_ONLY));
        }
        when(spotRepository.selectList(any())).thenReturn(spots);
        when(abExperimentService.resolveVariant(anyString(), anyString())).thenReturn("TREATMENT");
        when(userProfileService.listPreferences("u1")).thenReturn(List.of());

        var feed = service.feed("u1", "上海", 1, 12, "personalized");

        // 攻略候选仅 1 个 < 6 → 保持全量 9 条（POI_ONLY 仍可见，只是不空流）
        assertEquals(9, feed.getTotal());
    }
}
