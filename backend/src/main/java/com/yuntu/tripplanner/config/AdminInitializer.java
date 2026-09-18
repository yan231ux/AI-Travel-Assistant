package com.yuntu.tripplanner.config;

import com.yuntu.tripplanner.common.AdminRole;
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
 *
 * <p>幂等语义（角色细化后）：<b>只在账号"不是任何管理端角色"时才建/提为 SUPER_ADMIN</b>。
 * 这样既能在账号被误降为普通用户时兜底恢复（防锁死），又<b>不会覆盖</b>超管把默认 admin
 * 改配成审核员/运营等更小权限角色的运营决策。
 * 普通用户永远无法自行提权（无自助改角色入口，改角色需 SUPER_ADMIN 且二次确认）。
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
            // 已在管理端（超管/审核员/编辑/运营）→ 不动，尊重运营对角色的调整
            if (AdminRole.fromCode(existing.getRole()).isAdminSide()) {
                return;
            }
            // 停在普通用户（含被误降级）→ 兜底提为超管，避免把自己锁在门外
            existing.setRole(AdminRole.SUPER_ADMIN.name());
            communityUserService.updateUser(existing);
            log.info("管理员账号已提升为 {}: {} (id={})",
                    AdminRole.SUPER_ADMIN.name(), username, existing.getId());
            return;
        }
        User admin = new User();
        admin.setUsername(username);
        admin.setPasswordHash(encoder.encode(
                adminPassword == null || adminPassword.isBlank() ? "admin123" : adminPassword));
        admin.setNickname("管理员");
        admin.setRole(AdminRole.SUPER_ADMIN.name());
        communityUserService.insertUser(admin);
        log.info("已创建首个{}账号: {} (id={})",
                AdminRole.SUPER_ADMIN.label(), username, admin.getId());
    }
}
