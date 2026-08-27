package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.model.Itinerary;
import com.yuntu.tripplanner.model.TripRecord;
import com.yuntu.tripplanner.model.TripSaveRequest;
import com.yuntu.tripplanner.repository.AgentTraceRepository;
import com.yuntu.tripplanner.repository.TripRecordRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 行程隔离单测：列表/详情/删除按 userId 过滤，保存以服务端 userId 为准（防伪造归属）。
 */
@ExtendWith(MockitoExtension.class)
class TripRecordServiceTest {

    @Mock
    private TripRecordRepository tripRecordRepository;

    @Mock
    private AgentTraceRepository agentTraceRepository;

    private TripRecordService service;

    @BeforeEach
    void setUp() {
        // 注册实体 TableInfo，否则 LambdaQueryWrapper 的方法引用在无 Spring 环境解析失败
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), TripRecord.class);
        service = new TripRecordService(tripRecordRepository, agentTraceRepository, new ObjectMapper());
    }

    @Test
    void getTripListQueriesByUserId() {
        service.getTripList("user-42");

        ArgumentCaptor<LambdaQueryWrapper<TripRecord>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(tripRecordRepository).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("user_id"), "列表查询必须带 user_id 条件");
    }

    @Test
    void getTripDetailForOtherUserReturnsNull() {
        when(tripRecordRepository.selectOne(any())).thenReturn(null);

        assertNull(service.getTripDetail("trip-1", "other-user"), "非本人行程应返回 null（前端转 404）");
    }

    @Test
    void deleteTripScopesToUser() {
        service.deleteTrip("trip-1", "user-42");

        ArgumentCaptor<LambdaQueryWrapper<TripRecord>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(tripRecordRepository).delete(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("user_id"), "删除必须带 user_id 条件");
    }

    @Test
    void saveTripUsesServerSideUserIdOverRequest() {
        TripSaveRequest req = new TripSaveRequest();
        req.setTripId("trip-1");
        req.setUserId("fake-user"); // 前端伪造的归属
        Itinerary itinerary = new Itinerary();
        itinerary.setTripId("trip-1");
        itinerary.setDestination("成都");
        req.setItinerary(itinerary);
        req.setTrace(List.of());

        when(tripRecordRepository.selectOne(any())).thenReturn(null);

        service.saveTrip(req, "server-user-99");

        ArgumentCaptor<TripRecord> insertCaptor = ArgumentCaptor.forClass(TripRecord.class);
        verify(tripRecordRepository).insert(insertCaptor.capture());
        assertEquals("server-user-99", insertCaptor.getValue().getUserId(), "保存归属必须以服务端解析的 userId 为准");
    }
}
