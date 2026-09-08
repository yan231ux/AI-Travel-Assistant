package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.model.LoginRequest;
import com.yuntu.tripplanner.model.RegisterRequest;
import com.yuntu.tripplanner.model.User;
import com.yuntu.tripplanner.security.JwtUtil;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.AuthService;
import com.yuntu.tripplanner.service.CommunityUserService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证控制器：注册 + 登录 + 当前用户信息（无需 token 的路径只有注册/登录）。
 *
 * <p>成功返回 {success, message, token, user:{id, username, nickname, role}}；
 * 注册重名 409、登录失败 401、参数校验 400（走 GlobalExceptionHandler）。
 * role 为阶段二社区新增（USER/ADMIN，存量老用户按 USER 处理）；
 * GET /auth/me 供前端启动/刷新时同步最新用户态（旧 localStorage 缺 role 时可补齐）。
 */
@Slf4j
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtUtil jwtUtil;
    private final CommunityUserService communityUserService;

    public AuthController(AuthService authService, JwtUtil jwtUtil,
                          CommunityUserService communityUserService) {
        this.authService = authService;
        this.jwtUtil = jwtUtil;
        this.communityUserService = communityUserService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        User user = authService.register(request.getUsername(), request.getPassword(), request.getNickname());
        if (user == null) {
            Map<String, Object> body = new HashMap<>();
            body.put("success", false);
            body.put("message", "用户名已存在");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        log.info("注册成功: {}", user.getUsername());
        return ResponseEntity.ok(authBody(true, "注册成功", token, user));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        User user = authService.login(request.getUsername(), request.getPassword());
        if (user == null) {
            Map<String, Object> body = new HashMap<>();
            body.put("success", false);
            body.put("message", "用户名或密码错误");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        return ResponseEntity.ok(authBody(true, "登录成功", token, user));
    }

    /** 当前登录用户信息（阶段二：同步最新 role；旧 token/localStorage 缺 role 时前端调用补齐） */
    @GetMapping("/me")
    public ResponseEntity<?> me() {
        String userId = UserContext.getUserId();
        String role = communityUserService.roleOf(userId);
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("id", userId);
        userMap.put("role", role);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("user", userMap);
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> authBody(boolean success, String message, String token, User user) {
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("id", String.valueOf(user.getId()));
        userMap.put("username", user.getUsername());
        userMap.put("nickname", user.getNickname());
        userMap.put("role", user.getRole() == null || user.getRole().isBlank()
                ? CommunityUserService.ROLE_USER : user.getRole());

        Map<String, Object> body = new HashMap<>();
        body.put("success", success);
        body.put("message", message);
        body.put("token", token);
        body.put("user", userMap);
        return body;
    }
}
