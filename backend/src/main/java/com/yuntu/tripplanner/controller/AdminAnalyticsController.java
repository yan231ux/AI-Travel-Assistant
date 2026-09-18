package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.AdminAnalyticsService;
import com.yuntu.tripplanner.service.CommunityUserService;
import com.yuntu.tripplanner.service.TravelEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端数据看板聚合接口（设计方案 §5.3 六张图，阶段四可视化）。
 *
 * <p>前缀 /admin/analytics 全部要求 ANALYTICS_VIEW 权限（只读看板，管理端各类角色均可查看）。
 * <b>例外：</b>{@code POST /recompute} 是<b>写操作</b>（会改预聚合表），且不属于任何可下放的
 * 业务域 —— 它只要求 SUPER_ADMIN，绝不能沿用只读的 ANALYTICS_VIEW，否则"能看看板"就等于
 * "能触发全量重算"。
 * 窗口参数 days 限 1~90（防全量扫描）；
 * 响应统一带 degraded/errors（统计失败显式降级，不伪装成 0，见 AdminAnalyticsService）。
 */
@Slf4j
@RestController
@RequestMapping("/admin/analytics")
public class AdminAnalyticsController {

    private final AdminAnalyticsService analyticsService;
    private final CommunityUserService communityUserService;
    private final TravelEventService travelEventService;

    public AdminAnalyticsController(AdminAnalyticsService analyticsService,
                                    CommunityUserService communityUserService,
                                    TravelEventService travelEventService) {
        this.analyticsService = analyticsService;
        this.communityUserService = communityUserService;
        this.travelEventService = travelEventService;
    }

    /** 图表一：景点规划采用排行（days 窗口 + city 筛选） */
    @GetMapping("/spot-adopt")
    public ResponseEntity<Map<String, Object>> spotAdopt(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String city) {
        communityUserService.requirePermission(UserContext.getUserId(), AdminPermission.ANALYTICS_VIEW);
        return ResponseEntity.ok(analyticsService.spotAdoption(clampDays(days), city));
    }

    /** 图表二：城市热度排行 */
    @GetMapping("/city-heat")
    public ResponseEntity<Map<String, Object>> cityHeat(@RequestParam(defaultValue = "30") int days) {
        communityUserService.requirePermission(UserContext.getUserId(), AdminPermission.ANALYTICS_VIEW);
        return ResponseEntity.ok(analyticsService.cityHeat(clampDays(days)));
    }

    /** 图表三：内容审核漏斗 */
    @GetMapping("/moderation-funnel")
    public ResponseEntity<Map<String, Object>> moderationFunnel(@RequestParam(defaultValue = "30") int days) {
        communityUserService.requirePermission(UserContext.getUserId(), AdminPermission.ANALYTICS_VIEW);
        return ResponseEntity.ok(analyticsService.moderationFunnel(clampDays(days)));
    }

    /** 图表四：内容风险构成 */
    @GetMapping("/risk-breakdown")
    public ResponseEntity<Map<String, Object>> riskBreakdown(@RequestParam(defaultValue = "30") int days) {
        communityUserService.requirePermission(UserContext.getUserId(), AdminPermission.ANALYTICS_VIEW);
        return ResponseEntity.ok(analyticsService.riskBreakdown(clampDays(days)));
    }

    /** 图表五：推荐/规划趋势 */
    @GetMapping("/trend")
    public ResponseEntity<Map<String, Object>> trend(@RequestParam(defaultValue = "14") int days) {
        communityUserService.requirePermission(UserContext.getUserId(), AdminPermission.ANALYTICS_VIEW);
        return ResponseEntity.ok(analyticsService.trend(clampDays(days)));
    }

    /** 图表六：RAG 索引状态 */
    @GetMapping("/rag-status")
    public ResponseEntity<Map<String, Object>> ragStatus() {
        communityUserService.requirePermission(UserContext.getUserId(), AdminPermission.ANALYTICS_VIEW);
        return ResponseEntity.ok(analyticsService.ragStatus());
    }

    /**
     * 立即重算近 N 天的热度/审核预聚合（幂等 DELETE + INSERT...SELECT）。
     *
     * <p>用途：定时任务之外的补偿入口——看板数据滞后、或停机后需要立刻补数时手工触发。
     * 窗口限 1~90 天（与查询参数同口径）；返回实际重算的窗口与成功维度数。
     *
     * <p><b>权限：仅超级管理员</b>（写操作，见类注释）。
     */
    @PostMapping("/recompute")
    public ResponseEntity<Map<String, Object>> recompute(@RequestParam(defaultValue = "7") int days) {
        communityUserService.requireSuperAdmin(UserContext.getUserId());
        int window = clampDays(days);
        LocalDate today = LocalDate.now();
        int okDimensions = 0;
        for (int i = 0; i < window; i++) {
            okDimensions += travelEventService.aggregateAll(today.minusDays(i));
        }
        log.info("管理端手工重算预聚合：窗口 {} 天，成功维度 {} 个", window, okDimensions);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("days", window);
        body.put("from", today.minusDays(window - 1L).toString());
        body.put("to", today.toString());
        body.put("recomputed_dimensions", okDimensions);
        body.put("expected_dimensions", window * 3);
        body.put("message", "热度与审核预聚合重算完成");
        return ResponseEntity.ok(body);
    }

    private static int clampDays(int days) {
        return Math.max(1, Math.min(days, 90));
    }
}
