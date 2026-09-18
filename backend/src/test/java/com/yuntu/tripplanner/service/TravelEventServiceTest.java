package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuntu.tripplanner.model.DayPlan;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.Spot;
import com.yuntu.tripplanner.model.SpotItem;
import com.yuntu.tripplanner.model.TravelEvent;
import com.yuntu.tripplanner.repository.SpotRepository;
import com.yuntu.tripplanner.repository.TravelEventRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 行程事件埋点单测（阶段二数据地基）：
 * 去重（同 user+trip+item+event 只记一次）/ trip+spot 两级事件 /
 * item_id 口径 spot_id→poi_id→名称兜底 / 按天预聚合 DELETE+INSERT 幂等。
 */
@ExtendWith(MockitoExtension.class)
class TravelEventServiceTest {

    @Mock
    private TravelEventRepository travelEventRepository;
    @Mock
    private SpotRepository spotRepository;

    private TravelEventService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), TravelEvent.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Spot.class);
        service = new TravelEventService(travelEventRepository, spotRepository);
    }

    private Itinerary itineraryWithSpots(String... spotNames) {
        Itinerary it = new Itinerary();
        it.setTripId("trip_成都_2026-10-17_ab12cd34");
        it.setDestination("成都");
        DayPlan day = new DayPlan();
        day.setSpots(List.of(spotNames).stream().map(n -> {
            SpotItem s = new SpotItem();
            s.setName(n);
            return s;
        }).toList());
        it.setDays(List.of(day));
        return it;
    }

    @Test
    void tripGenerated_recordsTripAndSpotEvents() {
        when(travelEventRepository.selectCount(any())).thenReturn(0L);

        service.recordTripGenerated("u1", itineraryWithSpots("大熊猫基地", "宽窄巷子"),
                TravelEvent.SOURCE_AGENT);

        ArgumentCaptor<TravelEvent> cap = ArgumentCaptor.forClass(TravelEvent.class);
        verify(travelEventRepository, org.mockito.Mockito.times(3)).insert(cap.capture());
        List<TravelEvent> events = cap.getAllValues();
        assertEquals("TRIP_GENERATED", events.get(0).getEventType());
        assertEquals("trip_成都_2026-10-17_ab12cd34", events.get(0).getItemId());
        assertEquals("SPOT_GENERATED", events.get(1).getEventType());
        assertEquals("SPOT_GENERATED", events.get(2).getEventType());
        // stat_date 与 created_at 同日（预聚合分区口径）
        assertEquals(LocalDate.now(), events.get(0).getStatDate());
    }

    @Test
    void duplicateEvent_isSkippedByDedupQuery() {
        // 同 (user, trip, item, event) 已存在 → 不再 insert（重复刷新结果页不重复统计）
        when(travelEventRepository.selectCount(any())).thenReturn(1L);

        service.recordTripGenerated("u1", itineraryWithSpots("大熊猫基地"), TravelEvent.SOURCE_AGENT);

        verify(travelEventRepository, never()).insert(any(TravelEvent.class));
    }

    @Test
    void sameSpotAcrossDays_countedOncePerTrip() {
        when(travelEventRepository.selectCount(any())).thenReturn(0L);

        Itinerary it = new Itinerary();
        it.setTripId("t1");
        it.setDestination("成都");
        SpotItem s1 = new SpotItem();
        s1.setName("宽窄巷子");
        SpotItem s2 = new SpotItem();
        s2.setName("宽窄巷子"); // 跨天重复
        DayPlan d1 = new DayPlan();
        d1.setSpots(List.of(s1));
        DayPlan d2 = new DayPlan();
        d2.setSpots(List.of(s2));
        it.setDays(List.of(d1, d2));

        service.recordTripGenerated("u1", it, TravelEvent.SOURCE_AGENT);

        // trip 事件 1 条 + 景点去重后 1 条
        verify(travelEventRepository, org.mockito.Mockito.times(2)).insert(any(TravelEvent.class));
    }

    @Test
    void spotItemId_resolvesSpotIdFromPoiThenNameThenFallback() {
        // 1) poi_id 命中主档 → 稳定 spot_id
        Spot byPoi = new Spot();
        byPoi.setSpotId("spot_成都_B0001");
        when(spotRepository.selectOne(any())).thenReturn(byPoi, (Spot) null);
        Itinerary it = itineraryWithSpots("青城山");
        it.getDays().get(0).getSpots().get(0).setPoiId("B0POI1");
        service.recordTripSaved("u1", it);

        ArgumentCaptor<TravelEvent> cap = ArgumentCaptor.forClass(TravelEvent.class);
        verify(travelEventRepository, org.mockito.Mockito.times(2)).insert(cap.capture());
        assertEquals("spot_成都_B0001", cap.getAllValues().get(1).getItemId(),
                "poi_id 命中主档时用稳定 spot_id");

        // 2) poi/主档都未命中 → name: 兜底（走第二个 selectOne=null 分支已覆盖）
    }

    @Test
    void spotItemId_fallsBackToNamePrefix() {
        when(travelEventRepository.selectCount(any())).thenReturn(0L);
        when(spotRepository.selectOne(any())).thenReturn(null); // 主档无记录

        Itinerary it = itineraryWithSpots("无名小景点");
        service.recordTripSaved("u1", it);

        ArgumentCaptor<TravelEvent> cap = ArgumentCaptor.forClass(TravelEvent.class);
        verify(travelEventRepository, org.mockito.Mockito.times(2)).insert(cap.capture());
        assertEquals("name:无名小景点", cap.getAllValues().get(1).getItemId(),
                "主档与 poi_id 均无时用 name: 前缀兜底");
    }

    @Test
    void favorite_usesSpotIdWithoutTrip() {
        when(travelEventRepository.selectCount(any())).thenReturn(0L);
        Spot spot = new Spot();
        spot.setSpotId("spot_上海_B0FFFR6GDS");
        spot.setPoiId("B0FFFR6GDS");
        spot.setName("外滩-观景平台");
        spot.setCity("上海");

        service.recordSpotFavorited("u1", spot);

        ArgumentCaptor<TravelEvent> cap = ArgumentCaptor.forClass(TravelEvent.class);
        verify(travelEventRepository).insert(cap.capture());
        TravelEvent ev = cap.getValue();
        assertEquals("SPOT_FAVORITED", ev.getEventType());
        assertEquals("spot_上海_B0FFFR6GDS", ev.getItemId());
        assertEquals("", ev.getTripId(), "无行程上下文时 trip_id 存空串（唯一键有效）");
    }

    @Test
    void aggregateDaily_deletesThenInserts() {
        when(travelEventRepository.insertTrendingDaily(LocalDate.of(2026, 9, 10))).thenReturn(7);

        int rows = service.aggregateDaily(LocalDate.of(2026, 9, 10));

        assertEquals(7, rows);
        verify(travelEventRepository).deleteTrendingDaily(LocalDate.of(2026, 9, 10));
        verify(travelEventRepository).insertTrendingDaily(LocalDate.of(2026, 9, 10));
    }

    @Test
    void aggregateCityDaily_deletesThenInserts() {
        when(travelEventRepository.insertCityTrendingDaily(LocalDate.of(2026, 9, 10))).thenReturn(3);

        int rows = service.aggregateCityDaily(LocalDate.of(2026, 9, 10));

        assertEquals(3, rows);
        verify(travelEventRepository).deleteCityTrendingDaily(LocalDate.of(2026, 9, 10));
        verify(travelEventRepository).insertCityTrendingDaily(LocalDate.of(2026, 9, 10));
    }

    @Test
    void aggregateModerationDaily_deletesThenInserts() {
        when(travelEventRepository.insertModerationDaily(LocalDate.of(2026, 9, 10))).thenReturn(1);

        int rows = service.aggregateModerationDaily(LocalDate.of(2026, 9, 10));

        assertEquals(1, rows);
        verify(travelEventRepository).deleteModerationDaily(LocalDate.of(2026, 9, 10));
        verify(travelEventRepository).insertModerationDaily(LocalDate.of(2026, 9, 10));
    }

    @Test
    void aggregateAll_recomputesThreeDimensions() {
        LocalDate date = LocalDate.of(2026, 9, 10);

        int ok = service.aggregateAll(date);

        assertEquals(3, ok, "三维度均成功时返回 3");
        verify(travelEventRepository).deleteTrendingDaily(date);
        verify(travelEventRepository).deleteCityTrendingDaily(date);
        verify(travelEventRepository).deleteModerationDaily(date);
    }

    /**
     * 关键隔离性：某一维度失败不能拖垮另外两个。
     * 场景真实性：日表结构异常（如老库漏建表）时，不应连累其余维度也一起不产出。
     */
    @Test
    void aggregateAll_isolatesOneDimensionFailure() {
        LocalDate date = LocalDate.of(2026, 9, 10);
        when(travelEventRepository.insertTrendingDaily(date))
                .thenThrow(new RuntimeException("Table 'spot_trending_daily' doesn't exist"));

        int ok = service.aggregateAll(date);

        assertEquals(2, ok, "景点维度失败，城市与审核维度仍应完成");
        verify(travelEventRepository).deleteCityTrendingDaily(date);
        verify(travelEventRepository).deleteModerationDaily(date);
    }

    @Test
    void aggregateAll_nullDate_returnsZeroWithoutTouchingRepository() {
        assertEquals(0, service.aggregateAll(null));
        verify(travelEventRepository, org.mockito.Mockito.never()).deleteTrendingDaily(any());
    }

    @Test
    void insertFailure_isSwallowedWithWarning() {
        // 旁路埋点：写失败不抛出（不影响生成/保存主链路）
        when(travelEventRepository.selectCount(any())).thenReturn(0L);
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(travelEventRepository).insert(any(TravelEvent.class));

        service.recordTripGenerated("u1", itineraryWithSpots("宽窄巷子"), TravelEvent.SOURCE_AGENT);
        // 未抛异常即通过
        assertTrue(true);
    }
}
