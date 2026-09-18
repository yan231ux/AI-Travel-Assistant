package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.model.PostPage;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.AdminDashboardService;
import com.yuntu.tripplanner.service.AdminEvidenceService;
import com.yuntu.tripplanner.service.PostService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理后台核心控制器（管理员后台与内容运营中心设计方案：独立 /admin 空间）。
 *
 * <p>仅提供"证据聚合/看板"类只读接口：帖子审核详情、举报证据详情、后台首页汇总；
 * 审核动作（通过/拒绝/下架）与举报处理沿用既有接口（各自服务端校验权限 + 审计），
 * 避免维护两份写路径。权限分层：审核/举报证据要求 {@code CONTENT_REVIEW}，
 * 后台首页汇总只要求"属于管理端"（各角色进后台都能看到自己的待办概览）。
 */
@Slf4j
@RestController
@RequestMapping("/admin")
public class AdminContentController {

    private final AdminDashboardService dashboardService;
    private final AdminEvidenceService evidenceService;
    private final PostService postService;

    public AdminContentController(AdminDashboardService dashboardService,
                                  AdminEvidenceService evidenceService,
                                  PostService postService) {
        this.dashboardService = dashboardService;
        this.evidenceService = evidenceService;
        this.postService = postService;
    }

    /** 后台首页汇总（待办/内容概览/最近操作） */
    @GetMapping("/dashboard/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", dashboardService.summary(UserContext.getUserId()));
        return ResponseEntity.ok(body);
    }

    /** 帖子审核/治理队列：status=PENDING_REVIEW 待审 / PUBLISHED 已发布 / HIDDEN 已隐藏 */
    @GetMapping("/review/posts")
    public ResponseEntity<Map<String, Object>> reviewPosts(
            @RequestParam(defaultValue = "PENDING_REVIEW") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        String adminId = UserContext.getUserId();
        PostPage result;
        if ("PENDING_REVIEW".equalsIgnoreCase(status.trim())) {
            result = postService.moderationQueue(adminId, page, pageSize);
        } else {
            result = postService.adminPosts(adminId, status.trim().toUpperCase(), page, pageSize);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("items", result.getItems());
        body.put("total", result.getTotal());
        body.put("page", result.getPage());
        return ResponseEntity.ok(body);
    }

    /** 帖子审核证据详情（管理员专用，替代跳普通用户 PostDetail） */
    @GetMapping("/review/posts/{postId}")
    public ResponseEntity<Map<String, Object>> postReview(@PathVariable Long postId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", evidenceService.postReview(UserContext.getUserId(), postId));
        return ResponseEntity.ok(body);
    }

    /** 举报证据详情（完整被举报内容 + 上下文 + 历史处理） */
    @GetMapping("/reports/{reportId}")
    public ResponseEntity<Map<String, Object>> reportEvidence(@PathVariable Long reportId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", evidenceService.reportEvidence(UserContext.getUserId(), reportId));
        return ResponseEntity.ok(body);
    }
}
