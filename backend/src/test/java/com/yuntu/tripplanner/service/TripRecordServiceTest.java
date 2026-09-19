package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.model.DayPlan;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 行程隔离单测：列表/详情/删除按 userId 过滤，保存以服务端 userId 为准（防伪造归属）。
 * 另覆盖保存的两个坑：trip_id 全局唯一（缺 id 时服务端补）、逻辑删除行需"复活"而非重复插入。
 */
@ExtendWith(MockitoExtension.class)
class TripRecordServiceTest {

    @Mock
    private TripRecordRepository tripRecordRepository;

    @Mock
    private AgentTraceRepository agentTraceRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private TravelEventService travelEventService;

    private TripRecordService service;

    @BeforeEach
    void setUp() {
        // 注册实体 TableInfo，否则 LambdaQueryWrapper 的方法引用在无 Spring 环境解析失败
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), TripRecord.class);
        service = new TripRecordService(tripRecordRepository, agentTraceRepository,
                new ObjectMapper(), auditService, travelEventService);
    }

    private TripSaveRequest saveRequest(String tripId) {
        TripSaveRequest req = new TripSaveRequest();
        req.setTripId(tripId);
        req.setUserId("fake-user"); // 前端伪造的归属
        Itinerary itinerary = new Itinerary();
        itinerary.setTripId(tripId);
        itinerary.setDestination("成都");
        req.setItinerary(itinerary);
        req.setTrace(List.of());
        return req;
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
        when(tripRecordRepository.selectKeyIncludingDeleted(any(), any())).thenReturn(null);

        service.saveTrip(saveRequest("trip-1"), "server-user-99");

        ArgumentCaptor<TripRecord> insertCaptor = ArgumentCaptor.forClass(TripRecord.class);
        verify(tripRecordRepository).insert(insertCaptor.capture());
        assertEquals("server-user-99", insertCaptor.getValue().getUserId(), "保存归属必须以服务端解析的 userId 为准");
    }

    @Test
    void saveTripRevivesSoftDeletedRecordInsteadOfInserting() {
        // 用户删过这条行程（deleted=1）后又点保存：必须复活旧行，否则 INSERT 撞 UNIQUE(trip_id) → 500
        TripRecord softDeleted = new TripRecord();
        softDeleted.setId(7L);
        softDeleted.setDeleted(1);
        when(tripRecordRepository.selectKeyIncludingDeleted(any(), any())).thenReturn(softDeleted);

        service.saveTrip(saveRequest("trip-1"), "user-42");

        verify(tripRecordRepository).restoreById(7L);
        verify(tripRecordRepository).updateById(any());
        verify(tripRecordRepository, never()).insert(any());
    }

    @Test
    void saveTripUpdatesLiveRecordWithoutRestore() {
        TripRecord live = new TripRecord();
        live.setId(8L);
        live.setDeleted(0);
        when(tripRecordRepository.selectKeyIncludingDeleted(any(), any())).thenReturn(live);

        service.saveTrip(saveRequest("trip-1"), "user-42");

        verify(tripRecordRepository, never()).restoreById(any());
        verify(tripRecordRepository).updateById(any());
        verify(tripRecordRepository, never()).insert(any());
    }

    @Test
    void saveTripAssignsUniqueIdWhenRequestHasNone() {
        when(tripRecordRepository.selectKeyIncludingDeleted(any(), any())).thenReturn(null);

        service.saveTrip(saveRequest(null), "user-42");

        ArgumentCaptor<TripRecord> insertCaptor = ArgumentCaptor.forClass(TripRecord.class);
        verify(tripRecordRepository).insert(insertCaptor.capture());
        assertNotNull(insertCaptor.getValue().getTripId(), "缺 trip_id 时应由服务端补齐，不能带 null 落库");
        assertTrue(insertCaptor.getValue().getTripId().startsWith("trip_"));
    }

    @Test
    void saveTripReassignsTripIdWhenTakenByAnotherUser() {
        // 历史遗留的确定性 trip_id（如 trip_成都_2026-09-09）可能已被别人占用：
        // 此时不能硬插（撞唯一键 500），要由服务端改派唯一 id 后落库
        when(tripRecordRepository.selectKeyIncludingDeleted(any(), any())).thenReturn(null);
        when(tripRecordRepository.countByTripId("trip-1")).thenReturn(1);

        service.saveTrip(saveRequest("trip-1"), "user-42");

        ArgumentCaptor<TripRecord> insertCaptor = ArgumentCaptor.forClass(TripRecord.class);
        verify(tripRecordRepository).insert(insertCaptor.capture());
        assertNotEquals("trip-1", insertCaptor.getValue().getTripId(), "id 被他人占用时应改派为服务端唯一 id");
        assertTrue(insertCaptor.getValue().getTripId().startsWith("trip_"));
    }

    @Test
    void saveTripRejectsMissingItinerary() {
        TripSaveRequest req = new TripSaveRequest();
        req.setTripId("trip-1");

        assertThrows(IllegalArgumentException.class, () -> service.saveTrip(req, "user-42"),
                "行程内容为空应抛参数异常（控制器回 400），而不是 NPE 变成 500");
    }

    /* ================= 确认去过（确认去过功能） ================= */

    private TripRecord ownedTrip(String tripId, String lastDate, boolean confirmed) {
        TripRecord r = new TripRecord();
        r.setId(1L);
        r.setTripId(tripId);
        r.setUserId("user-42");
        r.setDestination("成都");
        r.setVisitedConfirmed(confirmed);
        Itinerary it = new Itinerary();
        DayPlan day = new DayPlan();
        day.setDate(lastDate);
        it.setDays(List.of(day));
        r.setItinerary(it);
        return r;
    }

    @Test
    void confirmVisited_setsFlagAndReturnsDestination() {
        when(tripRecordRepository.selectOne(any())).thenReturn(ownedTrip("trip-1", "2020-01-01", false));

        String city = service.confirmVisited("trip-1", "user-42");

        assertEquals("成都", city);
        ArgumentCaptor<TripRecord> captor = ArgumentCaptor.forClass(TripRecord.class);
        verify(tripRecordRepository).updateById(captor.capture());
        assertTrue(Boolean.TRUE.equals(captor.getValue().getVisitedConfirmed()), "确认后应置 visited_confirmed=true");
    }

    @Test
    void confirmVisited_futureTrip_throws() {
        when(tripRecordRepository.selectOne(any())).thenReturn(ownedTrip("trip-1", "2099-01-01", false));

        assertThrows(IllegalArgumentException.class, () -> service.confirmVisited("trip-1", "user-42"),
                "未来行程不可确认去过");
        verify(tripRecordRepository, never()).updateById(any());
    }

    @Test
    void confirmVisited_notFound_throws() {
        when(tripRecordRepository.selectOne(any())).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.confirmVisited("trip-1", "user-42"),
                "行程不存在应报错");
        verify(tripRecordRepository, never()).updateById(any());
    }

    @Test
    void unconfirmVisited_setsFlagFalse() {
        when(tripRecordRepository.selectOne(any())).thenReturn(ownedTrip("trip-1", "2020-01-01", true));

        service.unconfirmVisited("trip-1", "user-42");

        ArgumentCaptor<TripRecord> captor = ArgumentCaptor.forClass(TripRecord.class);
        verify(tripRecordRepository).updateById(captor.capture());
        assertTrue(Boolean.FALSE.equals(captor.getValue().getVisitedConfirmed()), "撤销后应置 visited_confirmed=false");
    }

    @Test
    void unconfirmVisited_alreadyUnconfirmed_noop() {
        when(tripRecordRepository.selectOne(any())).thenReturn(ownedTrip("trip-1", "2020-01-01", false));

        service.unconfirmVisited("trip-1", "user-42");

        verify(tripRecordRepository, never()).updateById(any());
    }
}
