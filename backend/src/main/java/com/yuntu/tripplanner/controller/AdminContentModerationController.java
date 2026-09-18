package com.yuntu.tripplanner.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.model.ContentModerationTask;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.CommunityUserService;
import com.yuntu.tripplanner.service.ContentModerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 内容审核管理端接口（设计方案 §4.7，阶段三）。
 *
 * <p>前缀 /admin 全部要求 CONTENT_REVIEW 权限（服务端校验，不依赖前端隐藏按钮）。
 * 对应前端页 /admin/content/ai-review：队列列表（风险等级/分/决策/规则命中数）+
 * 详情（原文/规则命中/AI 风险解释）+ 人工决策（覆盖 AI 结论必须填原因）+ 失败重试。
 */
@Slf4j
@RestController
@RequestMapping("/admin/content/moderation")
public class AdminContentModerationController {

    private final ContentModerationService moderationService;
    private final CommunityUserService communityUserService;

    public AdminContentModerationController(ContentModerationService moderationService,
                                            CommunityUserService communityUserService) {
        this.moderationService = moderationService;
        this.communityUserService = communityUserService;
    }

    /** 审核队列分页（可按状态/对象类型过滤） */
    @GetMapping
    public ResponseEntity<Map<String, Object>> queue(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String targetType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int pageSize) {
        communityUserService.requirePermission(UserContext.getUserId(), AdminPermission.CONTENT_REVIEW);
        Page<ContentModerationTask> result = moderationService.page(status, targetType, page, pageSize);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("items", result.getRecords());
        body.put("total", result.getTotal());
        body.put("page", page);
        return ResponseEntity.ok(body);
    }

    /** 任务详情（原文快照 + 规则命中 + AI 结构化输出） */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long id) {
        communityUserService.requirePermission(UserContext.getUserId(), AdminPermission.CONTENT_REVIEW);
        ContentModerationTask task = moderationService.get(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", task != null);
        body.put("data", task);
        if (task == null) {
            body.put("message", "审核任务不存在");
        }
        return ResponseEntity.ok(body);
    }

    /** 人工决策：APPROVE / REJECT（覆盖 AI 结论，REJECT 必须填原因，全程审计） */
    @PostMapping("/{id}/decide")
    public ResponseEntity<Map<String, Object>> decide(@PathVariable Long id,
                                                      @RequestBody Map<String, String> body) {
        String adminId = UserContext.getUserId();
        communityUserService.requirePermission(adminId, AdminPermission.CONTENT_REVIEW);
        String action = body == null ? null : body.get("action");
        String reason = body == null ? null : body.get("reason");
        moderationService.decide(adminId, id, action, reason);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", ContentModerationTask.DECISION_APPROVE.equals(action) ? "已通过" : "已拒绝");
        return ResponseEntity.ok(resp);
    }

    /** 失败/待审任务重试（重跑规则+AI 流水线） */
    @PostMapping("/{id}/retry")
    public ResponseEntity<Map<String, Object>> retry(@PathVariable Long id) {
        String adminId = UserContext.getUserId();
        communityUserService.requirePermission(adminId, AdminPermission.CONTENT_REVIEW);
        moderationService.retry(adminId, id);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "已重新排队");
        return ResponseEntity.ok(resp);
    }
}
