package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.model.*;
import com.yuntu.tripplanner.repository.AgentTraceRepository;
import com.yuntu.tripplanner.repository.TripRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    public TripRecordService(TripRecordRepository tripRecordRepository,
                             AgentTraceRepository agentTraceRepository,
                             ObjectMapper objectMapper) {
        this.tripRecordRepository = tripRecordRepository;
        this.agentTraceRepository = agentTraceRepository;
        this.objectMapper = objectMapper;
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
     * 仅当 trip_id 属于当前用户时更新；否则作为新行程插入（trip_id 为 UUID，跨用户冲突概率可忽略）。
     */
    @Transactional
    public void saveTrip(TripSaveRequest request, String userId) {
        // 检查当前用户是否已有该行程
        TripRecord existing = tripRecordRepository.selectOne(
                new LambdaQueryWrapper<TripRecord>()
                        .eq(TripRecord::getTripId, request.getTripId())
                        .eq(TripRecord::getUserId, userId)
        );

        if (existing != null) {
            // 更新
            existing.setItinerary(request.getItinerary());
            existing.setUserId(userId);
            tripRecordRepository.updateById(existing);
        } else {
            // 新增
            TripRecord record = new TripRecord();
            record.setTripId(request.getTripId());
            record.setDestination(request.getItinerary().getDestination());
            record.setItinerary(request.getItinerary());
            record.setUserId(userId);
            tripRecordRepository.insert(record);
        }

        saveAgentTrace(request);
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
        
        if (record.getItinerary() != null) {
            item.setSummary(record.getItinerary().getSummary());
        }
        
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