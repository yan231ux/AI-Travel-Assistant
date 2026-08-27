package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.agent.TravelAgent;
import com.yuntu.tripplanner.exception.CityValidationException;
import com.yuntu.tripplanner.model.*;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.CityValidator;
import com.yuntu.tripplanner.service.TripRecordService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
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

    public TripController(TravelAgent travelAgent, TripRecordService tripRecordService,
                          CityValidator cityValidator) {
        this.travelAgent = travelAgent;
        this.tripRecordService = tripRecordService;
        this.cityValidator = cityValidator;
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
        try {
            log.info("生成行程请求: {}", request.getDestination());

            AgentTraceResponse response = travelAgent.execute(request);

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
        try {
            log.info("生成行程（带轨迹）请求: {}", request.getDestination());

            AgentTraceResponse response = travelAgent.execute(request);

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
        try {
            log.info("保存行程: {}", request.getTripId());
            
            tripRecordService.saveTrip(request, UserContext.getUserId());
            
            Map<String, String> response = new HashMap<>();
            response.put("message", "保存成功");
            response.put("trip_id", request.getTripId());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("保存行程失败", e);
            
            Map<String, String> response = new HashMap<>();
            response.put("message", "保存失败: " + e.getMessage());
            response.put("trip_id", request.getTripId());
            
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
}