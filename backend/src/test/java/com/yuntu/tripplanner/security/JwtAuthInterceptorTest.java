package com.yuntu.tripplanner.security;

import com.yuntu.tripplanner.config.JwtProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JWT 拦截器单测：OPTIONS 预检必须放行（否则 CORS 预检 401，浏览器 fetch 报 Failed to fetch）、
 * 有效 token 放行、无/坏 token 返回 401。
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthInterceptorTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private JwtAuthInterceptor interceptor;
    private String validToken;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret("test-secret-key-for-jwt-util-test-32-bytes!!");
        JwtUtil jwtUtil = new JwtUtil(props);
        interceptor = new JwtAuthInterceptor(jwtUtil, props);
        validToken = jwtUtil.generateToken(42L, "alice");
    }

    /** 仅在需要写 401 JSON 的测试里启用 getWriter 桩（避免严格检测报 UnnecessaryStubbing） */
    private void stubWriter() throws Exception {
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void optionsPreflightPassesThroughEvenWithoutToken() throws Exception {
        // 预检不带 Authorization 头，且拦截器应在读头之前就放行——因此这里不桩 getHeader
        when(request.getMethod()).thenReturn("OPTIONS");

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed, "OPTIONS 预检必须放行，交 CORS 处理");
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void validBearerTokenSetsUserContext() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + validToken);

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        assertEquals("42", UserContext.getUserId(), "拦截器应把 token 里的 uid 写入 UserContext");
    }

    @Test
    void missingTokenReturns401() throws Exception {
        stubWriter();
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("Authorization")).thenReturn(null);

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(!allowed, "无 token 应拒绝");
        verify(response).setStatus(401);
        assertNull(UserContext.getUserId());
    }

    @Test
    void garbageTokenReturns401() throws Exception {
        stubWriter();
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("Authorization")).thenReturn("Bearer not.a.jwt");

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(!allowed, "坏 token 应拒绝");
        verify(response).setStatus(401);
    }
}
