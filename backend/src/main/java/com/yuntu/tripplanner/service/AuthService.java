package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yuntu.tripplanner.common.AdminRole;
import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.model.AuditLog;
import com.yuntu.tripplanner.model.User;
import com.yuntu.tripplanner.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 用户注册/登录服务
 *
 * <p>密码用 BCrypt 哈希存储（永不落明文）；注册重名返回 null（Controller 转 409），
 * 登录失败返回 null（Controller 转 401）。
 */
@Slf4j
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final AuditService auditService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository userRepository, AuditService auditService) {
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    /**
     * 注册：用户名已存在返回 null，否则落库返回完整用户（含哈希，仅内部使用）。
     */
    public User register(String username, String password, String nickname) {
        Long count = userRepository.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (count != null && count > 0) {
            log.info("注册失败：用户名已存在 {}", username);
            return null;
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode(password));
        user.setNickname(nickname == null || nickname.isBlank() ? null : nickname.trim());
        userRepository.insert(user);
        log.info("新用户注册: {}", username);
        auditService.record(String.valueOf(user.getId()), AuditLog.CAT_USER, "user_registered",
                "user", String.valueOf(user.getId()),
                AuditService.detailOf("username", username));
        return user;
    }

    /**
     * 登录：校验通过返回用户，否则返回 null。
     */
    public User login(String username, String password) {
        User user = userRepository.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            auditService.record(null, AuditLog.CAT_USER, "login_failed",
                    "user", null, AuditService.detailOf("username", username, "reason", "not_found"));
            return null;
        }
        if (!encoder.matches(password, user.getPasswordHash())) {
            log.warn("登录失败：密码错误 {}", username);
            auditService.record(null, AuditLog.CAT_USER, "login_failed",
                    "user", String.valueOf(user.getId()),
                    AuditService.detailOf("username", username, "reason", "bad_password"));
            return null;
        }
        // 用户治理（§7）：暂停账号拒绝登录，message 直达前端（区别于密码错误）。
        // 仅 SUSPENDED 才算暂停：null/ACTIVE 均放行（存量老账号 account_status 可能为 NULL，语义与 CommunityUserService.accountStatusOf 一致）。
        if (User.STATUS_SUSPENDED.equalsIgnoreCase(user.getAccountStatus())) {
            log.warn("登录被拒：账号已暂停 {}", username);
            auditService.record(null, AuditLog.CAT_USER, "login_failed",
                    "user", String.valueOf(user.getId()),
                    AuditService.detailOf("username", username, "reason", "suspended"));
            throw new IllegalArgumentException("该账号已被暂停，请联系管理员处理");
        }
        // 记录最近登录（不阻断主流程）
        try {
            userRepository.update(null, new LambdaUpdateWrapper<User>()
                    .eq(User::getId, user.getId())
                    .set(User::getLastLoginAt, LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("记录最近登录失败（不影响登录）: {}", e.getMessage());
        }
        auditService.record(String.valueOf(user.getId()), AuditLog.CAT_USER, "login_success",
                "user", String.valueOf(user.getId()), null);
        return user;
    }

    /**
     * 管理员登录（独立入口，与用户登录分开）：先走通用账号校验，再强制校验角色属于管理端。
     *
     * <p>与 {@link #login} 的差异只有最后一步：普通登录任何角色都能进（随后前端按角色分流），
     * 管理员入口则要求角色必须是管理端，否则抛 {@link ForbiddenException}（"该账号不是管理员"）。
     * 账号/密码错误仍返回 null（Controller 转 401）——两种失败状态分明：401 是凭据错，
     * 403 是"凭据对但不是管理员"，不给攻击者泄露账号是否存在。
     */
    public User loginAsAdmin(String username, String password) {
        User user = login(username, password);
        if (user == null) {
            return null;
        }
        if (!AdminRole.fromCode(user.getRole()).isAdminSide()) {
            log.warn("管理员登录被拒：账号非管理端 {}", username);
            auditService.record(String.valueOf(user.getId()), AuditLog.CAT_USER, "login_failed",
                    "user", String.valueOf(user.getId()),
                    AuditService.detailOf("username", username, "reason", "not_admin"));
            throw new ForbiddenException("该账号不是管理员，请使用普通登录");
        }
        return user;
    }
}
