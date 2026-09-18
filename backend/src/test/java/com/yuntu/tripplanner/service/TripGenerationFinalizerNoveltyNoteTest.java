package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.model.AgentTraceResponse;
import com.yuntu.tripplanner.model.CandidateEvidence;
import com.yuntu.tripplanner.model.DayPlan;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.MealItem;
import com.yuntu.tripplanner.model.PersonalizationSummary;
import com.yuntu.tripplanner.model.SpotItem;
import com.yuntu.tripplanner.model.TripRequest;
import com.yuntu.tripplanner.repository.CandidateEvidenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 「已减少历史行程中出现过的重复景点」必须描述<b>结果</b>，不能只描述<b>意图</b>。
 *
 * <p>三亚实测：凤凰岛桥头公园、大东海广场因"去过"被降级排除，鹿回头风景区同样"去过"却照样排进
 * 第 4 天，而页面顶部仍宣称「已减少重复」——用户看到的不是规则，是"规则时灵时不灵"。
 * 这里锁住新口径：按最终行程实算，全避开就说避开几个，保留了就点名是哪几个。
 */
@ExtendWith(MockitoExtension.class)
class TripGenerationFinalizerNoveltyNoteTest {

    @Mock
    private RecommendationService recommendationService;
    @Mock
    private CandidateEvidenceRepository candidateEvidenceRepository;
    @Mock
    private UserProfileService userProfileService;
    @Mock
    private TravelEventService travelEventService;

    private TripGenerationFinalizer finalizer;

    @BeforeEach
    void setUp() {
        finalizer = new TripGenerationFinalizer(recommendationService, candidateEvidenceRepository,
                userProfileService, travelEventService);
    }

    private Itinerary itinerary(String... spotNames) {
        DayPlan d = new DayPlan();
        d.setDayIndex(1);
        d.setSpots(new ArrayList<>());
        d.setMeals(new ArrayList<>());
        d.setTransport(new ArrayList<>());
        d.setNotes(new ArrayList<>());
        for (String n : spotNames) {
            SpotItem s = new SpotItem();
            s.setName(n);
            d.getSpots().add(s);
        }
        Itinerary it = new Itinerary();
        it.setDestination("三亚");
        it.setDays(List.of(d));
        return it;
    }

    private CandidateEvidence visitedSpot(String name) {
        CandidateEvidence ev = new CandidateEvidence();
        ev.setItemName(name);
        ev.setBucket("景点");
        ev.setVisited(1);
        return ev;
    }

    private CandidateEvidence newSpot(String name) {
        CandidateEvidence ev = new CandidateEvidence();
        ev.setItemName(name);
        ev.setBucket("景点");
        ev.setVisited(0);
        return ev;
    }

    private CandidateEvidence visitedMeal(String name) {
        CandidateEvidence ev = new CandidateEvidence();
        ev.setItemName(name);
        ev.setBucket(CandidateEvidence.BUCKET_RESTAURANT);
        ev.setVisited(1);
        return ev;
    }

    /** 跑一次完整收尾（候证据落库/埋点全为 mock），返回挂到行程上的个性化摘要 */
    private PersonalizationSummary run(Itinerary it, String originalNote, List<CandidateEvidence> evidence) {
        PersonalizationSummary summary = new PersonalizationSummary();
        summary.setNoveltyNote(originalNote);
        when(userProfileService.buildPersonalizationSummary(anyString(), any(), any())).thenReturn(summary);

        AgentTraceResponse resp = new AgentTraceResponse();
        resp.setSuccess(true);
        resp.setItinerary(it);
        resp.setCandidateEvidence(evidence);

        finalizer.finalizeGeneration("u1", new TripRequest(), resp);
        return it.getPersonalizationSummary();
    }

    @Test
    void allVisitedSpotsAvoided_noteStatesAvoidedCount() {
        // 历史去过 2 个，最终行程一个都没排 → 说清"避开了 2 个"
        Itinerary it = itinerary("椰梦长廊", "蜈支洲岛");
        PersonalizationSummary summary = run(it, "已减少历史行程中出现过的重复景点",
                List.of(visitedSpot("凤凰岛桥头公园"), visitedSpot("大东海广场"), newSpot("椰梦长廊")));

        assertNotNull(summary, "有历史去重信息时摘要不应被整体隐藏");
        String note = summary.getNoveltyNote();
        assertTrue(note.contains("已避开") && note.contains("2 个地点"),
                "全部避开时应说清避开了几个，实际：" + note);
        assertFalse(note.contains("已减少历史行程中出现过的重复景点"),
                "不能保留模糊的意图式原文（必须换成结果口径）");
    }

    @Test
    void visitedSpotStillPlanned_noteNamesIt() {
        // 鹿回头同样"去过"但最终仍在行程里 → 必须点名，而不是笼统宣称"已减少重复"
        Itinerary it = itinerary("鹿回头风景区", "椰梦长廊");
        PersonalizationSummary summary = run(it, "已减少历史行程中出现过的重复景点",
                List.of(visitedSpot("鹿回头风景区"), visitedSpot("凤凰岛桥头公园")));

        assertNotNull(summary);
        String note = summary.getNoveltyNote();
        assertTrue(note.contains("鹿回头风景区"), "保留下来的历史景点必须点名，实际：" + note);
        assertTrue(note.contains("保留"), "要点明为什么还在，实际：" + note);
        assertFalse(note.contains("本次全部安排新地点"), "并非全部避开，不能说全部");
    }

    @Test
    void visitedMealStillPlanned_noteNamesIt() {
        // 餐厅也按"结果"判定：去过的店还在行程里 → 点名
        DayPlan d = new DayPlan();
        d.setDayIndex(1);
        d.setSpots(new ArrayList<>());
        d.setTransport(new ArrayList<>());
        d.setNotes(new ArrayList<>());
        SpotItem s = new SpotItem();
        s.setName("椰梦长廊");
        d.getSpots().add(s);
        MealItem m = new MealItem();
        m.setName("第一市场海鲜大排档");
        d.setMeals(new ArrayList<>(List.of(m)));
        Itinerary it = new Itinerary();
        it.setDestination("三亚");
        it.setDays(List.of(d));

        PersonalizationSummary summary = run(it, "已减少历史行程中出现过的重复景点",
                List.of(visitedMeal("第一市场海鲜大排档")));

        assertNotNull(summary);
        assertTrue(summary.getNoveltyNote().contains("第一市场海鲜大排档"),
                "去过的餐厅仍在行程里也要点名，实际：" + summary.getNoveltyNote());
    }

    @Test
    void noVisitedCandidate_keepsOriginalNote() {
        // 证据里没有任何"去过"的项 → 不越权改写（原文由画像侧决定）
        Itinerary it = itinerary("椰梦长廊");
        PersonalizationSummary summary = run(it, "已尽量减少与历史行程重复的地点",
                List.of(newSpot("凤凰岛桥头公园"), newSpot("大东海广场")));

        assertNotNull(summary);
        assertEquals("已尽量减少与历史行程重复的地点", summary.getNoveltyNote(),
                "无 visited 证据时不得改写文案");
    }
}
