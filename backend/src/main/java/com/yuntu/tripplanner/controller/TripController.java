package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.agent.TravelAgent;
import com.yuntu.tripplanner.exception.CityValidationException;
import com.yuntu.tripplanner.model.*;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.CityValidator;
import com.yuntu.tripplanner.service.TripGenerationFinalizer;
import com.yuntu.tripplanner.service.TripRecordService;
import com.yuntu.tripplanner.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 行程控制器
 */
@Slf4j
@RestController
@RequestMapping("/trip")
public class TripController {

    private final TravelAgent travelAgent;
    private final TripRecordService tripRecordService;
    private final CityValidator cityValidator;
    private final UserProfileService userProfileService;
    private final TripGenerationFinalizer tripGenerationFinalizer;

    public TripController(TravelAgent travelAgent, TripRecordService tripRecordService,
                          CityValidator cityValidator, UserProfileService userProfileService,
                          TripGenerationFinalizer tripGenerationFinalizer) {
        this.travelAgent = travelAgent;
        this.tripRecordService = tripRecordService;
        this.cityValidator = cityValidator;
        this.userProfileService = userProfileService;
        this.tripGenerationFinalizer = tripGenerationFinalizer;
    }

    /**
     * 注入用户上下文：从 ThreadLocal 捕获当前登录用户 id（异步线程读不到，必须在主线程读），
     * 并基于历史行程构建长期记忆画像文本。未登录或构建失败 → 静默降级，不影响生成。
     */
    private void attachUserContext(TripRequest request) {
        String userId = UserContext.getUserId();
        if (userId == null || userId.isBlank()) {
            return;
        }
        request.setUserId(userId);
        try {
            String memory = userProfileService.buildMemoryText(userId, request);
            if (memory != null && !memory.isBlank()) {
                request.setUserMemory(memory);
                log.info("用户长期记忆注入：{}（{}）", userId, memory.length());
            }
        } catch (Exception e) {
            log.warn("构建用户记忆失败（降级，不影响生成）: {}", e.getMessage());
        }
    }

    /** 生成前城市名校验：失败抛异常 → 全局处理器回 400，不启动 Agent；成功可能用规范化名覆盖输入 */
    private void validateDestination(TripRequest request) {
        CityValidationResult result = cityValidator.validate(request.getDestination());
        if (!result.valid()) {
            throw new CityValidationException(result.message(request.getDestination()));
        }
        if (result.normalizedCity() != null) {
            log.info("目的地规范化: {} -> {}", request.getDestination(), result.normalizedCity());
            request.setDestination(result.normalizedCity());
        }
    }

    /**
     * 统一生成收尾（非流式入口；与流式入口语义一致，见 PLAN §8.4 问题六）：
     * 真实生成成功 → 推荐理由回填 + 推荐日志 + 候选证据落库 + 个性化摘要（统一收尾服务），
     * 失败仅告警，不阻断响应。
     */
    private void finalizeGeneration(TripRequest request, AgentTraceResponse response) {
        if (response == null || !Boolean.TRUE.equals(response.getSuccess())
                || response.getItinerary() == null || request == null
                || request.getUserId() == null || request.getUserId().isBlank()) {
            return;
        }
        try {
            tripGenerationFinalizer.finalizeGeneration(request.getUserId(), request, response);
        } catch (Exception e) {
            log.warn("行程统一收尾失败（不影响生成）: {}", e.getMessage());
        }
    }

    /**
     * 获取历史行程列表
     */
    @GetMapping
    public ResponseEntity<TripListResponse> getTripList() {
        try {
            TripListResponse response = tripRecordService.getTripList(UserContext.getUserId());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取行程列表失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * 生成行程（无轨迹）
     */
    @PostMapping("/generate")
    public ResponseEntity<?> generateTrip(@Valid @RequestBody TripRequest request) {
        validateDestination(request);
        attachUserContext(request);
        try {
            log.info("生成行程请求: {}", request.getDestination());

            AgentTraceResponse response = travelAgent.execute(request);

            // 统一收尾（与流式入口一致）：理由回填/推荐日志/候选证据/个性化摘要；失败不阻断响应
            finalizeGeneration(request, response);

            if (Boolean.TRUE.equals(response.getSuccess()) && response.getItinerary() != null) {
                return ResponseEntity.ok(response.getItinerary());
            }
            String message = response.getErrors().isEmpty() ? "行程生成失败" : response.getErrors().get(0);
            Map<String, Object> errorBody = new HashMap<>();
            errorBody.put("success", false);
            errorBody.put("message", message);
            return ResponseEntity.internalServerError().body(errorBody);

        } catch (Exception e) {
            log.error("生成行程失败", e);
            Map<String, Object> errorBody = new HashMap<>();
            errorBody.put("success", false);
            errorBody.put("message", "生成行程失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorBody);
        }
    }
    
    /**
     * 生成行程（带轨迹）
     */
    @PostMapping("/generate-with-trace")
    public ResponseEntity<?> generateTripWithTrace(@Valid @RequestBody TripRequest request) {
        validateDestination(request);
        attachUserContext(request);
        try {
            log.info("生成行程（带轨迹）请求: {}", request.getDestination());

            AgentTraceResponse response = travelAgent.execute(request);

            // 统一收尾（与流式入口一致）：理由回填/推荐日志/候选证据/个性化摘要；失败不阻断响应
            finalizeGeneration(request, response);

            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("生成行程失败", e);
            
            AgentTraceResponse errorResponse = new AgentTraceResponse();
            errorResponse.setSuccess(false);
            errorResponse.getErrors().add("生成失败: " + e.getMessage());
            
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
    
    /**
     * 保存行程
     */
    @PostMapping("/save")
    public ResponseEntity<Map<String, String>> saveTrip(@RequestBody TripSaveRequest request) {
        String tripId = request == null ? null : request.getTripId();
        try {
            log.info("保存行程: {}", tripId);

            tripRecordService.saveTrip(request, UserContext.getUserId());

            Map<String, String> response = new HashMap<>();
            response.put("message", "保存成功");
            response.put("trip_id", request.getTripId());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            // 参数问题（行程内容为空等）：回 400，文案可读
            log.warn("保存行程参数非法: {}", e.getMessage());

            Map<String, String> response = new HashMap<>();
            response.put("message", "保存失败: " + e.getMessage());
            response.put("trip_id", tripId);

            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            // 细节只进日志，不回吐给前端（避免把 SQL/表结构暴露到响应体）
            log.error("保存行程失败", e);

            Map<String, String> response = new HashMap<>();
            response.put("message", "保存失败，请稍后重试");
            response.put("trip_id", tripId);

            return ResponseEntity.internalServerError().body(response);
        }
    }
    
    /**
     * 获取行程详情
     */
    @GetMapping("/{trip_id}")
    public ResponseEntity<TripDetailResponse> getTripDetail(@PathVariable("trip_id") String tripId) {
        try {
            log.info("获取行程详情: {}", tripId);
            
            TripDetailResponse response = tripRecordService.getTripDetail(tripId, UserContext.getUserId());
            
            if (response != null) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.notFound().build();
            }
            
        } catch (Exception e) {
            log.error("获取行程详情失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * 删除行程
     */
    @DeleteMapping("/{trip_id}")
    public ResponseEntity<Map<String, String>> deleteTrip(@PathVariable("trip_id") String tripId) {
        try {
            log.info("删除行程: {}", tripId);
            
            tripRecordService.deleteTrip(tripId, UserContext.getUserId());
            
            Map<String, String> response = new HashMap<>();
            response.put("message", "删除成功");
            response.put("trip_id", tripId);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("删除行程失败", e);
            
            Map<String, String> response = new HashMap<>();
            response.put("message", "删除失败: " + e.getMessage());
            response.put("trip_id", tripId);
            
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 确认去过（确认去过功能）：把该行程目的地计入"去过"。
     * 未来行程会被服务端拦截（400，文案可读）。返回城市供前端祝福语插值。
     */
    @PostMapping("/{trip_id}/confirm-visited")
    public ResponseEntity<Map<String, Object>> confirmVisited(@PathVariable("trip_id") String tripId) {
        try {
            String city = tripRecordService.confirmVisited(tripId, UserContext.getUserId());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("city", city);
            body.put("suggest_post", true);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(body);
        } catch (Exception e) {
            log.error("确认去过失败", e);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", "确认去过失败，请稍后重试");
            return ResponseEntity.internalServerError().body(body);
        }
    }

    /** 撤销"确认去过"（幂等） */
    @PostMapping("/{trip_id}/unconfirm-visited")
    public ResponseEntity<Map<String, Object>> unconfirmVisited(@PathVariable("trip_id") String tripId) {
        try {
            tripRecordService.unconfirmVisited(tripId, UserContext.getUserId());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("撤销确认去过失败", e);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", "撤销失败，请稍后重试");
            return ResponseEntity.internalServerError().body(body);
        }
    }
}
