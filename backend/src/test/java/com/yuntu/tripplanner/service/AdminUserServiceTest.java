package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.exception.PostNotFoundException;
import com.yuntu.tripplanner.model.AuditLog;
import com.yuntu.tripplanner.model.User;
import com.yuntu.tripplanner.repository.UserRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 用户治理服务（设计方案 §7 /admin/users）单测：
 * requireAdmin 强制、ADMIN 保护、动作语义、审计留痕、列表视图字段。
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private CommunityUserService communityUserService;
    @Mock
    private PostService postService;
    @Mock
    private AuditService auditService;

    private AdminUserService svc;

    @BeforeEach
    void setUp() {
        // 注册实体 TableInfo，否则 LambdaUpdateWrapper/LambdaQueryWrapper 的方法引用在无 Spring 环境解析失败
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
        svc = new AdminUserService(userRepository, communityUserService, postService, auditService);
    }

    private User user(long id, String username, String role, String status) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setNickname("昵称-" + username);
        u.setRole(role);
        u.setAccountStatus(status);
        u.setPostLimited(false);
        u.setCommentBanned(false);
        u.setViolationCount(0);
        return u;
    }

    /* ---------- 权限 ---------- */

    @Test
    void page_nonAdmin_forbidden() {
        doThrow(new ForbiddenException("该操作需要管理员权限"))
                .when(communityUserService).requirePermission(any(), eq(AdminPermission.USER_GOVERN));
        assertThrows(ForbiddenException.class, () -> svc.page("u1", null, null, null, null, 1, 20));
    }

    @Test
    void govern_nonAdmin_forbidden() {
        doThrow(new ForbiddenException("该操作需要管理员权限"))
                .when(communityUserService).requirePermission(any(), eq(AdminPermission.USER_GOVERN));
        assertThrows(ForbiddenException.class,
                () -> svc.govern("u1", 7L, AdminUserService.ACT_SUSPEND, "test"));
    }

    /* ---------- 列表 ---------- */

    @Test
    void page_returnsViewWithPostCountAndStatusDefaults() {
        when(userRepository.selectCount(any())).thenReturn(1L);
        when(userRepository.selectList(any())).thenReturn(List.of(user(7L, "alice", "USER", null)));
        when(postService.publishedCounts(any())).thenReturn(Map.of("7", 3L));

        Map<String, Object> body = svc.page("admin", null, null, null, null, 1, 20);

        assertEquals(1L, body.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        assertEquals(1, items.size());
        Map<String, Object> row = items.get(0);
        assertEquals("7", row.get("id"));
        assertEquals("alice", row.get("username"));
        assertEquals(CommunityUserService.ROLE_USER, row.get("role"));
        assertEquals(User.STATUS_ACTIVE, row.get("account_status"), "null 状态按 ACTIVE 展示");
        assertEquals(3L, row.get("post_count"));
    }

    /* ---------- 治理动作语义 ---------- */

    @Test
    void govern_suspend_appliesStatusAndAudits() {
        when(userRepository.selectById(7L)).thenReturn(user(7L, "alice", "USER", User.STATUS_ACTIVE));

        Map<String, Object> r = svc.govern("admin", 7L, AdminUserService.ACT_SUSPEND, "多次违规");

        assertEquals(User.STATUS_SUSPENDED, r.get("account_status"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(communityUserService).applyGovernance(eq(7L), fieldsCaptor.capture());
        assertEquals(User.STATUS_SUSPENDED, fieldsCaptor.getValue().get("account_status"));
        verify(auditService, times(1)).record(anyString(), eq(AuditLog.CAT_ADMIN),
                eq("user_governed"), eq("user"), eq("7"), any());
    }

    @Test
    void govern_restore_activates() {
        when(userRepository.selectById(7L)).thenReturn(user(7L, "alice", "USER", User.STATUS_SUSPENDED));
        Map<String, Object> r = svc.govern("admin", 7L, AdminUserService.ACT_RESTORE, null);
        assertEquals(User.STATUS_ACTIVE, r.get("account_status"));
    }

    @Test
    void govern_postLimit_setsPostLimited() {
        when(userRepository.selectById(7L)).thenReturn(user(7L, "alice", "USER", User.STATUS_ACTIVE));
        svc.govern("admin", 7L, AdminUserService.ACT_POST_LIMIT, "广告刷屏");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(communityUserService).applyGovernance(eq(7L), fieldsCaptor.capture());
        assertEquals(Boolean.TRUE, fieldsCaptor.getValue().get("post_limited"));
    }

    /* ---------- 保护与兜底 ---------- */

    @Test
    void govern_adminTarget_rejected() {
        when(userRepository.selectById(7L)).thenReturn(user(7L, "boss", "ADMIN", User.STATUS_ACTIVE));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.govern("admin", 7L, AdminUserService.ACT_SUSPEND, null));
        assertTrue(ex.getMessage().contains("管理端账号不接受用户治理动作"));
        verify(communityUserService, never()).applyGovernance(anyLong(), any());
    }

    @Test
    void govern_missingUser_notFound() {
        when(userRepository.selectById(99L)).thenReturn(null);
        assertThrows(PostNotFoundException.class,
                () -> svc.govern("admin", 99L, AdminUserService.ACT_SUSPEND, null));
    }

    @Test
    void govern_unknownAction_rejected() {
        when(userRepository.selectById(7L)).thenReturn(user(7L, "alice", "USER", User.STATUS_ACTIVE));
        assertThrows(IllegalArgumentException.class,
                () -> svc.govern("admin", 7L, "FREEZE", null));
        verify(communityUserService, never()).applyGovernance(anyLong(), any());
    }

    /* ================= 角色分配（§11 第五阶段：权限点控制 + 高风险二次确认） ================= */

    /** 治理保护要覆盖所有管理端角色，不只是历史值 ADMIN（否则会限制到同事账号） */
    @Test
    void govern_anyAdminSideRole_rejected() {
        when(userRepository.selectById(8L)).thenReturn(user(8L, "editor", "CITY_EDITOR", User.STATUS_ACTIVE));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.govern("admin", 8L, AdminUserService.ACT_SUSPEND, null));
        assertTrue(ex.getMessage().contains("管理端账号不接受用户治理动作"));
        verify(communityUserService, never()).applyGovernance(anyLong(), any());
    }

    @Test
    void assignRole_withoutSecondConfirmation_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.assignRole("1", 7L, "CONTENT_REVIEWER", false, "招聘审核员"));
        verify(userRepository, never()).update(any(), any());
    }

    @Test
    void assignRole_withoutReason_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.assignRole("1", 7L, "CONTENT_REVIEWER", true, "   "));
        verify(userRepository, never()).update(any(), any());
    }

    @Test
    void assignRole_unknownRoleCode_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.assignRole("1", 7L, "ROOT", true, "给个权限"));
        verify(userRepository, never()).update(any(), any());
    }

    /** 历史值 ADMIN 不允许再被写回（迁移后只认 5 个设计角色） */
    @Test
    void assignRole_legacyAdminCode_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.assignRole("1", 7L, "ADMIN", true, "沿用旧值"));
        verify(userRepository, never()).update(any(), any());
    }

    @Test
    void assignRole_cannotChangeOwnRole() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.assignRole("7", 7L, "SUPER_ADMIN", true, "自我提权"));
        assertTrue(ex.getMessage().contains("不能修改自己的角色"));
        verify(userRepository, never()).update(any(), any());
    }

    @Test
    void assignRole_lastSuperAdmin_cannotBeDemoted() {
        when(userRepository.selectById(7L)).thenReturn(user(7L, "boss", "SUPER_ADMIN", User.STATUS_ACTIVE));
        when(userRepository.selectCount(any())).thenReturn(1L);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.assignRole("1", 7L, "CONTENT_REVIEWER", true, "转岗做审核"));

        assertTrue(ex.getMessage().contains("至少需要保留一名超级管理员"));
        verify(userRepository, never()).update(any(), any());
    }

    @Test
    void assignRole_demotingSuperAdmin_allowedWhenAnotherRemains() {
        when(userRepository.selectById(7L)).thenReturn(user(7L, "boss", "SUPER_ADMIN", User.STATUS_ACTIVE));
        when(userRepository.selectCount(any())).thenReturn(2L);

        Map<String, Object> r = svc.assignRole("1", 7L, "CONTENT_REVIEWER", true, "转岗做审核");

        assertEquals("CONTENT_REVIEWER", r.get("role"));
        assertEquals("内容审核员", r.get("role_label"));
        verify(userRepository).update(any(), any());
    }

    @Test
    void assignRole_promotesUser_andAuditsBeforeAfterWithReason() {
        when(userRepository.selectById(7L)).thenReturn(user(7L, "alice", "USER", User.STATUS_ACTIVE));

        Map<String, Object> r = svc.assignRole("1", 7L, "RECOMMENDATION_OPERATOR", true, "运营组新同事");

        assertEquals("RECOMMENDATION_OPERATOR", r.get("role"));
        assertEquals("推荐运营", r.get("role_label"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq("1"), eq(AuditLog.CAT_ADMIN), eq("user_role_assigned"),
                eq("user"), eq("7"), detail.capture());
        // 审计必须同时留下"从哪来、到哪去、为什么"——事后追责靠这三样
        assertEquals("USER", detail.getValue().get("from_role"));
        assertEquals("RECOMMENDATION_OPERATOR", detail.getValue().get("to_role"));
        assertEquals("运营组新同事", detail.getValue().get("reason"));
    }

    @Test
    void assignRole_sameRole_rejected() {
        when(userRepository.selectById(7L)).thenReturn(user(7L, "alice", "CITY_EDITOR", User.STATUS_ACTIVE));
        assertThrows(IllegalArgumentException.class,
                () -> svc.assignRole("1", 7L, "CITY_EDITOR", true, "重复设置"));
        verify(userRepository, never()).update(any(), any());
    }

    @Test
    void assignRole_missingUser_notFound() {
        when(userRepository.selectById(99L)).thenReturn(null);
        assertThrows(PostNotFoundException.class,
                () -> svc.assignRole("1", 99L, "CITY_EDITOR", true, "新增城市编辑"));
    }

    /** 降级为普通用户（收回管理权限）也是合法分配 */
    @Test
    void assignRole_canDemoteBackToPlainUser() {
        when(userRepository.selectById(7L)).thenReturn(user(7L, "alice", "CITY_EDITOR", User.STATUS_ACTIVE));

        Map<String, Object> r = svc.assignRole("1", 7L, "USER", true, "离职收回权限");

        assertEquals("USER", r.get("role"));
        assertEquals("普通用户", r.get("role_label"));
        verify(userRepository).update(any(), any());
    }
}
