package com.yuntu.tripplanner.security;

import com.yuntu.tripplanner.config.JwtProperties;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * JWT 鉴权拦截器：从 Authorization: Bearer &lt;token&gt; 解析当前用户，写入 {@link UserContext}。
 *
 * <p>非白名单路径（白名单 /auth/**；OPTIONS 预检在此直接放行，交由 CORS 处理器响应）都必须
 * 携带有效 token，否则返回 401 JSON。afterCompletion 必须清理 ThreadLocal 防泄漏。
 */
@Slf4j
@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final JwtProperties properties;

    public JwtAuthInterceptor(JwtUtil jwtUtil, JwtProperties properties) {
        this.jwtUtil = jwtUtil;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // CORS 预检（OPTIONS）不带 Authorization 头，必须放行交由 CorsFilter 处理，
        // 否则浏览器预检收到 401，主请求直接失败（fetch 报 "Failed to fetch"）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String authHeader = request.getHeader(properties.getHeader());
        String prefix = properties.getPrefix() + " ";
        if (authHeader != null && authHeader.startsWith(prefix)) {
            String token = authHeader.substring(prefix.length()).trim();
            try {
                Claims claims = jwtUtil.parse(token);
                String userId = claims.get("uid", String.class);
                if (userId != null && !userId.isBlank()) {
                    UserContext.setUserId(userId);
                    return true;
                }
            } catch (Exception e) {
                log.debug("JWT 校验失败: {}", e.getMessage());
            }
        }
        writeUnauthorized(response);
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"未登录或登录已过期\"}");
    }
}
