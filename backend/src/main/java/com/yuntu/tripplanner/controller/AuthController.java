package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.model.LoginRequest;
import com.yuntu.tripplanner.model.RegisterRequest;
import com.yuntu.tripplanner.model.User;
import com.yuntu.tripplanner.security.JwtUtil;
import com.yuntu.tripplanner.service.AuthService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证控制器：注册 + 登录（无需 token，白名单路径）
 *
 * <p>成功返回 {success, message, token, user:{id, username, nickname}}；
 * 注册重名 409、登录失败 401、参数校验 400（走 GlobalExceptionHandler）。
 * user.id 为字符串（与 trip_record.user_id 同型，前端直接用于保存行程）。
 */
@Slf4j
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtUtil jwtUtil;

    public AuthController(AuthService authService, JwtUtil jwtUtil) {
        this.authService = authService;
        this.jwtUtil = jwtUtil;
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

    private static Map<String, Object> authBody(boolean success, String message, String token, User user) {
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("id", String.valueOf(user.getId()));
        userMap.put("username", user.getUsername());
        userMap.put("nickname", user.getNickname());

        Map<String, Object> body = new HashMap<>();
        body.put("success", success);
        body.put("message", message);
        body.put("token", token);
        body.put("user", userMap);
        return body;
    }
}
