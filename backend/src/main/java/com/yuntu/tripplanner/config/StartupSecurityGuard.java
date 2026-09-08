package com.yuntu.tripplanner.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 启动凭据安全门禁（审查报告 P0-2/P0-3）。
 *
 * <p>设计：开发默认 local profile 允许便捷默认值（admin/admin123、demo JWT secret）；
 * 一旦以<b>非 local</b> profile（如 prod/demo 部署）启动，若：
 * <ul>
 *   <li>community.admin-password 仍为默认 admin123（或未设置）；</li>
 *   <li>jwt.secret 仍为公开 demo 值（或长度 &lt; 32 字节）；</li>
 * </ul>
 * 直接抛 IllegalStateException 阻止启动并输出修复指引——默认凭据是公网部署最直接的攻击入口，
 * 不能只靠 README 注释提醒。local 环境放行，不影响日常开发/答辩演示。
 */
@Slf4j
@Order(0)
@Component
public class StartupSecurityGuard implements ApplicationRunner {

    /** application.yml 中的默认演示值（与 yml 常量一致，勿单边修改） */
    static final String DEMO_ADMIN_PASSWORD = "admin123";
    static final String DEMO_JWT_SECRET = "ai-travel-assistant-demo-jwt-secret-key-change-in-production";
    static final int MIN_JWT_SECRET_LENGTH = 32;

    private final Environment environment;

    @Value("${community.admin-password:admin123}")
    private String adminPassword;

    @Value("${jwt.secret:ai-travel-assistant-demo-jwt-secret-key-change-in-production}")
    private String jwtSecret;

    public StartupSecurityGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String[] profiles = environment.getActiveProfiles();
        String error = check(profiles, adminPassword, jwtSecret);
        if (error != null) {
            throw new IllegalStateException("启动安全门禁拦截：\n" + error
                    + "\n解决：用非 local profile 部署时，必须通过环境变量注入强凭据："
                    + "\n  1) COMMUNITY_ADMIN_PASSWORD=<至少 12 位的随机密码>"
                    + "\n  2) JWT_SECRET=<至少 32 字节的随机密钥>（如 `openssl rand -base64 48`）"
                    + "\n如需临时忽略（仅限可信内网联调）：--spring.profiles.active=local");
        }
    }

    /**
     * 纯函数校验，便于单测。activeProfiles 含 local → 返回 null（放行）。
     *
     * @return null=通过；非 null=阻断原因（多行文案）
     */
    public static String check(String[] activeProfiles, String adminPassword, String jwtSecret) {
        boolean local = activeProfiles != null && Arrays.stream(activeProfiles)
                .anyMatch(p -> p != null && "local".equalsIgnoreCase(p.trim()));
        if (local) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean badPassword = adminPassword == null || adminPassword.isBlank()
                || DEMO_ADMIN_PASSWORD.equals(adminPassword);
        if (badPassword) {
            sb.append("  - 管理员密码仍为默认/未设置（community.admin-password 命中演示值），"
                    + "生产部署必须注入 COMMUNITY_ADMIN_PASSWORD\n");
        }
        boolean badSecret = jwtSecret == null || jwtSecret.isBlank()
                || jwtSecret.length() < MIN_JWT_SECRET_LENGTH
                || DEMO_JWT_SECRET.equals(jwtSecret);
        if (badSecret) {
            sb.append("  - JWT 密钥仍为公开默认/过短（jwt.secret < 32 字节或命中演示值），"
                    + "生产部署必须注入 JWT_SECRET\n");
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
