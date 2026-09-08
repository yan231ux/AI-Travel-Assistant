package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.agent.CollectedData;
import com.yuntu.tripplanner.model.DayPlan;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.SpotItem;
import com.yuntu.tripplanner.model.TripRecord;
import com.yuntu.tripplanner.model.UserPreference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 个性化候选排序单测（个性化阶段三）：
 * 问卷偏好命中置顶 / 负反馈回避标签沉底 / 点名景点豁免过滤 /
 * 已体验景点提示换新 / 餐厅按口味域排序 / 无画像或单候选跳过 / 同分稳定保持原序。
 */
@ExtendWith(MockitoExtension.class)
class PersonalizedRankingServiceTest {

    @Mock
    private UserProfileService userProfileService;
    @Mock
    private TripRecordService tripRecordService;

    private PersonalizedRankingService service;

    @BeforeEach
    void setUp() {
        service = new PersonalizedRankingService(userProfileService, tripRecordService);
    }

    /** 构造候选 POI（name + 高德 type 两键即可，与 AmapClient 返回字段一致） */
    private Map<String, Object> poi(String name, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        return m;
    }

    private CollectedData dataWithSpots(Map<String, Object>... spots) {
        CollectedData cd = new CollectedData();
        cd.getPoiResults().put("景点", List.of(spots));
        return cd;
    }

    private UserPreference stylePref(String tag, double weight, double confidence, String source) {
        UserPreference p = new UserPreference();
        p.setUserId("user-1");
        p.setCategory(UserPreference.CATEGORY_TRAVEL_STYLE);
        p.setTag(tag);
        p.setWeight(weight);
        p.setConfidence(confidence);
        p.setSource(source);
        return p;
    }

    private UserPreference foodPref(String tag, double weight, double confidence, String source) {
        UserPreference p = new UserPreference();
        p.setUserId("user-1");
        p.setCategory(UserPreference.CATEGORY_FOOD);
        p.setTag(tag);
        p.setWeight(weight);
        p.setConfidence(confidence);
        p.setSource(source);
        return p;
    }

    @Test
    void noUserIdOrNoPrefs_skipsSilently() {
        when(userProfileService.listPreferences("user-1")).thenReturn(List.of());

        CollectedData cd = dataWithSpots(poi("故宫博物院", "科教文化;博物馆"));
        assertFalse(service.rankAndFilter("user-1", null, cd), "无画像 → 不排序");

        // 未登录
        assertFalse(service.rankAndFilter(null, null, cd), "无 userId → 不排序");
    }

    @Test
    void questionnairePref_boostsMatchingSpotToFrontWithNote() {
        when(userProfileService.listPreferences("user-1")).thenReturn(List.of(
                stylePref("历史文化", 0.9, 0.95, UserPreference.SOURCE_QUESTIONNAIRE),
                stylePref("自然风景", 0.9, 0.95, UserPreference.SOURCE_QUESTIONNAIRE)));
        when(tripRecordService.getRecentTrips(anyString(), anyInt())).thenReturn(List.of());

        CollectedData cd = dataWithSpots(
                poi("现代购物中心", "购物服务;商场"),
                poi("故宫博物院", "科教文化;博物馆"),
                poi("某森林公园", "风景名胜;森林公园"));

        assertTrue(service.rankAndFilter("user-1", null, cd));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranked = (List<Map<String, Object>>) cd.getPoiResults().get("景点");
        assertEquals("故宫博物院", ranked.get(0).get("name"), "历史文化偏好的故宫应置顶");
        assertEquals("某森林公园", ranked.get(1).get("name"), "自然风景偏好次之");
        assertEquals("现代购物中心", ranked.get(2).get("name"), "无匹配候选沉底");

        List<String> notes = cd.getPersonalizedNotes();
        assertTrue(notes.stream().anyMatch(n -> n.contains("故宫博物院") && n.contains("历史文化")),
                "需产出匹配偏好的说明");
        assertFalse(notes.stream().anyMatch(n -> n.contains("不感兴趣")),
                "无负反馈时不应有回避说明");
    }

    @Test
    void dislikeFeedbackTag_sinksCandidateWithAvoidNote() {
        // 用户对"购物"类点过不感兴趣 → FEEDBACK 行 w0.2（跌破 0.45 阈值）→ 候选沉底
        when(userProfileService.listPreferences("user-1")).thenReturn(List.of(
                stylePref("购物", 0.20, 0.6, UserPreference.SOURCE_FEEDBACK)));
        when(tripRecordService.getRecentTrips(anyString(), anyInt())).thenReturn(List.of());

        CollectedData cd = dataWithSpots(
                poi("故宫博物院", "科教文化;博物馆"),
                poi("星光购物中心", "购物服务;商场"));

        assertTrue(service.rankAndFilter("user-1", null, cd));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranked = (List<Map<String, Object>>) cd.getPoiResults().get("景点");
        assertEquals("故宫博物院", ranked.get(0).get("name"), "无回避的候选前置");
        assertEquals("星光购物中心", ranked.get(1).get("name"), "命中回避标签的候选沉底");

        List<String> notes = cd.getPersonalizedNotes();
        assertTrue(notes.stream().anyMatch(n -> n.contains("星光购物中心") && n.contains("不感兴趣")),
                "需产出回避原因说明");
    }

    @Test
    void requestedSpot_isExemptFromAvoid() {
        // 用户点名要去的景点不受"近期不感兴趣"标签影响（豁免硬约束）
        when(userProfileService.listPreferences("user-1")).thenReturn(List.of(
                stylePref("购物", 0.20, 0.6, UserPreference.SOURCE_FEEDBACK)));
        when(tripRecordService.getRecentTrips(anyString(), anyInt())).thenReturn(List.of());

        CollectedData cd = dataWithSpots(
                poi("星光购物中心", "购物服务;商场"),
                poi("故宫博物院", "科教文化;博物馆"));
        cd.setRequestedSpots(new ArrayList<>(List.of("星光购物中心")));

        assertTrue(service.rankAndFilter("user-1", null, cd));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranked = (List<Map<String, Object>>) cd.getPoiResults().get("景点");
        assertEquals("星光购物中心", ranked.get(0).get("name"), "点名景点豁免回避标签，保持原序首位");
        assertFalse(cd.getPersonalizedNotes().stream().anyMatch(n -> n.contains("不感兴趣")),
                "点名景点不应产回避说明");
    }

    @Test
    void visitedSpot_getsNewPlaceTip() {
        // 上次行程已去过故宫 → 说明中提示"已体验过，建议同类新地点"
        when(userProfileService.listPreferences("user-1")).thenReturn(List.of(
                stylePref("历史文化", 0.9, 0.95, UserPreference.SOURCE_QUESTIONNAIRE)));

        TripRecord visited = new TripRecord();
        visited.setTripId("t-old");
        Itinerary it = new Itinerary();
        DayPlan day = new DayPlan();
        SpotItem spot = new SpotItem();
        spot.setName("故宫博物院");
        day.setSpots(List.of(spot));
        it.setDays(List.of(day));
        visited.setItinerary(it);
        when(tripRecordService.getRecentTrips(anyString(), anyInt())).thenReturn(List.of(visited));

        CollectedData cd = dataWithSpots(
                poi("故宫博物院", "科教文化;博物馆"),
                poi("天坛公园", "风景名胜;公园"));

        service.rankAndFilter("user-1", null, cd);

        List<String> notes = cd.getPersonalizedNotes();
        assertTrue(notes.stream().anyMatch(n -> n.contains("故宫博物院") && n.contains("已体验过")),
                "已体验候选需提示换新");
    }

    @Test
    void restaurantBucket_usesFoodDomain() {
        // 餐厅桶按口味（food）域排序：火锅偏好 → 老北京火锅置顶
        when(userProfileService.listPreferences("user-1")).thenReturn(List.of(
                foodPref("火锅", 0.9, 0.95, UserPreference.SOURCE_QUESTIONNAIRE)));
        when(tripRecordService.getRecentTrips(anyString(), anyInt())).thenReturn(List.of());

        CollectedData cd = new CollectedData();
        cd.getPoiResults().put("餐厅", List.of(
                poi("全聚德烤鸭", "餐饮服务;中餐厅"),
                poi("老北京火锅(前门店)", "餐饮服务;火锅店")));

        assertTrue(service.rankAndFilter("user-1", null, cd));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranked = (List<Map<String, Object>>) cd.getPoiResults().get("餐厅");
        assertEquals("老北京火锅(前门店)", ranked.get(0).get("name"), "命中火锅口味的餐厅置顶");
        assertTrue(cd.getPersonalizedNotes().stream().anyMatch(n -> n.contains("火锅")),
                "餐厅桶需产出口味匹配说明");
    }

    @Test
    void singleCandidate_skipsRanking() {
        when(userProfileService.listPreferences("user-1")).thenReturn(List.of(
                stylePref("历史文化", 0.9, 0.95, UserPreference.SOURCE_QUESTIONNAIRE)));
        when(tripRecordService.getRecentTrips(anyString(), anyInt())).thenReturn(List.of());

        CollectedData cd = dataWithSpots(poi("故宫博物院", "科教文化;博物馆"));
        assertFalse(service.rankAndFilter("user-1", null, cd), "仅 1 个候选无排序意义");
        assertTrue(cd.getPersonalizedNotes().isEmpty());
    }

    @Test
    void equalScores_keepOriginalOrder() {
        // 两个无匹配候选：同分 → 保持高德原序（稳定排序）
        when(userProfileService.listPreferences("user-1")).thenReturn(List.of(
                stylePref("历史文化", 0.9, 0.95, UserPreference.SOURCE_QUESTIONNAIRE)));
        when(tripRecordService.getRecentTrips(anyString(), anyInt())).thenReturn(List.of());

        CollectedData cd = dataWithSpots(
                poi("甲公园", "风景名胜;公园"),
                poi("乙广场", "购物服务;商业街"));

        service.rankAndFilter("user-1", null, cd);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranked = (List<Map<String, Object>>) cd.getPoiResults().get("景点");
        assertEquals("甲公园", ranked.get(0).get("name"), "同分保持原序");
        assertEquals("乙广场", ranked.get(1).get("name"));
    }
}
