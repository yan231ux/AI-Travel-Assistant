package com.yuntu.tripplanner.security;

import com.yuntu.tripplanner.config.JwtProperties;
import com.yuntu.tripplanner.service.CommunityUserService;
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
 *
 * <p><b>账号状态校验（P0 修复）</b>：解析出 uid 后必须再校验账号状态，SUSPENDED 或账号已不存在
 * 一律 401。此前状态只在「登录」「发帖」「评论」处校验，被停用账号凭未过期的旧 token（有效期 7 天）
 * 仍可调用绝大多数写接口；把校验前移到拦截器后，任意受保护请求都会即时拒绝，做到「停用即失效」。
 */
@Slf4j
@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final JwtProperties properties;
    private final CommunityUserService communityUserService;

    public JwtAuthInterceptor(JwtUtil jwtUtil, JwtProperties properties,
                              CommunityUserService communityUserService) {
        this.jwtUtil = jwtUtil;
        this.properties = properties;
        this.communityUserService = communityUserService;
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
                    // 账号状态校验：停用/已不存在的账号，其旧 token 不再可用（P0）
                    String rejection = rejectionReasonFor(userId);
                    if (rejection != null) {
                        writeUnauthorized(response, rejection);
                        return false;
                    }
                    UserContext.setUserId(userId);
                    return true;
                }
            } catch (Exception e) {
                log.debug("JWT 校验失败: {}", e.getMessage());
            }
        }
        writeUnauthorized(response, "未登录或登录已过期");
        return false;
    }

    /**
     * 账号状态校验，异常时放行降级：DB 抖动不应让全站登录态集体失效（且此时写操作本身也会失败）。
     * 正常情况下该方法只多一次主键查询（/auth/**、/uploads/**、/system/health 走白名单，不查）。
     */
    private String rejectionReasonFor(String userId) {
        try {
            return communityUserService.rejectionReasonFor(userId);
        } catch (Exception e) {
            log.warn("账号状态校验失败，本次放行以避免误伤登录态: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\"}");
    }
}
