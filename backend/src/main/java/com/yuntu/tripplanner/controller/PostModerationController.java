package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.model.PostPage;
import com.yuntu.tripplanner.model.User;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.CommunityUserService;
import com.yuntu.tripplanner.service.PostReportService;
import com.yuntu.tripplanner.service.PostService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 社区管理控制器（阶段二 §10.4 + 阶段四任务 4 完善）：审核队列 / 通过 / 拒绝 / 隐藏 /
 * 举报处理 / 帖子全量治理 / 举报历史 / 用户列表。
 *
 * <p>所有动作服务端校验 CONTENT_REVIEW 权限（CommunityUserService.requirePermission → 无权限 403），
 * 不依赖前端隐藏按钮；审核动作记录处理人（handled_by 由登录态 userId 决定）。
 */
@Slf4j
@RestController
@RequestMapping("/community/moderation")
public class PostModerationController {

    private final PostService postService;
    private final PostReportService reportService;
    private final CommunityUserService communityUserService;

    public PostModerationController(PostService postService,
                                    PostReportService reportService,
                                    CommunityUserService communityUserService) {
        this.postService = postService;
        this.reportService = reportService;
        this.communityUserService = communityUserService;
    }

    /** 待审核帖子队列（管理员） */
    @GetMapping("/posts/pending")
    public ResponseEntity<Map<String, Object>> pendingPosts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int pageSize) {
        PostPage result = postService.moderationQueue(UserContext.getUserId(), page, pageSize);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("items", result.getItems());
        body.put("total", result.getTotal());
        body.put("page", result.getPage());
        return ResponseEntity.ok(body);
    }

    /** 通过（PENDING_REVIEW → PUBLISHED） */
    @PostMapping("/posts/{postId}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable Long postId) {
        postService.approve(UserContext.getUserId(), postId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "已通过并发布");
        return ResponseEntity.ok(body);
    }

    /** 拒绝（PENDING_REVIEW → REJECTED，必须带原因） */
    @PostMapping("/posts/{postId}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable Long postId,
                                                      @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        postService.reject(UserContext.getUserId(), postId, reason);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "已拒绝");
        return ResponseEntity.ok(resp);
    }

    /** 隐藏下架（PUBLISHED → HIDDEN） */
    @PostMapping("/posts/{postId}/hide")
    public ResponseEntity<Map<String, Object>> hide(@PathVariable Long postId) {
        postService.hide(UserContext.getUserId(), postId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "已隐藏下架");
        return ResponseEntity.ok(resp);
    }

    /** 待处理举报队列（管理员） */
    @GetMapping("/reports/pending")
    public ResponseEntity<Map<String, Object>> pendingReports(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PostReportService.ReportPage result =
                reportService.pending(UserContext.getUserId(), page, pageSize);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("items", result.getItems());
        body.put("total", result.getTotal());
        return ResponseEntity.ok(body);
    }

    /** 处理举报：RESOLVE（成立→隐藏帖子/删评论）/ DISMISS（驳回） */
    @PostMapping("/reports/{reportId}/handle")
    public ResponseEntity<Map<String, Object>> handleReport(@PathVariable Long reportId,
                                                            @RequestBody(required = false) Map<String, String> body) {
        String action = body == null ? null : body.get("action");
        String note = body == null ? null : body.get("note");
        reportService.handle(UserContext.getUserId(), reportId, action, note);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "举报已处理");
        return ResponseEntity.ok(resp);
    }

    /* ================= 阶段四任务 4：管理后台完善 ================= */

    /** 已发布/已隐藏帖子全量治理（管理员）：PUBLISHED 可下架、HIDDEN 可恢复（approve） */
    @GetMapping("/posts")
    public ResponseEntity<Map<String, Object>> managePosts(
            @RequestParam(defaultValue = "PUBLISHED") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PostPage result = postService.adminPosts(UserContext.getUserId(), status, page, pageSize);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("items", result.getItems());
        body.put("total", result.getTotal());
        body.put("page", result.getPage());
        return ResponseEntity.ok(body);
    }

    /** 已处理举报历史（管理员）：RESOLVED/DISMISSED，含处理人与时间 */
    @GetMapping("/reports/history")
    public ResponseEntity<Map<String, Object>> reportHistory(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PostReportService.ReportPage result =
                reportService.history(UserContext.getUserId(), page, pageSize);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("items", result.getItems());
        body.put("total", result.getTotal());
        return ResponseEntity.ok(body);
    }

    /** 用户列表（管理员）：新注册在前，附每人已发布帖子数（社区治理/审计用） */
    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> users(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        communityUserService.requirePermission(UserContext.getUserId(), AdminPermission.CONTENT_REVIEW);
        List<User> users = communityUserService.listUsers(page, pageSize);
        Map<String, Long> counts = postService.publishedCounts(
                users.stream().map(u -> String.valueOf(u.getId())).toList());
        List<Map<String, Object>> items = new ArrayList<>();
        for (User u : users) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(u.getId()));
            m.put("username", u.getUsername());
            m.put("nickname", u.getNickname());
            m.put("role", u.getRole() == null ? "USER" : u.getRole());
            m.put("created_at", u.getCreatedAt() == null ? null
                    : u.getCreatedAt().toString().replace("T", " "));
            m.put("post_count", counts.getOrDefault(String.valueOf(u.getId()), 0L));
            items.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("items", items);
        body.put("total", communityUserService.countUsers());
        return ResponseEntity.ok(body);
    }
}
