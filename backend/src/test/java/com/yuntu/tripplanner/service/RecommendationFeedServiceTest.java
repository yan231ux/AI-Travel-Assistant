package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuntu.tripplanner.client.AmapClient;
import com.yuntu.tripplanner.model.AbExperiment;
import com.yuntu.tripplanner.model.RecommendationFeed;
import com.yuntu.tripplanner.model.RecommendationIntervention;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
    private VisitedService visitedService;
    @Mock
    private AbExperimentService abExperimentService;
    @Mock
    private RecommendationInterventionService interventionService;
    /** 城市白名单 + 归一化：默认原样放行（恒等），避免闸门挡住既有用例；非法/归一化用例单独 stub 覆盖。 */
    @Mock
    private CityValidator cityValidator;

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
                visitedService, abExperimentService, interventionService, cityValidator);
        lenient().when(interventionService.activeNow()).thenReturn(List.of());
        // 闸门 = 归一化 + 校验：默认恒等放行（"上海"→"上海"），非法/归一化场景在各自用例里覆盖
        lenient().when(cityValidator.canonicalCity(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
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

    /** 列表卡片：无真实简介 → description=null（前端隐藏该行），免责声明只留给详情页。
     *  实测深圳等未覆盖攻略的城市，每张卡都显示同一句兜底文案，观感像数据坏了。 */
    @Test
    void feedItem_withoutRealDescription_isNull_notFallbackText() {
        when(spotRepository.selectCount(any())).thenReturn(50L);
        Spot withDesc = spot("外滩", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED);
        withDesc.setDescription("外滩是上海近代城市历史的起点。");
        Spot noDesc = spot("人才公园", "公园;公园", Spot.QUALITY_POI_ONLY);
        noDesc.setDescription(null);
        when(spotRepository.selectList(any())).thenReturn(List.of(withDesc, noDesc));
        when(userProfileService.listPreferences("u1")).thenReturn(List.of());

        var feed = service.feed("u1", "上海", 1, 12, "popular");

        assertEquals("外滩是上海近代城市历史的起点。", feed.getItems().get(0).getDescription(),
                "真实简介原样展示");
        assertNull(feed.getItems().get(1).getDescription(),
                "无简介应返回 null 让前端隐藏整行，不能每张卡都刷同一段兜底免责文案");
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
     * 非法城市闸门：白名单外的城市（错别字/假城市）应 fail-closed —— 不查库、不调高德、
     * 不写库，直接返回空列表并带上 invalidCity + 形近纠错建议。
     */
    @Test
    void unknownCity_isRejectedWithoutTouchingAmapOrDb() {
        when(cityValidator.canonicalCity("火星")).thenReturn(null);
        when(cityValidator.suggestCity("火星")).thenReturn("北京");

        var feed = service.feed("u1", "火星", 1, 12, "popular");

        assertTrue(feed.getInvalidCity());
        assertEquals("北京", feed.getInvalidCitySuggestion());
        assertEquals(0, feed.getTotal());
        assertTrue(feed.getItems().isEmpty());
        // 关键：不触发任何底层调用（否则会污染 spot 表 —— 这正是历史脏数据根因）
        verify(spotRepository, never()).selectList(any());
        verify(spotRepository, never()).selectCount(any());
        verify(amapClient, never()).searchPoi(anyString(), anyString());
    }

    /**
     * 归一化：输入"北京市"应归结到规范名"北京"去读库，绝不能以原始串另起一套 city 键
     * （否则同一座城会有两套 spot 行，还各同步一份高德数据）。
     */
    @Test
    void cityWithAdminSuffix_readsViaCanonicalKey() {
        when(spotRepository.selectCount(any())).thenReturn(50L); // 缓存充足不触发同步
        when(cityValidator.canonicalCity("北京市")).thenReturn("北京");
        Spot s = spot("故宫", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED);
        s.setCity("北京");
        when(spotRepository.selectList(any())).thenReturn(List.of(s));
        when(userProfileService.listPreferences("u1")).thenReturn(List.of());

        var feed = service.feed("u1", "北京市", 1, 12, "popular");

        assertEquals(1, feed.getTotal());
        assertEquals("故宫", feed.getItems().get(0).getName());
        // 归一化后不应因为"北京市"这个未知键而去打高德补数据
        verify(amapClient, never()).searchPoi(anyString(), anyString());
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

    /* ================= §6.3 推荐人工干预：黑名单/置顶/降权/城市精选 ================= */

    private RecommendationIntervention intervention(String spotId, String action, String reason) {
        RecommendationIntervention it = new RecommendationIntervention();
        it.setTargetType(RecommendationIntervention.TARGET_SPOT);
        it.setTargetId(spotId);
        it.setAction(action);
        it.setReason(reason);
        return it;
    }

    private void stubFeed(List<Spot> spots) {
        when(spotRepository.selectCount(any())).thenReturn(50L); // 缓存充足不触发同步
        when(spotRepository.selectList(any())).thenReturn(spots);
        when(userProfileService.listPreferences("u1")).thenReturn(List.of());
    }

    /** 推荐黑名单：候选池直接剔除（total 同步减少），meta 为空 */
    @Test
    void intervention_blacklist_removesFromCandidates() {
        Spot a = spot("外滩", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED);
        Spot b = spot("城隍庙", "风景名胜;风景名胜", Spot.QUALITY_POI_ONLY);
        stubFeed(List.of(a, b));
        lenient().when(interventionService.activeNow()).thenReturn(
                List.of(intervention("spot_上海_外滩", RecommendationIntervention.ACTION_BLACKLIST, "冒烟-黑名单")));

        var feed = service.feed("u1", "上海", 1, 12, "popular");

        assertEquals(1, feed.getTotal());
        assertEquals("城隍庙", feed.getItems().get(0).getName());
        assertTrue(feed.getInterventions().isEmpty());
    }

    /** 置顶：命中景点整体前置 + interventions meta 带 reason（不动算法分） */
    @Test
    void intervention_pin_movesSpotFront_withMeta() {
        Spot a = spot("外滩", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED);
        Spot b = spot("城隍庙", "风景名胜;风景名胜", Spot.QUALITY_POI_ONLY);
        Spot c = spot("人民广场", "风景名胜;风景名胜", Spot.QUALITY_POI_ONLY);
        stubFeed(List.of(a, b, c));
        lenient().when(interventionService.activeNow()).thenReturn(
                List.of(intervention("spot_上海_城隍庙", RecommendationIntervention.ACTION_PIN, "运营周推")));

        var feed = service.feed("u1", "上海", 1, 12, "popular");

        // 置顶不改变算法分：原排序中 b 在 a 后，搬移后 b 在 a 前
        assertEquals("城隍庙", feed.getItems().get(0).getName());
        assertEquals(3, feed.getTotal());
        assertEquals(1, feed.getInterventions().size());
        assertEquals("PIN", feed.getInterventions().get(0).getAction());
        assertEquals("运营周推", feed.getInterventions().get(0).getReason());
        assertEquals("spot_上海_城隍庙", feed.getInterventions().get(0).getSpotId());
    }

    /** 降权：命中景点沉底 + meta 标识 DEMOTE */
    @Test
    void intervention_demote_movesSpotEnd_withMeta() {
        Spot a = spot("外滩", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED);
        Spot b = spot("人民广场", "风景名胜;风景名胜", Spot.QUALITY_POI_ONLY);
        stubFeed(List.of(a, b));
        lenient().when(interventionService.activeNow()).thenReturn(
                List.of(intervention("spot_上海_外滩", RecommendationIntervention.ACTION_DEMOTE, "体验差待复核")));

        var feed = service.feed("u1", "上海", 1, 12, "popular");

        assertEquals("人民广场", feed.getItems().get(0).getName());
        assertEquals("外滩", feed.getItems().get(1).getName());
        assertEquals("DEMOTE", feed.getInterventions().get(0).getAction());
    }

    /** 同一景点同时置顶+降权 → 降权优先（保守语义），meta 只标 DEMOTE */
    @Test
    void intervention_bothPinAndDemote_demoteWins() {
        Spot a = spot("外滩", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED);
        Spot b = spot("城隍庙", "风景名胜;风景名胜", Spot.QUALITY_POI_ONLY);
        stubFeed(List.of(a, b));
        lenient().when(interventionService.activeNow()).thenReturn(List.of(
                intervention("spot_上海_外滩", RecommendationIntervention.ACTION_PIN, "置顶"),
                intervention("spot_上海_外滩", RecommendationIntervention.ACTION_DEMOTE, "降权")));

        var feed = service.feed("u1", "上海", 1, 12, "popular");

        assertEquals("城隍庙", feed.getItems().get(0).getName());
        assertEquals("外滩", feed.getItems().get(1).getName());
        assertEquals("DEMOTE", feed.getInterventions().get(0).getAction());
    }

    /** 城市精选：feed 载荷输出 featured_city 标记与原因（标记类运营，不参与排序） */
    @Test
    void intervention_cityFeatured_setsFeedMeta() {
        Spot a = spot("外滩", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED);
        stubFeed(List.of(a));
        RecommendationIntervention featured = new RecommendationIntervention();
        featured.setTargetType(RecommendationIntervention.TARGET_CITY);
        featured.setTargetId("上海");
        featured.setAction(RecommendationIntervention.ACTION_FEATURED);
        featured.setReason("本周目的地推荐");
        lenient().when(interventionService.activeNow()).thenReturn(List.of(featured));

        var feed = service.feed("u1", "上海", 1, 12, "popular");

        assertTrue(Boolean.TRUE.equals(feed.getFeaturedCity()));
        assertEquals("本周目的地推荐", feed.getFeaturedReason());
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
        AbExperiment spotExp = new AbExperiment();
        spotExp.setExpName("spot_feed_quality_gate");
        spotExp.setFeedType(AbExperiment.FEED_SPOT);
        when(abExperimentService.activeOf(AbExperiment.FEED_SPOT)).thenReturn(spotExp);
        when(abExperimentService.resolveVariant(eq("u1"),
                eq("spot_feed_quality_gate"))).thenReturn("TREATMENT");
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
        AbExperiment spotExp = new AbExperiment();
        spotExp.setExpName("spot_feed_quality_gate");
        spotExp.setFeedType(AbExperiment.FEED_SPOT);
        when(abExperimentService.activeOf(AbExperiment.FEED_SPOT)).thenReturn(spotExp);
        when(abExperimentService.resolveVariant(anyString(), anyString())).thenReturn("TREATMENT");
        when(userProfileService.listPreferences("u1")).thenReturn(List.of());

        var feed = service.feed("u1", "上海", 1, 12, "personalized");

        // 攻略候选仅 1 个 < 6 → 保持全量 9 条（POI_ONLY 仍可见，只是不空流）
        assertEquals(9, feed.getTotal());
    }

    /* ================= P0-1（排查报告）：综合排序分 ≠ 偏好匹配度 ================= */

    /** 无画像 + 兜底理由（popular 攻略质量优先）：任何卡片都不许出现"匹配度"字段与百分比素材 */
    @Test
    void noProfile_anyItem_mustNotExposeMatchFields() {
        when(spotRepository.selectCount(any())).thenReturn(50L);
        Spot poiOnly = spot("普通商场", "购物服务", Spot.QUALITY_POI_ONLY);
        Spot guide = spot("外滩", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED);
        when(spotRepository.selectList(any())).thenReturn(List.of(poiOnly, guide));
        when(userProfileService.listPreferences("u1")).thenReturn(List.of());

        var feed = service.feed("u1", "上海", 1, 12, "personalized"); // 显式 personalized 也无画像 → 降级

        assertFalse(feed.getPersonalized());
        for (var item : feed.getItems()) {
            // score 是内部排序分（0.5 基础分也照常返回），但前端可展示的匹配度三件套必须为空
            assertNull(item.getPersonalized(), item.getName() + " 不应标个性化");
            assertNull(item.getMatchScore(), item.getName() + " 不应有匹配分");
            assertNull(item.getMatchedPreferences(), item.getName() + " 不应有命中标签");
            // 兜底理由不得伪装"匹配你的偏好"；POI_ONLY 也不再说成"高德热门景点"
            assertFalse(item.getRecommendReason().contains("匹配你的偏好"));
            assertFalse(item.getRecommendReason().contains("热门"));
        }
    }

    /** 有画像但个别景点未命中：只有真命中的卡片才带 personalized/match_score/matched_preferences */
    @Test
    void withProfile_onlyHitItemsExposeMatchFields_missStaysNeutral() {
        when(spotRepository.selectCount(any())).thenReturn(50L);
        Spot nature = spot("西湖", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED);
        Spot mall = spot("银泰百货", "购物服务;商场", Spot.QUALITY_POI_ONLY);
        when(spotRepository.selectList(any())).thenReturn(List.of(mall, nature));
        when(userProfileService.listPreferences("u1")).thenReturn(
                List.of(stylePref("自然风景", 0.9)));
        when(userProfileService.getProfileVersion("u1")).thenReturn(3);

        var feed = service.feed("u1", "杭州", 1, 12, "personalized");
        assertTrue(feed.getPersonalized());

        var hit = feed.getItems().get(0); // 命中"自然风景"的西湖排最前
        assertEquals("西湖", hit.getName());
        assertEquals(Boolean.TRUE, hit.getPersonalized());
        assertNotNull(hit.getMatchScore());
        assertTrue(hit.getMatchScore() > 0 && hit.getMatchScore() <= 1);
        assertTrue(hit.getMatchedPreferences().contains("自然风景"));
        assertTrue(hit.getRecommendReason().contains("匹配你的偏好"));

        var miss = feed.getItems().get(1); // 银泰百货未命中 → 中性
        assertEquals("银泰百货", miss.getName());
        assertNull(miss.getPersonalized());
        assertNull(miss.getMatchScore());
        assertNull(miss.getMatchedPreferences());
        assertFalse(miss.getRecommendReason().contains("匹配你的偏好"));
    }

    /** 攻略优先/最近更新排序即使有画像也不暴露个性化字段（前端据此隐藏"匹配度%"） */
    @Test
    void popularOrLatest_withProfile_keepNeutralReasons() {
        when(spotRepository.selectCount(any())).thenReturn(50L);
        Spot nature = spot("西湖", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED);
        when(spotRepository.selectList(any())).thenReturn(List.of(nature));
        when(userProfileService.listPreferences("u1")).thenReturn(
                List.of(stylePref("自然风景", 0.9)));

        // 攻略优先
        var popular = service.feed("u1", "杭州", 1, 12, "popular");
        assertFalse(popular.getPersonalized());
        var pItem = popular.getItems().get(0);
        assertNull(pItem.getPersonalized());
        assertNull(pItem.getMatchScore());
        assertEquals("本地攻略收录的真实景点", pItem.getRecommendReason());

        // 最近更新（latest）
        var latest = service.feed("u1", "杭州", 1, 12, "latest");
        assertFalse(latest.getPersonalized());
        var lItem = latest.getItems().get(0);
        assertNull(lItem.getPersonalized());
        assertNull(lItem.getMatchScore());
        assertFalse(lItem.getRecommendReason().contains("匹配你的偏好"));
    }

    /** P1-2/P2-2 文案：POI_ONLY 兜底不再叫"热门"（改"城市精选"）；已去过不再说"换点新地方" */
    @Test
    void poiOnlyReason_isCityFeatured_notHot() {
        when(spotRepository.selectCount(any())).thenReturn(50L);
        Spot poiOnly = spot("外滩源", "风景名胜;风景名胜", Spot.QUALITY_POI_ONLY);
        when(spotRepository.selectList(any())).thenReturn(List.of(poiOnly));
        when(userProfileService.listPreferences("u1")).thenReturn(List.of());

        var feed = service.feed("u1", "上海", 1, 12, "personalized");
        assertEquals("城市精选", feed.getItems().get(0).getRecommendReason());
    }

    /* ============ 2026-09-09 匹配度粒度修正：visibleMatchScore 覆盖折算 ============ */

    /** 候选单标签且命中 → 覆盖度 1.0，匹配度保持偏好强度（0.9×0.8=0.72），不分档 */
    @Test
    void visibleMatch_singleTagCandidate_keepsStrength() {
        ScoreDetail d = new ScoreDetail(0.72, 1.0, 0.0, 0.9,
                List.of("自然风景"), null, false, false, null);
        assertEquals(0.72, RecommendationFeedService.visibleMatchScore(d, List.of("自然风景")), 1e-9);
        // 候选标签为空（映射缺失，理论上 hit 不会发生）也不除零
        assertEquals(0.72, RecommendationFeedService.visibleMatchScore(d, List.of()), 1e-9);
        assertEquals(0.72, RecommendationFeedService.visibleMatchScore(d, null), 1e-9);
    }

    /** 候选身兼两风格、画像只命中其一 → 折算一半：0.72×0.5=0.36，与"纯粹"候选拉开档位 */
    @Test
    void visibleMatch_multiTagCandidate_partialHit_discounted() {
        ScoreDetail d = new ScoreDetail(0.72, 1.0, 0.0, 0.9,
                List.of("自然风景"), null, false, false, null);
        assertEquals(0.36, RecommendationFeedService.visibleMatchScore(
                d, List.of("自然风景", "城市漫游")), 1e-9);
    }

    /** 候选双风格且画像两条都命中 → 覆盖度 1.0 不减（完全贴合） */
    @Test
    void visibleMatch_multiTagCandidate_fullHit_keepsStrength() {
        ScoreDetail d = new ScoreDetail(1.32, 1.0, 0.0, 1.0,
                List.of("自然风景", "城市漫游"), null, false, false, null);
        assertEquals(1.0, RecommendationFeedService.visibleMatchScore(
                d, List.of("自然风景", "城市漫游")), 1e-9);
    }

    /** 集成语义（"清一色 48%"修复）：同城同画像下，身份更杂的候选匹配度更低，卡片百分比不再全同 */
    @Test
    void personalizedFeed_sameProfile_multiTagCandidate_scoresLowerThanPureOne() {
        when(spotRepository.selectCount(any())).thenReturn(50L);
        // type 分段同时命中"自然风景"与"城市漫游" → styleTags=[自然风景, 城市漫游]
        Spot hybrid = spot("外滩滨江公园", "风景名胜;特色商业街", Spot.QUALITY_GUIDE_MATCHED);
        // type 只命中"自然风景" → styleTags=[自然风景]
        Spot pure = spot("西湖", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED);
        when(spotRepository.selectList(any())).thenReturn(List.of(pure, hybrid));
        when(userProfileService.listPreferences("u1")).thenReturn(
                List.of(stylePref("自然风景", 0.9)));
        when(userProfileService.getProfileVersion("u1")).thenReturn(3);

        var feed = service.feed("u1", "杭州", 1, 12, "personalized");
        assertTrue(feed.getPersonalized());
        var pureItem = feed.getItems().stream().filter(i -> "西湖".equals(i.getName())).findFirst().orElseThrow();
        var hybridItem = feed.getItems().stream().filter(i -> "外滩滨江公园".equals(i.getName())).findFirst().orElseThrow();
        assertNotNull(pureItem.getMatchScore());
        assertNotNull(hybridItem.getMatchScore());
        // 纯身份候选 0.72；身兼两风格的候选折算为 ~0.36 → 同域出现分档，不再是"全城一个数"
        assertEquals(0.72, pureItem.getMatchScore(), 1e-9);
        assertEquals(0.36, hybridItem.getMatchScore(), 1e-9);
        // 两条都亮 🎯（理由不变），只是百分比拉开
        assertTrue(pureItem.getRecommendReason().contains("匹配你的偏好"));
        assertTrue(hybridItem.getRecommendReason().contains("匹配你的偏好"));
    }

    /* ================= B组 景点治理联动（§5） ================= */

    /** B3：下线/合并别名/异常标记（NON_SPOT/CLOSED/ERROR_POI）不进推荐池；OUTDATED 仍可见 */
    @Test
    void governedSpots_areFilteredFromFeed() {
        when(spotRepository.selectCount(any())).thenReturn(50L);
        Spot online = spot("外滩", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED);
        Spot offline = spot("已关闭场馆", "风景名胜;风景名胜", Spot.QUALITY_POI_ONLY);
        offline.setStatus(Spot.STATUS_OFFLINE);
        Spot merged = spot("旧别名", "风景名胜;风景名胜", Spot.QUALITY_POI_ONLY);
        merged.setMergedInto("spot_上海_外滩");
        Spot error = spot("错误点位", "风景名胜;风景名胜", Spot.QUALITY_POI_ONLY);
        error.setFlag(Spot.FLAG_ERROR_POI);
        error.setStatus(Spot.STATUS_ONLINE); // 防御：即便漏改状态，flag 也拦截
        Spot outdated = spot("待核验景点", "风景名胜;风景名胜", Spot.QUALITY_POI_ONLY);
        outdated.setFlag(Spot.FLAG_OUTDATED);
        when(spotRepository.selectList(any())).thenReturn(
                List.of(online, offline, merged, error, outdated));
        when(userProfileService.listPreferences("u1")).thenReturn(List.of());

        var feed = service.feed("u1", "上海", 1, 12, "popular");

        assertEquals(2, feed.getTotal(), "治理对象应从候选池剔除");
        var names = feed.getItems().stream().map(i -> i.getName()).toList();
        assertEquals(List.of("外滩", "待核验景点"), names);
    }

    /** B2：单点重同步只更新「未人工锁定」字段，锁定的 name 不被覆盖 */
    @Test
    void resyncSpot_respectsManualLock() {
        Spot spot = spot("外滩", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED);
        spot.setPoiId("poi_outer");
        spot.setManualOverride(true);
        spot.setManualOverrideFields("name");
        when(spotRepository.selectOne(any())).thenReturn(spot);
        Map<String, Object> poi = new HashMap<>();
        poi.put("name", "外滩（高德新名）");
        poi.put("address", "中山东一路");
        poi.put("type", "风景名胜");
        poi.put("longitude", "121.1");
        poi.put("latitude", "31.2");
        poi.put("poi_id", "poi_outer");
        when(amapClient.searchPoiFresh("上海", "外滩")).thenReturn(List.of(poi));

        int code = service.resyncSpot(spot.getSpotId());

        assertEquals(RecommendationFeedService.SYNC_UPDATED, code);
        ArgumentCaptor<Spot> cap = ArgumentCaptor.forClass(Spot.class);
        verify(spotRepository).updateById(cap.capture());
        assertEquals("外滩", cap.getValue().getName(), "name 被人工锁定，不应被同步覆盖");
        assertEquals("中山东一路", cap.getValue().getAddress(), "未锁定字段应正常刷新");
        assertNotNull(cap.getValue().getLastSyncedAt());
    }

    /** B2：单点重同步同名搜索未命中相同 poi_id → NO_MATCH，不改库 */
    @Test
    void resyncSpot_noMatchingPoi_returnsNoMatch() {
        Spot spot = spot("外滩", "风景名胜;风景名胜", Spot.QUALITY_GUIDE_MATCHED);
        spot.setPoiId("poi_outer");
        when(spotRepository.selectOne(any())).thenReturn(spot);
        when(amapClient.searchPoiFresh("上海", "外滩")).thenReturn(List.of());

        int code = service.resyncSpot(spot.getSpotId());

        assertEquals(RecommendationFeedService.SYNC_NO_MATCH, code);
        verify(spotRepository, never()).updateById(any());
    }

    /** B2：攻略重匹配在描述被人工锁定（或标签锁定）时跳过 RAG 回填 */
    @Test
    void rematchGuide_skippedWhenDescriptionLocked() {
        Spot spot = spot("外滩", "风景名胜;风景名胜", Spot.QUALITY_POI_ONLY);
        spot.setManualOverride(true);
        spot.setManualOverrideFields("description");
        when(spotRepository.selectOne(any())).thenReturn(spot);

        boolean hit = service.rematchGuide(spot.getSpotId());

        assertFalse(hit);
        verify(ragService, never()).findSpotCard(anyString(), anyString());
        verify(spotRepository, never()).updateById(any());
    }

    /** B2：攻略重匹配命中 → 回填简介并升级 GUIDE_MATCHED；OUTDATED 同步清标记 */
    @Test
    void rematchGuide_hit_upgradesQuality_andClearsOutdated() {
        Spot spot = spot("外滩", "风景名胜;风景名胜", Spot.QUALITY_POI_ONLY);
        spot.setFlag(Spot.FLAG_OUTDATED);
        when(spotRepository.selectOne(any())).thenReturn(spot);
        when(ragService.findSpotCard("上海", "外滩")).thenReturn(
                Map.of("intro", "上海地标景观。", "ticket", "免费"));

        boolean hit = service.rematchGuide(spot.getSpotId());

        assertTrue(hit);
        ArgumentCaptor<Spot> cap = ArgumentCaptor.forClass(Spot.class);
        verify(spotRepository).updateById(cap.capture());
        assertEquals(Spot.QUALITY_GUIDE_MATCHED, cap.getValue().getDataQuality());
        assertTrue(cap.getValue().getDescription().contains("上海地标景观"));
        assertNull(cap.getValue().getFlag(), "重匹配成功应清除 OUTDATED");
    }
}
