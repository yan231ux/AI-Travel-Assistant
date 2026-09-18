package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.model.CommentCreateRequest;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.PostCommentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 帖子评论控制器（阶段二 §9.6）：列表 / 发布 / 删除。
 *
 * <p>删除权限为三方：评论作者本人 / 帖子作者（楼主治理自己帖子下的评论）/ 管理员，
 * 判定与审计留痕都在 Service 内完成，Controller 只做 HTTP 映射。
 */
@Slf4j
@RestController
@RequestMapping("/community")
public class PostCommentController {

    private final PostCommentService commentService;

    public PostCommentController(PostCommentService commentService) {
        this.commentService = commentService;
    }

    /** 评论列表（时间正序，软删评论占位显示） */
    @GetMapping("/posts/{postId}/comments")
    public ResponseEntity<Map<String, Object>> list(@PathVariable Long postId,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int pageSize) {
        PostCommentService.CommentPage result =
                commentService.listComments(UserContext.getUserId(), postId, page, pageSize);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("items", result.getItems());
        body.put("total", result.getTotal());
        body.put("page", result.getPage());
        return ResponseEntity.ok(body);
    }

    /** 发表评论（PUBLISHED 帖子） */
    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<Map<String, Object>> add(@PathVariable Long postId,
                                                   @RequestBody(required = false) CommentCreateRequest request) {
        String content = request == null ? null : request.getContent();
        Long parentId = request == null ? null : request.getParentId();
        PostCommentService.CommentItem item =
                commentService.add(UserContext.getUserId(), postId, content, parentId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", item);
        return ResponseEntity.ok(body);
    }

    /** 删除评论（评论作者本人 / 帖子作者 / 管理员） */
    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long commentId) {
        commentService.delete(UserContext.getUserId(), commentId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "评论已删除");
        return ResponseEntity.ok(body);
    }
}
