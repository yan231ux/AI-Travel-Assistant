package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.PostInteractionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 帖子互动控制器（阶段二 §9.5）：点赞/收藏/不喜欢，幂等。
 * POST = 生效，DELETE = 取消；重复操作不重复计数（唯一键 + 计数器守卫）。
 */
@Slf4j
@RestController
@RequestMapping("/community/posts/{postId}")
public class PostInteractionController {

    private final PostInteractionService interactionService;

    public PostInteractionController(PostInteractionService interactionService) {
        this.interactionService = interactionService;
    }

    @PostMapping("/like")
    public ResponseEntity<Map<String, Object>> like(@PathVariable Long postId) {
        return respond(postId, "LIKE", true);
    }

    @DeleteMapping("/like")
    public ResponseEntity<Map<String, Object>> unlike(@PathVariable Long postId) {
        return respond(postId, "LIKE", false);
    }

    @PostMapping("/favorite")
    public ResponseEntity<Map<String, Object>> favorite(@PathVariable Long postId) {
        return respond(postId, "FAVORITE", true);
    }

    @DeleteMapping("/favorite")
    public ResponseEntity<Map<String, Object>> unfavorite(@PathVariable Long postId) {
        return respond(postId, "FAVORITE", false);
    }

    @PostMapping("/dislike")
    public ResponseEntity<Map<String, Object>> dislike(@PathVariable Long postId) {
        return respond(postId, "DISLIKE", true);
    }

    @DeleteMapping("/dislike")
    public ResponseEntity<Map<String, Object>> undislike(@PathVariable Long postId) {
        return respond(postId, "DISLIKE", false);
    }

    private ResponseEntity<Map<String, Object>> respond(Long postId, String action, boolean active) {
        PostInteractionService.InteractionState state =
                interactionService.interact(UserContext.getUserId(), postId, action, active);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("liked", state.liked());
        body.put("favorited", state.favorited());
        body.put("disliked", state.disliked());
        body.put("likeCount", state.likeCount());
        body.put("favoriteCount", state.favoriteCount());
        return ResponseEntity.ok(body);
    }
}
