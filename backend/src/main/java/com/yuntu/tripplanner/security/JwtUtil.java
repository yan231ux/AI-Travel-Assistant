package com.yuntu.tripplanner.security;

import com.yuntu.tripplanner.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 生成/解析工具（jjwt 0.12，HS256）
 *
 * <p>token 载荷：subject=username、claim uid=userId（String，与 trip_record.user_id 同型）。
 * 解析失败（篡改/过期/非法签名）抛异常，由拦截器统一转 401。
 */
@Component
public class JwtUtil {

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtUtil(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /** 生成 token：subject=username，uid=userId */
    public String generateToken(Long userId, String username) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + properties.getExpirationMinutes() * 60_000);
        return Jwts.builder()
                .subject(username)
                .claim("uid", String.valueOf(userId))
                .issuedAt(now)
                .expiration(expiration)
                .signWith(key)
                .compact();
    }

    /** 解析并返回载荷；无效 token 抛异常（调用方处理） */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
