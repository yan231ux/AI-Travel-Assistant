package com.yuntu.tripplanner.security;

import com.yuntu.tripplanner.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JWT 工具单测：生成/解析往返、篡改拒绝、垃圾输入拒绝、错误密钥拒绝。
 */
class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret("test-secret-key-for-jwt-util-test-32-bytes!!");
        jwtUtil = new JwtUtil(props);
    }

    @Test
    void generateThenParseRoundTrip() {
        String token = jwtUtil.generateToken(42L, "alice");

        Claims claims = jwtUtil.parse(token);
        assertEquals("42", claims.get("uid", String.class));
        assertEquals("alice", claims.getSubject());
    }

    @Test
    void tamperedTokenRejected() {
        String token = jwtUtil.generateToken(42L, "alice");
        String tampered = token.substring(0, token.length() - 4) + "abcd";

        assertThrows(JwtException.class, () -> jwtUtil.parse(tampered));
    }

    @Test
    void garbageTokenRejected() {
        assertThrows(JwtException.class, () -> jwtUtil.parse("not.a.jwt"));
        // 空/null 在 jjwt 中直接抛 IllegalArgumentException（未进入签名校验）
        assertThrows(IllegalArgumentException.class, () -> jwtUtil.parse(""));
        assertThrows(IllegalArgumentException.class, () -> jwtUtil.parse(null));
    }

    @Test
    void differentSecretCannotParse() {
        String token = jwtUtil.generateToken(42L, "alice");

        JwtProperties other = new JwtProperties();
        other.setSecret("another-secret-key-0123456789-0123456789");
        JwtUtil otherUtil = new JwtUtil(other);

        assertThrows(JwtException.class, () -> otherUtil.parse(token));
    }
}
