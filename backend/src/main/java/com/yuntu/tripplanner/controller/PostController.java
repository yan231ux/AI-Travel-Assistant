package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.model.PostCreateRequest;
import com.yuntu.tripplanner.model.PostDetail;
import com.yuntu.tripplanner.model.PostPage;
import com.yuntu.tripplanner.model.PostUpdateRequest;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.PostService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 帖子控制器（阶段二社区，PRODUCT_EVOLUTION_PLAN §9）——薄门面。
 *
 * <p>公开流只出 PUBLISHED；我的帖子/提交审核带作者态；创建默认 DRAFT，发布必须过审核。
 * 域异常（参数 400 / 非作者 403 / 不存在 404）由 GlobalExceptionHandler 统一映射，
 * 本类不做业务逻辑，保证状态码语义一致。userId 一律取自登录态（不信任请求体）。
 */
@Slf4j
@RestController
@RequestMapping("/community/posts")
public class PostController {

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    /** 公开帖子流：city/postType 过滤 + sort(最新/热门) + 分页 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String postType,
            @RequestParam(required = false, defaultValue = "latest") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int pageSize,
            @RequestParam(required = false, name = "feed_trace_id") String feedTraceId) {
        PostPage result = postService.publicFeed(UserContext.getUserId(), city, postType,
                sort, page, pageSize, feedTraceId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("items", result.getItems());
        body.put("total", result.getTotal());
        body.put("page", result.getPage());
        body.put("pageSize", result.getPageSize());
        body.put("personalized", result.getPersonalized());
        return ResponseEntity.ok(body);
    }

    /** 帖子详情（公开可见；本人可看自己未发布状态；管理员可看待审/隐藏） */
    @GetMapping("/{postId}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long postId) {
        PostDetail detail = postService.detail(UserContext.getUserId(), postId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", detail);
        return ResponseEntity.ok(body);
    }

    /** 我的帖子（全部状态，供编辑/删除/提交/看拒绝原因） */
    @GetMapping("/mine")
    public ResponseEntity<Map<String, Object>> mine(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int pageSize) {
        PostPage result = postService.mine(UserContext.getUserId(), page, pageSize);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("items", result.getItems());
        body.put("total", result.getTotal());
        body.put("page", result.getPage());
        return ResponseEntity.ok(body);
    }

    /** 创建帖子（默认 DRAFT 草稿） */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody(required = false) PostCreateRequest request) {
        Long postId = postService.create(UserContext.getUserId(), request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("postId", postId);
        body.put("message", "草稿已保存，可继续编辑或提交审核");
        return ResponseEntity.ok(body);
    }

    /** 编辑帖子（仅作者本人） */
    @PutMapping("/{postId}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long postId,
                                                      @RequestBody(required = false) PostUpdateRequest request) {
        postService.update(UserContext.getUserId(), postId, request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "已保存");
        return ResponseEntity.ok(body);
    }

    /** 删除帖子（仅作者本人；软删） */
    @DeleteMapping("/{postId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long postId) {
        postService.delete(UserContext.getUserId(), postId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "已删除");
        return ResponseEntity.ok(body);
    }

    /** 提交审核：DRAFT/REJECTED → PENDING_REVIEW（规则拦截时返回 400 + 违规原因） */
    @PostMapping("/{postId}/submit")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable Long postId) {
        List<String> violations = postService.submit(UserContext.getUserId(), postId);
        Map<String, Object> body = new LinkedHashMap<>();
        if (!violations.isEmpty()) {
            body.put("success", false);
            body.put("error", "内容未通过自动检查");
            body.put("violations", violations);
            body.put("message", "提交被拦截：" + String.join("；", violations));
            return ResponseEntity.badRequest().body(body);
        }
        body.put("success", true);
        body.put("message", "已提交审核，审核通过后公开展示");
        return ResponseEntity.ok(body);
    }

    /**
     * 作者查看自己帖子的最新 AI 审核任务（细分状态）。
     * 仅作者本人可查；不存在则 204 让前端按"暂无任务"处理；越权则 403。
     */
    @GetMapping("/{postId}/moderation-status")
    public ResponseEntity<Map<String, Object>> moderationStatus(@PathVariable Long postId) {
        Map<String, Object> latest = postService.latestModerationStatus(UserContext.getUserId(), postId);
        if (latest == null) {
            return ResponseEntity.noContent().build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", latest);
        return ResponseEntity.ok(body);
    }
}
