package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.model.PostAuthor;
import com.yuntu.tripplanner.model.UserHome;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.FollowService;
import com.yuntu.tripplanner.service.UserHomeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户社交控制器（阶段四任务 2/3：关注 + 用户旅行主页）。
 *
 * <p>POST/DELETE /users/{userId}/follow 关注/取关（幂等，服务端校验不能关注自己、
 * 目标必须存在，403 由 GlobalExceptionHandler 统一映射）；GET /users/{userId}/home
 * 返回对外公开主页（只含已发布帖子 + 粉丝/关注统计，不含收藏/行程等私有数据）。
 * 所有端点都在 JWT 保护区（非 /auth 白名单），userId 取自登录态。
 */
@Slf4j
@RestController
@RequestMapping("/users")
public class UserController {

    private final FollowService followService;
    private final UserHomeService userHomeService;

    public UserController(FollowService followService, UserHomeService userHomeService) {
        this.followService = followService;
        this.userHomeService = userHomeService;
    }

    /** 关注用户（重复关注幂等，返回是否新关注） */
    @PostMapping("/{userId}/follow")
    public ResponseEntity<Map<String, Object>> follow(@PathVariable String userId) {
        boolean created = followService.follow(UserContext.getUserId(), userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("following", true);
        body.put("created", created);
        return ResponseEntity.ok(body);
    }

    /** 取关（幂等） */
    @DeleteMapping("/{userId}/follow")
    public ResponseEntity<Map<String, Object>> unfollow(@PathVariable String userId) {
        followService.unfollow(UserContext.getUserId(), userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("following", false);
        return ResponseEntity.ok(body);
    }

    /** 是否已关注（详情/卡片状态查询） */
    @GetMapping("/{userId}/follow/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String userId) {
        boolean following = followService.isFollowing(UserContext.getUserId(), userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("following", following);
        return ResponseEntity.ok(body);
    }

    /** 用户旅行主页（公开数据） */
    @GetMapping("/{userId}/home")
    public ResponseEntity<Map<String, Object>> home(@PathVariable String userId) {
        UserHome home = userHomeService.home(UserContext.getUserId(), userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", home);
        return ResponseEntity.ok(body);
    }

    /** 我关注的用户列表（卡片展示用） */
    @GetMapping("/following")
    public ResponseEntity<Map<String, Object>> myFollowing(
            @RequestParam(defaultValue = "50") int limit) {
        List<PostAuthor> list = followService.following(UserContext.getUserId(), limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("items", list);
        return ResponseEntity.ok(body);
    }
}
