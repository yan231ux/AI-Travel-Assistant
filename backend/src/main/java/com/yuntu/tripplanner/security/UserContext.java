package com.yuntu.tripplanner.security;

/**
 * 当前登录用户上下文（ThreadLocal，请求级）
 *
 * <p>由 JwtAuthInterceptor 在 preHandle 解析 token 后写入、afterCompletion 清除，
 * Controller 侧通过 {@link #getUserId()} 取当前用户 id（String，与 trip_record.user_id 同型）。
 * 未登录请求不会进入非白名单路径，因此 getUserId() 在受保护端点恒有值。
 */
public final class UserContext {

    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void setUserId(String userId) {
        CURRENT_USER.set(userId);
    }

    /** 当前登录用户 id；请求不经过鉴权时可能为 null */
    public static String getUserId() {
        return CURRENT_USER.get();
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
