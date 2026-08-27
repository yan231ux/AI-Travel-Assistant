package com.yuntu.tripplanner.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * JWT 鉴权配置
 *
 * <p>secret 为 HS256 签名密钥，生产必须更换（≥32 字节，可通过 JWT_SECRET 环境变量覆盖）；
 * expiration-minutes 默认 7 天（毕设演示级会话）。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** 签名密钥（HS256，≥32 字节；生产通过 JWT_SECRET 注入） */
    private String secret = "ai-travel-assistant-demo-jwt-secret-key-change-in-production";

    /** token 有效期（分钟），默认 7 天 */
    private long expirationMinutes = 10080;

    /** 请求头名称 */
    private String header = "Authorization";

    /** 请求头前缀（Bearer token） */
    private String prefix = "Bearer";
}
