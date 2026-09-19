package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuntu.tripplanner.model.DayPlan;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.SpotItem;
import com.yuntu.tripplanner.model.TripRecord;
import com.yuntu.tripplanner.repository.TripRecordRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 「去过」唯一数据源单测（确认去过功能）：确认过的城市/景点名从 VisitedService 统一出。
 */
@ExtendWith(MockitoExtension.class)
class VisitedServiceTest {

    @Mock
    private TripRecordRepository tripRecordRepository;

    private VisitedService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), TripRecord.class);
        service = new VisitedService(tripRecordRepository);
    }

    private TripRecord trip(String dest, String... spotNames) {
        TripRecord r = new TripRecord();
        r.setDestination(dest);
        Itinerary it = new Itinerary();
        DayPlan day = new DayPlan();
        day.setSpots(List.of(spotNames).stream().map(n -> {
            SpotItem s = new SpotItem();
            s.setName(n);
            return s;
        }).toList());
        it.setDays(List.of(day));
        r.setItinerary(it);
        return r;
    }

    @Test
    void confirmedVisitedCities_distinctInOrder() {
        when(tripRecordRepository.selectList(any())).thenReturn(List.of(trip("成都"), trip("北京")));

        Set<String> cities = service.confirmedVisitedCities("u1");

        assertEquals(List.of("成都", "北京"), new ArrayList<>(cities), "按确认时间倒序去重城市");
    }

    @Test
    void confirmedVisitedSpotNames_extractsNames() {
        when(tripRecordRepository.selectList(any())).thenReturn(List.of(
                trip("成都", "宽窄巷子", "锦里")));

        Set<String> names = service.confirmedVisitedSpotNames("u1");

        assertTrue(names.contains("宽窄巷子"));
        assertTrue(names.contains("锦里"));
    }

    @Test
    void blankUserId_returnsEmptyWithoutQuery() {
        assertTrue(service.confirmedVisitedCities(null).isEmpty());
        assertTrue(service.confirmedVisitedSpotNames("").isEmpty());

        verifyNoInteractions(tripRecordRepository);
    }
}
