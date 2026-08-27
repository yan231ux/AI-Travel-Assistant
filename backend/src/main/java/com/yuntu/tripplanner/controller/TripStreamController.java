package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.agent.AgentCallback;
import com.yuntu.tripplanner.agent.TravelAgent;
import com.yuntu.tripplanner.exception.CityValidationException;
import com.yuntu.tripplanner.model.AgentTraceResponse;
import com.yuntu.tripplanner.model.AgentTraceStep;
import com.yuntu.tripplanner.model.CityValidationResult;
import com.yuntu.tripplanner.model.TripRequest;
import com.yuntu.tripplanner.service.CityValidator;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 生成过程 SSE 流式端点。
 *
 * POST /trip/generate-stream 立即返回 SseEmitter，生成在 agentExecutor 独立线程上执行，
 * 每完成一个阶段推送一条 step，阶段间推送 progress，最后推送 itinerary + done。
 * 事件类型：progress / step / itinerary / done / error
 */
@Slf4j
@RestController
@RequestMapping("/trip")
public class TripStreamController {

    private final TravelAgent travelAgent;
    private final Executor agentExecutor;
    private final CityValidator cityValidator;

    public TripStreamController(TravelAgent travelAgent,
                                @Qualifier("agentExecutor") Executor agentExecutor,
                                CityValidator cityValidator) {
        this.travelAgent = travelAgent;
        this.agentExecutor = agentExecutor;
        this.cityValidator = cityValidator;
    }

    @PostMapping(value = "/generate-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> generateStream(@Valid @RequestBody TripRequest request) {
        // 城市名校验：假城市抛异常 → 全局处理器回 400（前端 streamGenerateTrip 读 message 提示），不启动 Agent。
        // 注意必须抛异常而不能手工拼错误体——SSE 靠泛型 <SseEmitter> 才被识别，见 CityValidationException 注释。
        CityValidationResult cityResult = cityValidator.validate(request.getDestination());
        if (!cityResult.valid()) {
            throw new CityValidationException(cityResult.message(request.getDestination()));
        }
        if (cityResult.normalizedCity() != null) {
            request.setDestination(cityResult.normalizedCity());
        }

        // 0 = 无超时（生成可能超过默认 30s）
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean closed = new AtomicBoolean(false);

        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(t -> closed.set(true));

        AgentCallback callback = new AgentCallback() {
            @Override
            public void onStep(AgentTraceStep step) {
                send(emitter, closed, "step", step);
            }

            @Override
            public void onProgress(String phase, String message) {
                send(emitter, closed, "progress", Map.of("phase", phase, "message", message));
            }

            @Override
            public boolean isClosed() {
                return closed.get();
            }
        };

        CompletableFuture.runAsync(() -> {
            AgentTraceResponse resp = travelAgent.execute(request, callback);
            try {
                if (closed.get()) {
                    return; // 客户端已断开，直接收尾
                }
                if (Boolean.TRUE.equals(resp.getSuccess()) && resp.getItinerary() != null) {
                    send(emitter, closed, "itinerary", resp.getItinerary());
                    send(emitter, closed, "done", Map.of(
                            "token_usage", resp.getTokenUsage(),
                            "collected_data", resp.getCollectedData()));
                } else {
                    String msg = resp.getErrors().isEmpty() ? "行程生成失败" : resp.getErrors().get(0);
                    send(emitter, closed, "error", Map.of("message", msg));
                }
            } finally {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 已 complete 时抛 IllegalStateException，忽略
                }
            }
        }, agentExecutor);

        return ResponseEntity.ok(emitter);
    }

    /**
     * 统一发帧：客户端断开（send 抛 IOException）→ 置 closed，停止后续发送。
     * 回调内必须吞掉一切异常，绝不向上抛，否则会被 Agent 的 try/catch 当成生成失败。
     */
    private void send(SseEmitter emitter, AtomicBoolean closed, String event, Object data) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception e) {
            log.debug("SSE 发送失败（客户端可能断开）: {}", e.getMessage());
            closed.set(true);
        }
    }
}
