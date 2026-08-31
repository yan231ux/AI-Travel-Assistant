package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.agent.AgentCallback;
import com.yuntu.tripplanner.agent.TravelAgent;
import com.yuntu.tripplanner.exception.CityValidationException;
import com.yuntu.tripplanner.model.AgentTraceResponse;
import com.yuntu.tripplanner.model.AgentTraceStep;
import com.yuntu.tripplanner.model.CityValidationResult;
import com.yuntu.tripplanner.model.TripRequest;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.CacheService;
import com.yuntu.tripplanner.service.CityValidator;
import com.yuntu.tripplanner.service.UserProfileService;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
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
 *
 * 相同行程参数在 TTL 内命中结果缓存时直接秒回：外部数据已有 Redis 缓存，
 * 但 LLM 的 think/reflect/generate 是串行调用、才是耗时主因，结果缓存把整条链路省掉。
 */
@Slf4j
@RestController
@RequestMapping("/trip")
public class TripStreamController {

    /** 生成结果缓存 TTL（秒）：与天气缓存一致，避免天气过期后返回陈旧行程 */
    private static final long TRIP_CACHE_TTL_SECONDS = 30 * 60;

    /** 缓存 key 版本号：生成逻辑或 LLM 模型切换后 bump，避免旧缓存污染新逻辑/新模型 */
    private static final String TRIP_CACHE_VERSION = "v5";

    private final TravelAgent travelAgent;
    private final Executor agentExecutor;
    private final CityValidator cityValidator;
    private final CacheService cacheService;
    private final UserProfileService userProfileService;

    public TripStreamController(TravelAgent travelAgent,
                                @Qualifier("agentExecutor") Executor agentExecutor,
                                CityValidator cityValidator,
                                CacheService cacheService,
                                UserProfileService userProfileService) {
        this.travelAgent = travelAgent;
        this.agentExecutor = agentExecutor;
        this.cityValidator = cityValidator;
        this.cacheService = cacheService;
        this.userProfileService = userProfileService;
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

        // 注入用户上下文：userId + 长期记忆画像（必须在主线程捕获 ThreadLocal，异步线程读不到）
        attachUserContext(request);

        // 结果缓存：相同行程参数在 TTL 内直接秒回，不再重复跑 Agent（LLM 串行调用是耗时主因）
        String cacheKey = buildTripCacheKey(request);
        AgentTraceResponse cached = cacheService.get(cacheKey, AgentTraceResponse.class);
        if (cached != null && cached.getItinerary() != null) {
            log.info("行程生成缓存命中，直接返回: {}", cacheKey);
            return ResponseEntity.ok(serveCached(cached));
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
            // 生成成功后写入结果缓存（下次同参数直接秒回）
            if (resp != null && Boolean.TRUE.equals(resp.getSuccess()) && resp.getItinerary() != null) {
                cacheService.set(cacheKey, resp, TRIP_CACHE_TTL_SECONDS);
            }
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
     * 缓存命中：构造一个"简化回放"的 SSE 流，推送提示 + 两条 step + 缓存行程，秒回。
     */
    private SseEmitter serveCached(AgentTraceResponse cached) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().name("progress")
                    .data(Map.of("phase", "plan", "message", "相同参数已生成过，直接复用缓存结果")));

            AgentTraceStep planStep = new AgentTraceStep();
            planStep.setStep(1);
            planStep.setAction("plan_search");
            planStep.setThought("缓存命中：此行程参数（目的地/日期/预算/偏好）在 30 分钟内生成过，直接复用结果");
            planStep.setToolCalls(new ArrayList<>());
            emitter.send(SseEmitter.event().name("step").data(planStep));

            AgentTraceStep genStep = new AgentTraceStep();
            genStep.setStep(2);
            genStep.setAction("generate");
            genStep.setThought("从缓存读取上次生成的行程");
            genStep.setObservation("命中缓存，跳过 Agent 循环，秒出结果");
            emitter.send(SseEmitter.event().name("step").data(genStep));

            emitter.send(SseEmitter.event().name("itinerary").data(cached.getItinerary()));
            emitter.send(SseEmitter.event().name("done").data(Map.of(
                    "token_usage", cached.getTokenUsage() == null ? new com.yuntu.tripplanner.model.TokenUsage() : cached.getTokenUsage(),
                    "cached", true)));
        } catch (Exception e) {
            log.debug("缓存结果推送失败: {}", e.getMessage());
        } finally {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 已 complete 时抛 IllegalStateException，忽略
            }
        }
        return emitter;
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

    /**
     * 生成结果缓存 key：对行程参数做 SHA-256 哈希，参数一致即复用同一份结果。
     * 含 userId：个性化后不同用户的记忆不同，缓存必须按用户隔离，防止串味。
     */
    private String buildTripCacheKey(TripRequest r) {
        String raw = String.join("|",
                String.valueOf(r.getUserId()),
                String.valueOf(r.getDestination()),
                String.valueOf(r.getStartDate()),
                String.valueOf(r.getEndDate()),
                String.valueOf(r.getTravelers()),
                String.valueOf(r.getBudget()),
                String.valueOf(r.getHotelLevel()),
                String.valueOf(r.getPace()),
                String.valueOf(r.getPreferences()),
                String.valueOf(r.getDietaryPreferences()),
                String.valueOf(r.getSpecialNotes()));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }
            return "trip:result:" + TRIP_CACHE_VERSION + ":" + sb;
        } catch (Exception e) {
            return "trip:result:" + TRIP_CACHE_VERSION + ":" + Integer.toHexString(raw.hashCode());
        }
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
