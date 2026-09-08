package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuntu.tripplanner.model.BehaviorRequest;
import com.yuntu.tripplanner.model.DayPlan;
import com.yuntu.tripplanner.model.HotelItem;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.MealItem;
import com.yuntu.tripplanner.model.QuestionnaireRequest;
import com.yuntu.tripplanner.model.PreferenceAdjustment;
import com.yuntu.tripplanner.model.TripRecord;
import com.yuntu.tripplanner.model.TripRequest;
import com.yuntu.tripplanner.model.UserBehavior;
import com.yuntu.tripplanner.model.UserPreference;
import com.yuntu.tripplanner.model.UserProfile;
import com.yuntu.tripplanner.repository.UserBehaviorRepository;
import com.yuntu.tripplanner.repository.UserPreferenceRepository;
import com.yuntu.tripplanner.repository.UserProfileRepository;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结构化画像 + 行为反馈单测（个性化阶段一/二）：
 * 无历史不注入 / 有历史首次访问自动推断建档（HISTORY_INFER 明细）/
 * 问卷显式偏好以最高权重置信度落库并覆盖主档 /
 * 行为反馈画像增量更新（SAVE 提权、DISLIKE 建负反馈行、问卷行受保护、RATE 仅留痕）/
 * 画像文本含行为反馈来源标注与"近期不感兴趣"回避信号。
 */
@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    @Mock
    private TripRecordService tripRecordService;
    @Mock
    private UserProfileRepository userProfileRepository;
    @Mock
    private UserPreferenceRepository userPreferenceRepository;
    @Mock
    private UserBehaviorRepository userBehaviorRepository;
    @Mock
    private PostTagService postTagService;

    private UserProfileService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserProfile.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserPreference.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserBehavior.class);
        service = new UserProfileService(tripRecordService, userProfileRepository,
                userPreferenceRepository, userBehaviorRepository, postTagService);
    }

    private TripRecord trip(String dest, String summary, String hotelLevel, String mealName, double budget) {
        TripRecord r = new TripRecord();
        r.setTripId("t-" + dest);
        r.setDestination(dest);
        Itinerary it = new Itinerary();
        it.setSummary(summary);
        it.setEstimatedBudget(budget);
        DayPlan day = new DayPlan();
        day.setDayIndex(1);
        HotelItem hotel = new HotelItem();
        hotel.setLevel(hotelLevel);
        day.setHotel(hotel);
        MealItem meal = new MealItem();
        meal.setName(mealName);
        day.setMeals(List.of(meal));
        it.setDays(List.of(day));
        r.setItinerary(it);
        return r;
    }

    @Test
    void noHistoryNoPreferences_returnsNullMemory() {
        when(tripRecordService.getRecentTrips(anyString(), anyInt())).thenReturn(List.of());
        when(userPreferenceRepository.selectList(any())).thenReturn(List.of());

        String memory = service.buildMemoryText("user-1", new TripRequest());

        assertNull(memory, "无历史且无偏好 → 不注入画像");
        verify(userProfileRepository).insert(any(UserProfile.class));
    }

    @Test
    void historyOnly_firstVisitInfersStructuredProfile() {
        TripRecord beijing = trip("北京", "轻松三日的城市漫游", "舒适型", "老北京火锅", 3000);
        TripRecord dali = trip("大理", "洱海边的慢生活", "舒适型", "大理砂锅鱼", 2500);
        when(tripRecordService.getRecentTrips(anyString(), anyInt()))
                .thenReturn(List.of(beijing, dali));
        when(userPreferenceRepository.selectList(any())).thenReturn(List.of());

        String memory = service.buildMemoryText("user-1", new TripRequest());

        assertNotNull(memory);
        assertTrue(memory.contains("曾去过"), "画像文本需含历史城市");
        assertTrue(memory.contains("北京"), "需含推断出的去过城市");
        assertTrue(memory.contains("最近行程"), "需含最近行程摘要");

        // 主档落库：城市/节奏(轻松)/住宿(舒适型)/口味(火锅)/预算均值
        ArgumentCaptor<UserProfile> profileCaptor = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileRepository).insert(profileCaptor.capture());
        UserProfile saved = profileCaptor.getValue();
        assertEquals("北京,大理", saved.getVisitedCities());
        assertEquals("轻松", saved.getPacePreference());
        assertEquals("舒适型", saved.getHotelPreference());
        assertEquals("火锅", saved.getFoodPreferences());
        assertEquals(2750.0, saved.getBudgetPreference(), 0.01);
        assertEquals(2, saved.getTripCount());

        // 推断明细（HISTORY_INFER，中等权重）
        ArgumentCaptor<UserPreference> prefCaptor = ArgumentCaptor.forClass(UserPreference.class);
        verify(userPreferenceRepository, atLeastOnce()).insert(prefCaptor.capture());
        boolean hasInferPace = prefCaptor.getAllValues().stream().anyMatch(p ->
                UserPreference.SOURCE_HISTORY_INFER.equals(p.getSource())
                        && "轻松".equals(p.getTag()));
        assertTrue(hasInferPace, "需写入节奏推断明细");
    }

    @Test
    void questionnaire_overridesProfileWithHighestTrust() {
        when(tripRecordService.getRecentTrips(anyString(), anyInt())).thenReturn(List.of());

        QuestionnaireRequest q = new QuestionnaireRequest();
        q.setTravelStyles(List.of("自然风景", "历史文化"));
        q.setPace("紧凑");
        q.setFoodPreferences(List.of("海鲜"));
        q.setDietaryRestrictions(List.of("少辣"));
        service.applyQuestionnaire("user-1", q);

        // 明细：QUESTIONNAIRE 权重 0.9 / 置信度 0.95
        ArgumentCaptor<UserPreference> prefCaptor = ArgumentCaptor.forClass(UserPreference.class);
        verify(userPreferenceRepository, atLeastOnce()).insert(prefCaptor.capture());
        List<UserPreference> inserted = prefCaptor.getAllValues();
        assertEquals(5, inserted.size(), "2风格+1节奏+1口味+1忌口");
        for (UserPreference p : inserted) {
            assertEquals(UserPreference.SOURCE_QUESTIONNAIRE, p.getSource());
            assertEquals(0.90, p.getWeight(), 0.001);
            assertEquals(0.95, p.getConfidence(), 0.001);
        }
        assertTrue(inserted.stream().anyMatch(p ->
                UserPreference.CATEGORY_TRAVEL_STYLE.equals(p.getCategory()) && "自然风景".equals(p.getTag())));

        // 主档：问卷覆盖显式域 + 版本自增 + 标记已填问卷
        ArgumentCaptor<UserProfile> profileCaptor = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileRepository).updateById(profileCaptor.capture());
        UserProfile updated = profileCaptor.getValue();
        assertEquals("自然风景,历史文化", updated.getTravelStyles());
        assertEquals("紧凑", updated.getPacePreference());
        assertEquals("海鲜", updated.getFoodPreferences());
        assertEquals(1, updated.getFilledFromQuestionnaire());
        assertEquals(2, updated.getProfileVersion());
    }

    @Test
    void memoryText_annotatesSources() {
        // 主档已存在且含问卷偏好 + 历史推断偏好 → 注入文本分来源标注
        when(userProfileRepository.selectOne(any())).thenReturn(profileRow());
        when(userPreferenceRepository.selectList(any())).thenReturn(
                List.of(prefRow(UserPreference.CATEGORY_PACE, "轻松", UserPreference.SOURCE_QUESTIONNAIRE),
                        prefRow(UserPreference.CATEGORY_FOOD, "火锅", UserPreference.SOURCE_HISTORY_INFER)));
        when(tripRecordService.getRecentTrips(anyString(), anyInt())).thenReturn(List.of());

        String memory = service.buildMemoryText("user-1", new TripRequest());

        assertNotNull(memory);
        assertTrue(memory.contains("[主动选择]"), "问卷偏好需标注来源");
        assertTrue(memory.contains("[历史推断]"), "推断偏好需标注来源");
    }

    /* ================= 行为反馈（阶段二） ================= */

    @Test
    void saveBehavior_raisesHistoryInferWeight() {
        // 历史推断出的 历史文化 w0.5；收藏故宫（科教文化业态）→ +0.15 到 0.65，来源保持推断
        UserPreference historyPref = new UserPreference();
        historyPref.setUserId("user-1");
        historyPref.setCategory(UserPreference.CATEGORY_TRAVEL_STYLE);
        historyPref.setTag("历史文化");
        historyPref.setWeight(0.5);
        historyPref.setConfidence(0.5);
        historyPref.setSource(UserPreference.SOURCE_HISTORY_INFER);
        when(userPreferenceRepository.selectOne(any())).thenReturn(historyPref);

        List<PreferenceAdjustment> adjustments = service.recordBehavior("user-1",
                behavior(UserBehavior.ACTION_SAVE, UserBehavior.ITEM_TYPE_SPOT,
                        "故宫博物院", "科教文化;博物馆", "trip-1"));

        ArgumentCaptor<UserPreference> prefCaptor = ArgumentCaptor.forClass(UserPreference.class);
        verify(userPreferenceRepository).updateById(prefCaptor.capture());
        assertEquals(0.65, prefCaptor.getValue().getWeight(), 0.001, "收藏 +0.15");
        verify(userBehaviorRepository).insert(any(UserBehavior.class));

        assertEquals(1, adjustments.size());
        assertEquals(0.15, adjustments.get(0).getDelta(), 0.001);
        assertEquals(0.65, adjustments.get(0).getWeight(), 0.001);
        assertTrue(!adjustments.get(0).isProtectedRow());
    }

    @Test
    void dislikeBehavior_protectsQuestionnaireChoice() {
        // 问卷主动选择 历史文化（w0.9）；对某个历史景点点不感兴趣 → 不抹除显式选择（留痕仍在）
        when(userPreferenceRepository.selectOne(any())).thenReturn(
                prefRow(UserPreference.CATEGORY_TRAVEL_STYLE, "历史文化", UserPreference.SOURCE_QUESTIONNAIRE));

        List<PreferenceAdjustment> adjustments = service.recordBehavior("user-1",
                behavior(UserBehavior.ACTION_DISLIKE, UserBehavior.ITEM_TYPE_SPOT,
                        "某博物馆", "科教文化;博物馆", "trip-1"));

        verify(userPreferenceRepository, never()).updateById(any(UserPreference.class));
        verify(userBehaviorRepository).insert(any(UserBehavior.class));
        assertEquals(1, adjustments.size());
        assertTrue(adjustments.get(0).isProtectedRow(), "问卷行受负反馈保护");
        assertEquals(0.9, adjustments.get(0).getWeight(), 0.001);
        assertEquals(0.0, adjustments.get(0).getDelta(), 0.001);
    }

    @Test
    void dislikeNewTag_createsLowWeightFeedbackRow() {
        // 无既有行：DISLIKE 购物类地点 → 新建 FEEDBACK 行 w=0.5-0.3=0.2（后续转"近期不感兴趣"回避信号）
        when(userPreferenceRepository.selectOne(any())).thenReturn(null);

        List<PreferenceAdjustment> adjustments = service.recordBehavior("user-1",
                behavior(UserBehavior.ACTION_DISLIKE, UserBehavior.ITEM_TYPE_SPOT,
                        "星光购物中心", "购物服务;商场", "trip-1"));

        ArgumentCaptor<UserPreference> prefCaptor = ArgumentCaptor.forClass(UserPreference.class);
        verify(userPreferenceRepository).insert(prefCaptor.capture());
        UserPreference created = prefCaptor.getValue();
        assertEquals(UserPreference.CATEGORY_TRAVEL_STYLE, created.getCategory());
        assertEquals("购物", created.getTag());
        assertEquals(0.20, created.getWeight(), 0.001, "0.5 + (-0.30)");
        assertEquals(0.60, created.getConfidence(), 0.001);
        assertEquals(UserPreference.SOURCE_FEEDBACK, created.getSource());

        assertEquals(1, adjustments.size());
        assertTrue(adjustments.get(0).isCreated());
        assertEquals(0.20, adjustments.get(0).getWeight(), 0.001);
    }

    @Test
    void restaurantDislike_mapsToFoodCategory() {
        // RESTAURANT 负反馈 → 口味域（food）标签；无既有行 → FEEDBACK w=0.2
        when(userPreferenceRepository.selectOne(any())).thenReturn(null);

        service.recordBehavior("user-1",
                behavior(UserBehavior.ACTION_DISLIKE, UserBehavior.ITEM_TYPE_RESTAURANT,
                        "老北京火锅(前门店)", null, "trip-1"));

        ArgumentCaptor<UserPreference> prefCaptor = ArgumentCaptor.forClass(UserPreference.class);
        verify(userPreferenceRepository).insert(prefCaptor.capture());
        assertEquals(UserPreference.CATEGORY_FOOD, prefCaptor.getValue().getCategory());
        assertEquals("火锅", prefCaptor.getValue().getTag());
    }

    @Test
    void replaceClampsWeightAtFloor() {
        // 替换 -0.20：FEEDBACK w0.15 的弱偏好行 → clamp 到 0（不出现负权重）
        when(userPreferenceRepository.selectOne(any())).thenReturn(
                feedbackPrefRow("自然风景", 0.15));

        service.recordBehavior("user-1",
                behavior(UserBehavior.ACTION_REPLACE, UserBehavior.ITEM_TYPE_SPOT,
                        "某山峰", "自然风景;山岳", "trip-1"));

        ArgumentCaptor<UserPreference> prefCaptor = ArgumentCaptor.forClass(UserPreference.class);
        verify(userPreferenceRepository).updateById(prefCaptor.capture());
        assertEquals(0.0, prefCaptor.getValue().getWeight(), 0.001, "clamp 下限 0");
    }

    @Test
    void rateTrip_recordsOnlyWithoutWeightNoise() {
        // 整体评分仅留痕（aspect 存 aspect_json 供阶段四指标），不产生标签噪声
        BehaviorRequest req = behavior(UserBehavior.ACTION_RATE, UserBehavior.ITEM_TYPE_TRIP,
                "北京文化之旅", null, "trip-1");
        req.setRating(2);
        req.setAspects(List.of("pace", "food"));

        List<PreferenceAdjustment> adjustments =
                service.recordBehavior("user-1", req);

        ArgumentCaptor<UserBehavior> behaviorCaptor = ArgumentCaptor.forClass(UserBehavior.class);
        verify(userBehaviorRepository).insert(behaviorCaptor.capture());
        UserBehavior saved = behaviorCaptor.getValue();
        assertEquals(UserBehavior.ACTION_RATE, saved.getActionType());
        assertEquals(2, saved.getRating());
        assertEquals("[\"pace\",\"food\"]", saved.getAspectJson());
        assertEquals("trip-1", saved.getTripId());
        verify(userPreferenceRepository, never()).insert(any(UserPreference.class));
        verify(userPreferenceRepository, never()).updateById(any(UserPreference.class));
        assertTrue(adjustments.isEmpty(), "RATE 不返回权重调整");
    }

    @Test
    void invalidAction_rejected() {
        BehaviorRequest req = behavior("HACK", UserBehavior.ITEM_TYPE_SPOT, "x", null, null);
        try {
            service.recordBehavior("user-1", req);
            assertTrue(false, "非法行为应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("HACK"));
        }
        verify(userBehaviorRepository, never()).insert(any(UserBehavior.class));
    }

    @Test
    void memoryText_includesFeedbackAndAvoidLines() {
        // FEEDBACK 正偏好（收藏形成 w0.65）按"行为反馈"输出；负反馈行（w0.2）转"近期不感兴趣"
        when(userProfileRepository.selectOne(any())).thenReturn(profileRow());
        when(userPreferenceRepository.selectList(any())).thenReturn(
                List.of(feedbackPrefRow("自然风景", 0.65), feedbackPrefRow("购物", 0.20)));
        when(tripRecordService.getRecentTrips(anyString(), anyInt())).thenReturn(List.of());

        String memory = service.buildMemoryText("user-1", new TripRequest());

        assertNotNull(memory);
        assertTrue(memory.contains("[行为反馈]"), "正反馈行需标注行为反馈来源");
        assertTrue(memory.contains("自然风景"));
        assertTrue(memory.contains("近期不感兴趣"), "低权重负反馈行需转回避信号");
        assertTrue(memory.contains("购物"));
    }

    /* ================= 画像版本（阶段三：结果缓存按画像失效） ================= */

    @Test
    void feedbackWeightChange_bumpsProfileVersion() {
        // 行为反馈真实改变了画像权重 → 主档 profileVersion+1（结果缓存 key 将不再命中旧行程）
        UserPreference historyPref = new UserPreference();
        historyPref.setUserId("user-1");
        historyPref.setCategory(UserPreference.CATEGORY_TRAVEL_STYLE);
        historyPref.setTag("历史文化");
        historyPref.setWeight(0.5);
        historyPref.setConfidence(0.5);
        historyPref.setSource(UserPreference.SOURCE_HISTORY_INFER);
        when(userPreferenceRepository.selectOne(any())).thenReturn(historyPref);
        when(userProfileRepository.selectOne(any())).thenReturn(profileRow()); // v2

        service.recordBehavior("user-1",
                behavior(UserBehavior.ACTION_SAVE, UserBehavior.ITEM_TYPE_SPOT,
                        "故宫博物院", "科教文化;博物馆", "trip-1"));

        ArgumentCaptor<UserProfile> profileCaptor = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileRepository).updateById(profileCaptor.capture());
        assertEquals(3, profileCaptor.getValue().getProfileVersion(), "画像版本应从 2 递增到 3");
    }

    @Test
    void dislikeProtectedRow_doesNotBumpVersion() {
        // 问卷行受负反馈保护（无真实变化）→ 不递增版本（避免无意义缓存失效）
        when(userPreferenceRepository.selectOne(any())).thenReturn(
                prefRow(UserPreference.CATEGORY_TRAVEL_STYLE, "历史文化", UserPreference.SOURCE_QUESTIONNAIRE));

        service.recordBehavior("user-1",
                behavior(UserBehavior.ACTION_DISLIKE, UserBehavior.ITEM_TYPE_SPOT,
                        "某博物馆", "科教文化;博物馆", "trip-1"));

        verify(userProfileRepository, never()).updateById(any(UserProfile.class));
    }

    @Test
    void getProfileVersion_returnsVersionOrZero() {
        when(userProfileRepository.selectOne(any())).thenReturn(profileRow());
        assertEquals(2, service.getProfileVersion("user-1"));

        when(userProfileRepository.selectOne(any())).thenReturn(null);
        assertEquals(0, service.getProfileVersion("user-1"), "无主档按 0 处理");
    }

    @Test
    void dislikeItemReason_onlyLogsWithoutTagGeneralization() {
        // 用户只不喜欢"这个具体地点"（REASON_ITEM）→ 仅留痕，不把一次误点泛化到整类标签
        BehaviorRequest req = behavior(UserBehavior.ACTION_DISLIKE, UserBehavior.ITEM_TYPE_SPOT,
                "星光购物中心", "购物服务;商场", "trip-1");
        req.setReason(BehaviorRequest.REASON_ITEM);

        List<PreferenceAdjustment> adjustments = service.recordBehavior("user-1", req);

        ArgumentCaptor<UserBehavior> behaviorCaptor = ArgumentCaptor.forClass(UserBehavior.class);
        verify(userBehaviorRepository).insert(behaviorCaptor.capture());
        assertEquals("[\"REASON:ITEM\"]", behaviorCaptor.getValue().getAspectJson(), "原因需入 aspect_json 留痕");
        verify(userPreferenceRepository, never()).insert(any(UserPreference.class));
        verify(userPreferenceRepository, never()).updateById(any(UserPreference.class));
        assertTrue(adjustments.isEmpty(), "具体地点负反馈不降任何标签权重");
    }

    @Test
    void dislikeTagReason_onlyLowersRequestedTag() {
        // 明确点选"不喜欢这个主题标签"（REASON_TAG=购物）→ 只降点名标签权重（0.62 → 0.32）
        when(userPreferenceRepository.selectOne(any())).thenReturn(feedbackPrefRow("购物", 0.62));

        BehaviorRequest req = behavior(UserBehavior.ACTION_DISLIKE, UserBehavior.ITEM_TYPE_SPOT,
                "星光购物中心", "购物服务;商场", "trip-1");
        req.setReason(BehaviorRequest.REASON_TAG);
        req.setTags(List.of("购物"));

        List<PreferenceAdjustment> adjustments = service.recordBehavior("user-1", req);

        ArgumentCaptor<UserPreference> prefCaptor = ArgumentCaptor.forClass(UserPreference.class);
        verify(userPreferenceRepository).updateById(prefCaptor.capture());
        assertEquals(0.32, prefCaptor.getValue().getWeight(), 0.001, "点名标签按 -0.30 降权");
        assertEquals(1, adjustments.size());
        assertEquals("购物", adjustments.get(0).getTag());
    }

    @Test
    void dislikeTagReason_unknownTagSkipsWeightChange() {
        // REASON_TAG 点名的标签与地点实际命中的标签不一致 → 仅留痕，不降权重（防前端误传）
        BehaviorRequest req = behavior(UserBehavior.ACTION_DISLIKE, UserBehavior.ITEM_TYPE_SPOT,
                "星光购物中心", "购物服务;商场", "trip-1");
        req.setReason(BehaviorRequest.REASON_TAG);
        req.setTags(List.of("历史文化"));

        List<PreferenceAdjustment> adjustments = service.recordBehavior("user-1", req);

        verify(userBehaviorRepository).insert(any(UserBehavior.class));
        verify(userPreferenceRepository, never()).insert(any(UserPreference.class));
        verify(userPreferenceRepository, never()).updateById(any(UserPreference.class));
        assertTrue(adjustments.isEmpty(), "未点名到有效标签 → 不泛化");
    }

    /* ================= 阶段三：帖子行为（POST）进入画像闭环 ================= */

    @Test
    void savePost_createsFeedbackRowsForPostTags() {
        // 收藏一篇"大理古镇攻略"：帖子画像标签（历史文化/轻松/大理，由 PostTagService 惰性补齐）
        when(postTagService.ensure(88L)).thenReturn(List.of(
                postTagRow(88L, UserPreference.CATEGORY_TRAVEL_STYLE, "历史文化"),
                postTagRow(88L, UserPreference.CATEGORY_PACE, "轻松"),
                postTagRow(88L, UserPreference.CATEGORY_CITY, "大理")));

        BehaviorRequest req = new BehaviorRequest();
        req.setItemType(UserBehavior.ITEM_TYPE_POST);
        req.setItemId("88");
        req.setItemName("大理古镇两日慢游");
        req.setActionType(UserBehavior.ACTION_SAVE);
        List<PreferenceAdjustment> adjustments = service.recordBehavior("user-1", req);

        ArgumentCaptor<UserPreference> captor = ArgumentCaptor.forClass(UserPreference.class);
        verify(userPreferenceRepository, times(3)).insert(captor.capture());
        List<UserPreference> rows = captor.getAllValues();
        assertEquals(3, rows.size());
        // 收藏 → 每条新标签 FEEDBACK 行 0.5+0.15=0.65，跨域（风格/节奏/城市）同规则
        assertTrue(rows.stream().allMatch(r -> r.getSource().equals(UserPreference.SOURCE_FEEDBACK)));
        assertTrue(rows.stream().allMatch(r -> Math.abs(r.getWeight() - 0.65) < 1e-9));
        assertTrue(rows.stream().anyMatch(r -> "travel_style".equals(r.getCategory()) && "历史文化".equals(r.getTag())));
        assertTrue(rows.stream().anyMatch(r -> "pace".equals(r.getCategory()) && "轻松".equals(r.getTag())));
        assertTrue(rows.stream().anyMatch(r -> "city".equals(r.getCategory()) && "大理".equals(r.getTag())));
        verify(userBehaviorRepository).insert(any(UserBehavior.class));
        assertEquals(3, adjustments.size());
    }

    @Test
    void dislikePost_lowersAllPostTags_asAvoidSignals() {
        // 对帖子点"不感兴趣"（reason 空 = TYPE）→ 该帖全部标签 -0.30 → 后续同类帖子沉底
        when(postTagService.ensure(88L)).thenReturn(List.of(
                postTagRow(88L, UserPreference.CATEGORY_TRAVEL_STYLE, "历史文化"),
                postTagRow(88L, UserPreference.CATEGORY_PACE, "轻松")));

        BehaviorRequest req = new BehaviorRequest();
        req.setItemType(UserBehavior.ITEM_TYPE_POST);
        req.setItemId("88");
        req.setItemName("大理古镇两日慢游");
        req.setActionType(UserBehavior.ACTION_DISLIKE);
        List<PreferenceAdjustment> adjustments = service.recordBehavior("user-1", req);

        ArgumentCaptor<UserPreference> captor = ArgumentCaptor.forClass(UserPreference.class);
        verify(userPreferenceRepository, times(2)).insert(captor.capture());
        List<UserPreference> rows = captor.getAllValues();
        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(r -> Math.abs(r.getWeight() - 0.20) < 1e-9), "0.5 - 0.30 → 回避信号行");
        assertEquals(2, adjustments.size());
    }

    /** 构造一次行为上报（itemId 可空） */
    private BehaviorRequest behavior(String action, String itemType, String name, String poiType, String tripId) {
        BehaviorRequest req = new BehaviorRequest();
        req.setActionType(action);
        req.setItemType(itemType);
        req.setItemName(name);
        req.setPoiType(poiType);
        req.setTripId(tripId);
        return req;
    }

    private com.yuntu.tripplanner.model.PostTag postTagRow(Long postId, String category, String tag) {
        com.yuntu.tripplanner.model.PostTag t = new com.yuntu.tripplanner.model.PostTag();
        t.setPostId(postId);
        t.setCategory(category);
        t.setTag(tag);
        return t;
    }

    /** FEEDBACK 来源偏好行（指定权重） */
    private UserPreference feedbackPrefRow(String tag, double weight) {
        UserPreference p = new UserPreference();
        p.setUserId("user-1");
        p.setCategory(UserPreference.CATEGORY_TRAVEL_STYLE);
        p.setTag(tag);
        p.setWeight(weight);
        p.setConfidence(0.60);
        p.setSource(UserPreference.SOURCE_FEEDBACK);
        return p;
    }

    private UserProfile profileRow() {
        UserProfile p = new UserProfile();
        p.setId(1L);
        p.setUserId("user-1");
        p.setPacePreference("轻松");
        p.setFoodPreferences("火锅");
        p.setVisitedCities("北京");
        p.setTripCount(2);
        p.setFilledFromQuestionnaire(1);
        p.setProfileVersion(2);
        return p;
    }

    private UserPreference prefRow(String category, String tag, String source) {
        UserPreference p = new UserPreference();
        p.setUserId("user-1");
        p.setCategory(category);
        p.setTag(tag);
        p.setWeight(0.9);
        p.setConfidence(0.95);
        p.setSource(source);
        return p;
    }
}
