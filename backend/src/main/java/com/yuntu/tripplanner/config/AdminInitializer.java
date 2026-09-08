package com.yuntu.tripplanner.config;

import com.yuntu.tripplanner.model.User;
import com.yuntu.tripplanner.service.CommunityUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首个管理员账号初始化（阶段二社区 §10.4 演示口径）。
 *
 * <p>账号来自配置 community.admin-username / community.admin-password（默认 admin/admin123，
 * 生产必须用环境变量 COMMUNITY_ADMIN_USERNAME / COMMUNITY_ADMIN_PASSWORD 覆盖）。
 * 幂等：已存在该用户则仅确保 role=ADMIN；不存在则创建。普通用户永远无法自行提权为管理员
 * （无任何"改角色"接口，前端也不可调用）。
 */
@Slf4j
@Order(2)
@Component
public class AdminInitializer implements ApplicationRunner {

    private final CommunityUserService communityUserService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Value("${community.admin-username:admin}")
    private String adminUsername;

    @Value("${community.admin-password:admin123}")
    private String adminPassword;

    public AdminInitializer(CommunityUserService communityUserService) {
        this.communityUserService = communityUserService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureAdmin();
        } catch (Exception e) {
            log.warn("管理员初始化失败（可稍后手工处理）: {}", e.getMessage());
        }
    }

    private void ensureAdmin() {
        String username = adminUsername == null || adminUsername.isBlank() ? "admin" : adminUsername.trim();
        User existing = communityUserService.findByUsername(username);
        if (existing != null) {
            if (!CommunityUserService.ROLE_ADMIN.equals(existing.getRole())) {
                existing.setRole(CommunityUserService.ROLE_ADMIN);
                communityUserService.updateUser(existing);
                log.info("管理员账号已提升: {} (id={})", username, existing.getId());
            }
            return;
        }
        User admin = new User();
        admin.setUsername(username);
        admin.setPasswordHash(encoder.encode(
                adminPassword == null || adminPassword.isBlank() ? "admin123" : adminPassword));
        admin.setNickname("管理员");
        admin.setRole(CommunityUserService.ROLE_ADMIN);
        communityUserService.insertUser(admin);
        log.info("已创建首个管理员账号: {} (id={})", username, admin.getId());
    }
}
