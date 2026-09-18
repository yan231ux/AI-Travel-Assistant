package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuntu.tripplanner.model.User;
import com.yuntu.tripplanner.repository.UserRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 用户治理（设计方案 §7）状态语义单测：
 * accountStatusOf 兜底 / 发帖与评论前置校验 / 违规原子自增 / 治理动作落库（wrapper 校验）。
 */
@ExtendWith(MockitoExtension.class)
class CommunityUserServiceGovernanceTest {

    @Mock
    private UserRepository userRepository;

    private CommunityUserService svc;

    @BeforeEach
    void setUp() {
        // LambdaUpdateWrapper.set(方法引用) 需要 TableInfo 缓存（生产由 Spring 初始化，单测手动注册）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
        svc = new CommunityUserService(userRepository);
    }

    private User user(String username, String status, Boolean postLimited, Boolean commentBanned) {
        User u = new User();
        u.setId(7L);
        u.setUsername(username);
        u.setAccountStatus(status);
        u.setPostLimited(postLimited);
        u.setCommentBanned(commentBanned);
        return u;
    }

    /* ---------- accountStatusOf 兜底 ---------- */

    @Test
    void accountStatusOf_nullUser_isActive() {
        when(userRepository.selectById(7L)).thenReturn(null);
        assertEquals(User.STATUS_ACTIVE, svc.accountStatusOf("7"));
    }

    @Test
    void accountStatusOf_nullColumn_isActive() {
        when(userRepository.selectById(7L)).thenReturn(user("a", null, false, false));
        assertEquals(User.STATUS_ACTIVE, svc.accountStatusOf("7"));
    }

    @Test
    void accountStatusOf_suspended_returnsSuspended() {
        when(userRepository.selectById(7L)).thenReturn(user("a", User.STATUS_SUSPENDED, false, false));
        assertEquals(User.STATUS_SUSPENDED, svc.accountStatusOf("7"));
    }

    /* ---------- 发帖/评论前置校验（真实链路强制点） ---------- */

    @Test
    void requireCanPublish_suspended_rejects() {
        when(userRepository.selectById(7L)).thenReturn(user("a", User.STATUS_SUSPENDED, false, false));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.requireCanPublish("7"));
        assertTrue(ex.getMessage().contains("暂停"));
    }

    @Test
    void requireCanPublish_postLimited_rejects() {
        when(userRepository.selectById(7L)).thenReturn(user("a", User.STATUS_ACTIVE, true, false));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.requireCanPublish("7"));
        assertTrue(ex.getMessage().contains("限制发帖"));
    }

    @Test
    void requireCanPublish_active_allows() {
        when(userRepository.selectById(7L)).thenReturn(user("a", User.STATUS_ACTIVE, false, false));
        assertDoesNotThrow(() -> svc.requireCanPublish("7"));
    }

    @Test
    void requireCanComment_commentBanned_rejects() {
        when(userRepository.selectById(7L)).thenReturn(user("a", User.STATUS_ACTIVE, false, true));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.requireCanComment("7"));
        assertTrue(ex.getMessage().contains("暂停评论"));
    }

    @Test
    void requireCanComment_suspended_rejects() {
        when(userRepository.selectById(7L)).thenReturn(user("a", User.STATUS_SUSPENDED, false, false));
        assertThrows(IllegalArgumentException.class, () -> svc.requireCanComment("7"));
    }

    /* ---------- 违规计数（举报成立自动 +1） ---------- */

    @Test
    void bumpViolation_missingUser_noop() {
        when(userRepository.selectById(7L)).thenReturn(null);
        svc.bumpViolation("7");
        verify(userRepository, never()).update(any(), any());
    }

    @Test
    void bumpViolation_increments_atomicallyViaSql() {
        when(userRepository.selectById(7L)).thenReturn(user("a", User.STATUS_ACTIVE, false, false));
        svc.bumpViolation("7");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<User>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(userRepository).update(isNull(), captor.capture());
        assertNotNull(captor.getValue().getSqlSet());
        assertTrue(captor.getValue().getSqlSet().contains("violation_count = violation_count + 1"),
                "违规次数必须用 SQL 原子自增，不能用读改写（并发会丢计数）");
    }

    /* ---------- 治理动作落库 ---------- */

    @Test
    void applyGovernance_setsSuspensionAndFlags() {
        svc.applyGovernance(7L, java.util.Map.of(
                "account_status", User.STATUS_SUSPENDED,
                "post_limited", true,
                "comment_banned", false));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<User>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(userRepository).update(isNull(), captor.capture());
        java.util.Map<String, Object> params = captor.getValue().getParamNameValuePairs();
        assertTrue(params.containsValue(User.STATUS_SUSPENDED));
        assertTrue(params.containsValue(Boolean.TRUE));
        assertTrue(params.containsValue(Boolean.FALSE));
    }
}
