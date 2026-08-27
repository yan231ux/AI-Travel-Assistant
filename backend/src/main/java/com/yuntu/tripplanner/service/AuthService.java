package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuntu.tripplanner.model.User;
import com.yuntu.tripplanner.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

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
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
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
        return user;
    }

    /**
     * 登录：校验通过返回用户，否则返回 null。
     */
    public User login(String username, String password) {
        User user = userRepository.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            return null;
        }
        if (!encoder.matches(password, user.getPasswordHash())) {
            log.warn("登录失败：密码错误 {}", username);
            return null;
        }
        return user;
    }
}
