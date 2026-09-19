package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.model.*;
import com.yuntu.tripplanner.repository.AgentTraceRepository;
import com.yuntu.tripplanner.repository.TripRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 行程记录服务
 */
@Slf4j
@Service
public class TripRecordService {

    private final TripRecordRepository tripRecordRepository;
    private final AgentTraceRepository agentTraceRepository;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final TravelEventService travelEventService;

    public TripRecordService(TripRecordRepository tripRecordRepository,
                             AgentTraceRepository agentTraceRepository,
                             ObjectMapper objectMapper,
                             AuditService auditService,
                             TravelEventService travelEventService) {
        this.tripRecordRepository = tripRecordRepository;
        this.agentTraceRepository = agentTraceRepository;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.travelEventService = travelEventService;
    }
    
    /**
     * 获取行程列表（仅当前用户，按创建时间倒序）
     */
    public TripListResponse getTripList(String userId) {
        LambdaQueryWrapper<TripRecord> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(TripRecord::getUserId, userId)
                .orderByDesc(TripRecord::getCreatedAt);

        List<TripRecord> records = tripRecordRepository.selectList(queryWrapper);
        
        List<TripSummaryItem> items = records.stream()
                .map(this::convertToSummaryItem)
                .collect(Collectors.toList());
        
        TripListResponse response = new TripListResponse();
        response.setTotal(items.size());
        response.setItems(items);
        
        return response;
    }

    /**
     * 获取用户最近 N 条行程（按创建时间倒序，用于构建长期记忆画像）。
     * 只读行程主体，不加载 Agent 轨迹（画像不需要）。
     */
    public List<TripRecord> getRecentTrips(String userId, int limit) {
        if (userId == null || userId.isBlank() || limit <= 0) {
            return List.of();
        }
        LambdaQueryWrapper<TripRecord> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(TripRecord::getUserId, userId)
                .orderByDesc(TripRecord::getCreatedAt)
                .last("LIMIT " + limit);
        return tripRecordRepository.selectList(queryWrapper);
    }
    
    /**
     * 保存行程（userId 以服务端解析的登录用户为准，覆盖请求体中的 user_id，防伪造归属）。
     *
     * 幂等语义：同一 (user_id, trip_id) 重复保存 = 更新；否则新增。
     * 两个必须踩住的坑（否则保存直接 500、无记录、管理端审计也看不到）：
     * 1) trip_id 有全局 UNIQUE 约束，所以 id 必须全局唯一（由服务端生成，见 ItineraryGenerator）；
     * 2) 查询必须"包含逻辑删除行"——用户删掉行程后又保存同一 trip_id 时，
     *    常规查询因 @TableLogic 过滤不到旧行会误判为新增，INSERT 撞唯一键。此时应复活旧行。
     */
    @Transactional
    public void saveTrip(TripSaveRequest request, String userId) {
        if (request == null || request.getItinerary() == null) {
            throw new IllegalArgumentException("行程内容不能为空");
        }
        // 兜底：缺 trip_id（异常载荷/老客户端）时由服务端补一个全局唯一 id，避免撞唯一键
        if (request.getTripId() == null || request.getTripId().isBlank()) {
            request.setTripId("trip_" + UUID.randomUUID().toString().replace("-", ""));
        }

        // 含已删除行查询：命中说明"这条行程存在过"，走更新（必要时先复活）
        TripRecord existing = tripRecordRepository.selectKeyIncludingDeleted(request.getTripId(), userId);
        boolean updating = existing != null;

        if (updating) {
            if (Integer.valueOf(1).equals(existing.getDeleted())) {
                tripRecordRepository.restoreById(existing.getId());
            }
            TripRecord update = new TripRecord();
            update.setId(existing.getId());
            update.setDestination(request.getItinerary().getDestination());
            update.setItinerary(request.getItinerary());
            update.setUserId(userId);
            tripRecordRepository.updateById(update);
        } else {
            // trip_id 全局唯一：若已被其他用户占用（历史遗留的"目的地_日期"式确定性 id），
            // 由服务端改派一个唯一 id，保证保存永远能落库，而不是抛唯一键冲突 500。
            // 改派后的 id 通过响应回给前端（Controller 读 request.getTripId()），前端会回写，避免二次保存又生成新记录。
            if (tripRecordRepository.countByTripId(request.getTripId()) > 0) {
                String reassigned = "trip_" + UUID.randomUUID().toString().replace("-", "");
                log.warn("trip_id {} 已被其他用户占用，服务端改派为 {}", request.getTripId(), reassigned);
                request.setTripId(reassigned);
            }
            TripRecord record = new TripRecord();
            record.setTripId(request.getTripId());
            record.setDestination(request.getItinerary().getDestination());
            record.setItinerary(request.getItinerary());
            record.setUserId(userId);
            tripRecordRepository.insert(record);
        }

        saveAgentTrace(request);
        // 全链路审计（阶段四任务 10）：行程保存/更新（destination 作人读上下文）
        auditService.record(userId, AuditLog.CAT_TRIP, updating ? "trip_updated" : "trip_saved",
                "trip", request.getTripId(),
                AuditService.detailOf("destination", request.getItinerary().getDestination()));
        // 行程事件埋点（阶段二数据地基）：TRIP_SAVED + SPOT_SAVED；事件表自带去重，
        // 幂等保存（同 trip_id 重复保存=更新）不会重复统计。旁路埋点失败不影响保存事务。
        try {
            travelEventService.recordTripSaved(userId, request.getItinerary());
        } catch (Exception e) {
            log.warn("保存行程事件埋点失败（不影响保存）: {}", e.getMessage());
        }
    }

    /**
     * 保存 Agent 推理轨迹（有则替换旧轨迹）
     */
    private void saveAgentTrace(TripSaveRequest request) {
        if (request.getTrace() == null || request.getTrace().isEmpty()) {
            return;
        }
        agentTraceRepository.delete(new LambdaQueryWrapper<AgentTraceRecord>()
                .eq(AgentTraceRecord::getTripId, request.getTripId()));

        for (AgentTraceStep step : request.getTrace()) {
            AgentTraceRecord traceRecord = new AgentTraceRecord();
            traceRecord.setTripId(request.getTripId());
            traceRecord.setStep(step.getStep());
            traceRecord.setThought(step.getThought());
            traceRecord.setAction(step.getAction());
            traceRecord.setObservation(step.getObservation());
            traceRecord.setToolCalls(step.getToolCalls());
            agentTraceRepository.insert(traceRecord);
        }
        log.info("保存行程轨迹 {} 条: {}", request.getTrace().size(), request.getTripId());
    }
    
    /**
     * 获取行程详情（仅当前用户；非本人行程返回 null）
     */
    public TripDetailResponse getTripDetail(String tripId, String userId) {
        TripRecord record = tripRecordRepository.selectOne(
                new LambdaQueryWrapper<TripRecord>()
                        .eq(TripRecord::getTripId, tripId)
                        .eq(TripRecord::getUserId, userId)
        );

        if (record == null) {
            return null;
        }

        return convertToDetailResponse(record);
    }

    /**
     * 删除行程（仅当前用户；非本人行程无操作）
     */
    @Transactional
    public void deleteTrip(String tripId, String userId) {
        tripRecordRepository.delete(
                new LambdaQueryWrapper<TripRecord>()
                        .eq(TripRecord::getTripId, tripId)
                        .eq(TripRecord::getUserId, userId)
        );
        // 全链路审计（阶段四任务 10）：删除本人行程（非本人删除不会命中任何行）
        auditService.record(userId, AuditLog.CAT_TRIP, "trip_deleted",
                "trip", tripId, null);
    }

    /**
     * 确认去过（确认去过功能）：校验归属 + 行程存在；未来行程拦截；置 visited_confirmed=1（幂等）。
     *
     * @return 行程目的地城市（供前端祝福语插值）
     * @throws IllegalArgumentException 行程不存在 / 行程还未出发（未来行程不可确认）
     */
    @Transactional
    public String confirmVisited(String tripId, String userId) {
        TripRecord record = loadOwned(tripId, userId);
        if (record == null) {
            throw new IllegalArgumentException("行程不存在");
        }
        if (isFutureTrip(record)) {
            throw new IllegalArgumentException("行程还未出发，无法确认去过");
        }
        if (!Boolean.TRUE.equals(record.getVisitedConfirmed())) {
            TripRecord update = new TripRecord();
            update.setId(record.getId());
            update.setVisitedConfirmed(true);
            tripRecordRepository.updateById(update);
            auditService.record(userId, AuditLog.CAT_TRIP, "trip_visited_confirmed",
                    "trip", tripId, null);
        }
        return record.getDestination();
    }

    /** 撤销"确认去过"（幂等：非本人/不存在/未确认均无副作用） */
    @Transactional
    public void unconfirmVisited(String tripId, String userId) {
        TripRecord record = loadOwned(tripId, userId);
        if (record == null || !Boolean.TRUE.equals(record.getVisitedConfirmed())) {
            return;
        }
        TripRecord update = new TripRecord();
        update.setId(record.getId());
        update.setVisitedConfirmed(false);
        tripRecordRepository.updateById(update);
        auditService.record(userId, AuditLog.CAT_TRIP, "trip_visited_unconfirmed",
                "trip", tripId, null);
    }

    private TripRecord loadOwned(String tripId, String userId) {
        if (tripId == null || tripId.isBlank() || userId == null || userId.isBlank()) {
            return null;
        }
        return tripRecordRepository.selectOne(new LambdaQueryWrapper<TripRecord>()
                .eq(TripRecord::getTripId, tripId)
                .eq(TripRecord::getUserId, userId));
    }

    /** 行程最后一天是否在将来（未来行程不可确认去过；无日期信息不拦截） */
    private boolean isFutureTrip(TripRecord record) {
        LocalDate max = maxTripDate(record);
        return max != null && max.isAfter(LocalDate.now());
    }

    /** 行程 JSON 各天日期最大值；无有效日期返回 null */
    private LocalDate maxTripDate(TripRecord record) {
        Itinerary it = record.getItinerary();
        if (it == null || it.getDays() == null) {
            return null;
        }
        LocalDate max = null;
        for (DayPlan d : it.getDays()) {
            if (d == null || d.getDate() == null || d.getDate().isBlank()) {
                continue;
            }
            try {
                LocalDate day = LocalDate.parse(d.getDate().trim());
                if (max == null || day.isAfter(max)) {
                    max = day;
                }
            } catch (Exception ignored) {
                // 非法日期忽略
            }
        }
        return max;
    }
    
    /**
     * 转换为摘要项
     */
    private TripSummaryItem convertToSummaryItem(TripRecord record) {
        TripSummaryItem item = new TripSummaryItem();
        item.setTripId(record.getTripId());
        item.setDestination(record.getDestination());
        item.setCreatedAt(record.getCreatedAt());
        item.setUpdatedAt(record.getUpdatedAt());
        item.setConfirmedVisited(Boolean.TRUE.equals(record.getVisitedConfirmed()));

        if (record.getItinerary() != null) {
            item.setSummary(record.getItinerary().getSummary());
        }
        LocalDate maxDate = maxTripDate(record);
        item.setFuture(maxDate != null && maxDate.isAfter(LocalDate.now()));

        return item;
    }
    
    /**
     * 转换为详情响应（附带该行程的 Agent 推理轨迹，供前端回放）
     */
    private TripDetailResponse convertToDetailResponse(TripRecord record) {
        TripDetailResponse response = new TripDetailResponse();
        response.setTripId(record.getTripId());
        response.setItinerary(record.getItinerary());
        response.setCreatedAt(record.getCreatedAt());
        response.setUpdatedAt(record.getUpdatedAt());

        List<AgentTraceRecord> traceRecords = agentTraceRepository.selectList(
                new LambdaQueryWrapper<AgentTraceRecord>()
                        .eq(AgentTraceRecord::getTripId, record.getTripId())
                        .orderByAsc(AgentTraceRecord::getStep));
        response.setTrace(traceRecords.stream().map(TripRecordService::toTraceStep).toList());
        return response;
    }

    /**
     * 数据库轨迹记录 → 响应模型（字段同名直拷）
     */
    private static AgentTraceStep toTraceStep(AgentTraceRecord r) {
        AgentTraceStep step = new AgentTraceStep();
        step.setStep(r.getStep());
        step.setThought(r.getThought());
        step.setAction(r.getAction());
        step.setObservation(r.getObservation());
        step.setToolCalls(r.getToolCalls());
        return step;
    }
}