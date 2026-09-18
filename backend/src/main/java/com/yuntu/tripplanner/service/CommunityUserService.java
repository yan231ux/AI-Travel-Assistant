package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.common.AdminRole;
import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.model.User;
import com.yuntu.tripplanner.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 社区用户侧服务（阶段二）：角色判定 + 权限点校验 + 作者昵称解析。
 *
 * <p>角色只存在 users.role，取值域见 {@link AdminRole}（USER / CONTENT_REVIEWER /
 * CITY_EDITOR / RECOMMENDATION_OPERATOR / SUPER_ADMIN，另保留历史值 ADMIN）。
 * 全部由服务端校验 —— 前端隐藏按钮仅体验优化，不能作为权限依据。
 *
 * <p><b>两层鉴权</b>：service 层 {@link #requireAdmin(String)} 保证"能进后台"，
 * controller 层 {@link #requirePermission(String, AdminPermission)} 决定"能操作哪一块"。
 */
@Slf4j
@Service
public class CommunityUserService {

    public static final String ROLE_USER = "USER";
    /** 历史管理员角色码：启动期会迁成 SUPER_ADMIN，代码保留兼容防迁移差异导致失权 */
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    private final UserRepository userRepository;

    public CommunityUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 当前用户角色；用户不存在/角色为空一律按 USER 处理（存量老用户平滑） */
    public String roleOf(String userId) {
        User user = findById(userId);
        if (user == null || user.getRole() == null || user.getRole().isBlank()) {
            return ROLE_USER;
        }
        return user.getRole();
    }

    /** 当前用户角色枚举（未知角色码 → USER，见 {@link AdminRole#fromCode}） */
    public AdminRole adminRoleOf(String userId) {
        return AdminRole.fromCode(roleOf(userId));
    }

    /** 是否属于管理端（拥有任一管理端角色即可进 /admin） */
    public boolean isAdmin(String userId) {
        return adminRoleOf(userId).isAdminSide();
    }

    /** 当前用户的权限点集合（登录响应下发，供前端按权限显示菜单） */
    public Set<AdminPermission> permissionsOf(String userId) {
        return adminRoleOf(userId).permissions();
    }

    public boolean hasPermission(String userId, AdminPermission permission) {
        return adminRoleOf(userId).has(permission);
    }

    /**
     * 粗粒度兜底：非管理端用户直接 403。
     *
     * <p>语义是"<b>能进后台</b>"，不是"是超管" —— service 层用它可以保证任何管理接口
     * 都不会被普通用户调用；具体"能操作哪一块"由 controller 层的
     * {@link #requirePermission(String, AdminPermission)} 决定。两层配合：
     * 权限点漏配时最坏也只是同域越权，不会门户大开。
     */
    public void requireAdmin(String userId) {
        if (!isAdmin(userId)) {
            log.warn("越权访问管理接口被拒: userId={}", userId);
            throw new ForbiddenException("该操作需要管理员权限");
        }
    }

    /** 细粒度校验：缺少指定权限点即 403（403 文案带权限名，便于运营排查"我为什么进不去"） */
    public void requirePermission(String userId, AdminPermission permission) {
        AdminRole role = adminRoleOf(userId);
        if (!role.isAdminSide()) {
            log.warn("越权访问管理接口被拒: userId={} role={} 需要权限={}",
                    userId, role.name(), permission.name());
            throw new ForbiddenException("该操作需要管理员权限");
        }
        if (!role.has(permission)) {
            log.warn("管理端越权被拒: userId={} role={} 缺少权限={}",
                    userId, role.name(), permission.name());
            throw new ForbiddenException("当前角色（" + role.label() + "）没有「"
                    + permission.label() + "」权限");
        }
    }

    /**
     * 超管专属校验：仅 SUPER_ADMIN（及历史值 ADMIN）放行。
     *
     * <p><b>什么时候用它，什么时候用 requirePermission</b>：动作属于某个可下放的业务域
     * （内容审核 / 攻略运营 / 推荐运营）就用 {@link #requirePermission}；动作<b>不属于任何
     * 单一业务域、且是写操作或影响面大</b>（如统计数据重算 —— 它会改预聚合表，且没有对应的
     * 左侧菜单可以授权）就直接收紧到超管，避免"只读权限顺带把写操作也放出去"。
     *
     * <p>兼容历史值 ADMIN：它的权限等同超管，迁移未完成时同样放行。
     */
    public void requireSuperAdmin(String userId) {
        AdminRole role = adminRoleOf(userId);
        if (role != AdminRole.SUPER_ADMIN && role != AdminRole.ADMIN) {
            log.warn("超管专属操作被拒: userId={} role={}", userId, role.name());
            throw new ForbiddenException("该操作仅超级管理员可执行");
        }
    }

    /** 作者昵称批量解析（帖子列表避免 N+1：一次 IN 查询） */
    public Map<String, String> nicknamesOf(Collection<String> userIds) {
        Map<String, String> result = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return result;
        }
        List<Long> ids = userIds.stream()
                .filter(this::isNumericId)
                .map(Long::valueOf)
                .distinct()
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return result;
        }
        List<User> users = userRepository.selectBatchIds(ids);
        for (User u : users) {
            if (u == null) {
                continue;
            }
            String key = String.valueOf(u.getId());
            result.put(key, u.getNickname() != null && !u.getNickname().isBlank()
                    ? u.getNickname()
                    : (u.getUsername() == null ? key : u.getUsername()));
        }
        return result;
    }

    /** 单个用户昵称解析（评论列表用；不存在时兜底显示用户 id） */
    public String nicknameOf(String userId) {
        Map<String, String> map = nicknamesOf(List.of(userId));
        return map.getOrDefault(userId, userId);
    }

    /** 用户是否存在（关注/用户主页等需要校验目标用户的场景；非数字 id 视为不存在） */
    public boolean exists(String userId) {
        return findById(userId) != null;
    }

    /** 注册时间（用户主页展示；仅暴露时间串，不返回完整用户实体） */
    public String createdAtOf(String userId) {
        User user = findById(userId);
        return user == null || user.getCreatedAt() == null ? null
                : user.getCreatedAt().toString().replace("T", " ");
    }

    /** 用户分页列表（管理后台任务 4：用户治理，新注册在前；调用方需先 requireAdmin） */
    public List<User> listUsers(int page, int pageSize) {
        int size = Math.max(1, Math.min(pageSize <= 0 ? 20 : pageSize, 100));
        int pageNo = Math.max(1, page);
        return userRepository.selectList(new LambdaQueryWrapper<User>()
                .orderByDesc(User::getCreatedAt)
                .last("LIMIT " + size + " OFFSET " + (pageNo - 1) * size));
    }

    /** 用户总数（管理后台用户列表分页用） */
    public long countUsers() {
        return userRepository.selectCount(new LambdaQueryWrapper<User>());
    }

    private User findById(String userId) {
        if (userId == null || userId.isBlank() || !isNumericId(userId)) {
            return null;
        }
        return userRepository.selectById(Long.valueOf(userId));
    }

    private boolean isNumericId(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 供管理员初始化等场景按用户名查用户 */
    public User findByUsername(String username) {
        return userRepository.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username).last("LIMIT 1"));
    }

    public void updateUser(User user) {
        userRepository.updateById(user);
    }

    public void insertUser(User user) {
        userRepository.insert(user);
    }

    /* ================= 用户治理（设计方案 §7：状态校验与违规计数） ================= */

    /** 当前账号状态（null/不存在 → ACTIVE 语义；SUSPENDED 才算暂停） */
    public String accountStatusOf(String userId) {
        User u = findById(userId);
        if (u == null || u.getAccountStatus() == null || u.getAccountStatus().isBlank()) {
            return User.STATUS_ACTIVE;
        }
        return u.getAccountStatus();
    }

    /**
     * 发帖前校验（PostService 创建/提交共用）：暂停账号一律拒绝；限制发帖只拦「发布新内容」。
     * 抛 IllegalArgumentException → 全局 400（message 直达前端）。
     */
    public void requireCanPublish(String userId) {
        if (!User.STATUS_ACTIVE.equals(accountStatusOf(userId))) {
            throw new IllegalArgumentException("账号已被暂停，无法发布内容");
        }
        User u = findById(userId);
        if (u != null && Boolean.TRUE.equals(u.getPostLimited())) {
            throw new IllegalArgumentException("你的账号已被限制发帖（可正常浏览与互动），如需申诉请联系管理员");
        }
    }

    /** 评论前校验（PostCommentService 发布共用） */
    public void requireCanComment(String userId) {
        if (!User.STATUS_ACTIVE.equals(accountStatusOf(userId))) {
            throw new IllegalArgumentException("账号已被暂停，无法发表评论");
        }
        User u = findById(userId);
        if (u != null && Boolean.TRUE.equals(u.getCommentBanned())) {
            throw new IllegalArgumentException("你的账号已被暂停评论（可正常浏览与发帖），如需申诉请联系管理员");
        }
    }

    /** 举报成立时违规次数 +1（原子自增，不覆盖历史审计） */
    public void bumpViolation(String userId) {
        User u = findById(userId);
        if (u == null) {
            return;
        }
        userRepository.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, u.getId())
                .setSql("violation_count = violation_count + 1"));
    }

    /** 治理动作落库（POST_LIMIT/COMMENT_BAN/SUSPEND 等，由 AdminUserService 统一调用） */
    public void applyGovernance(long id, java.util.Map<String, Object> fields) {
        userRepository.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, id)
                .set(fields.get("account_status") != null, User::getAccountStatus, fields.get("account_status"))
                .set(fields.get("post_limited") != null, User::getPostLimited, fields.get("post_limited"))
                .set(fields.get("comment_banned") != null, User::getCommentBanned, fields.get("comment_banned")));
    }
}
