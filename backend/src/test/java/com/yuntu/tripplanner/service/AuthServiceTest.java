package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.model.User;
import com.yuntu.tripplanner.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注册/登录服务单测：密码 BCrypt 哈希、重名拒绝、登录成功/失败。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    private AuthService authService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository);
    }

    @Test
    void registerSuccessInsertsAndHashesPassword() {
        when(userRepository.selectCount(any())).thenReturn(0L);

        User user = authService.register("alice", "secret123", "爱丽丝");

        assertNotNull(user);
        assertEquals("alice", user.getUsername());
        assertEquals("爱丽丝", user.getNickname());
        assertNotEquals("secret123", user.getPasswordHash(), "密码绝不能落明文");
        assertTrue(encoder.matches("secret123", user.getPasswordHash()));
        verify(userRepository).insert(user);
    }

    @Test
    void registerDuplicateUsernameReturnsNull() {
        when(userRepository.selectCount(any())).thenReturn(1L);

        assertNull(authService.register("alice", "secret123", null));
        verify(userRepository, never()).insert(any());
    }

    @Test
    void loginSuccessReturnsUser() {
        User stored = new User();
        stored.setId(7L);
        stored.setUsername("bob");
        stored.setPasswordHash(encoder.encode("pw123456"));
        when(userRepository.selectOne(any())).thenReturn(stored);

        assertEquals(stored, authService.login("bob", "pw123456"));
    }

    @Test
    void loginWrongPasswordReturnsNull() {
        User stored = new User();
        stored.setUsername("bob");
        stored.setPasswordHash(encoder.encode("pw123456"));
        when(userRepository.selectOne(any())).thenReturn(stored);

        assertNull(authService.login("bob", "wrong-password"));
    }

    @Test
    void loginUnknownUserReturnsNull() {
        when(userRepository.selectOne(any())).thenReturn(null);

        assertNull(authService.login("ghost", "whatever"));
    }
}
