package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.model.User;
import com.yuntu.tripplanner.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 社区用户侧服务（阶段二）：角色判定 + 作者昵称解析。
 *
 * <p>角色只存在 users.role（USER/ADMIN），全部由服务端校验 —— 前端隐藏按钮仅体验优化，
 * 不能作为权限依据（PRODUCT_EVOLUTION_PLAN §10.4：服务端必须校验角色）。
 */
@Slf4j
@Service
public class CommunityUserService {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";

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

    public boolean isAdmin(String userId) {
        return ROLE_ADMIN.equals(roleOf(userId));
    }

    /** 非 ADMIN 直接抛 403（审核/举报处理接口统一入口） */
    public void requireAdmin(String userId) {
        if (!isAdmin(userId)) {
            log.warn("越权访问管理接口被拒: userId={}", userId);
            throw new ForbiddenException("该操作需要管理员权限");
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
}
