package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.common.AdminRole;
import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.model.User;
import com.yuntu.tripplanner.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 角色/权限点校验单测（设计方案 §11 第五阶段：角色细化）：
 * 两层授权语义（requireAdmin=能进后台，requirePermission=能操作哪一块）、
 * 域隔离（审核员进不了攻略运营）、fail-safe（未知/空角色一律 USER，绝不提权）、
 * 历史 ADMIN 兼容（迁移差异不导致失权）。
 */
@ExtendWith(MockitoExtension.class)
class CommunityUserServicePermissionTest {

    @Mock
    private UserRepository userRepository;

    private CommunityUserService svc;

    @BeforeEach
    void setUp() {
        svc = new CommunityUserService(userRepository);
    }

    private void withRole(long id, String role) {
        User u = new User();
        u.setId(id);
        u.setUsername("u" + id);
        u.setRole(role);
        when(userRepository.selectById(id)).thenReturn(u);
    }

    /* ---------- 角色解析与 fail-safe ---------- */

    @Test
    void unknownRoleCode_fallsBackToUser_neverEscalates() {
        withRole(5L, "SUPERUSER");   // 拼错/伪造的角色码
        assertFalse(svc.isAdmin("5"));
        assertEquals(AdminRole.USER, svc.adminRoleOf("5"));
        assertTrue(svc.permissionsOf("5").isEmpty());
    }

    @Test
    void blankOrNullRole_fallsBackToUser() {
        withRole(6L, null);
        assertFalse(svc.isAdmin("6"));
        assertFalse(svc.hasPermission("6", AdminPermission.CONTENT_REVIEW));
    }

    @Test
    void nonNumericUserId_isNotAdmin() {
        assertFalse(svc.isAdmin("abc"));
        assertFalse(svc.isAdmin(null));
    }

    @Test
    void legacyAdminRole_stillFullPermissions_compatForMigrationGap() {
        withRole(7L, "ADMIN");
        assertTrue(svc.isAdmin("7"));
        assertEquals(AdminPermission.values().length, svc.permissionsOf("7").size());
    }

    /* ---------- 两层授权：粗粒度 vs 细粒度 ---------- */

    @Test
    void requireAdmin_onlyChecksBackendAccess_notDomain() {
        withRole(9L, "CONTENT_REVIEWER");
        assertDoesNotThrow(() -> svc.requireAdmin("9"));   // 能进后台
        // 但进不了攻略运营域
        assertThrows(ForbiddenException.class,
                () -> svc.requirePermission("9", AdminPermission.GUIDE_MANAGE));
    }

    @Test
    void requirePermission_plainUser_forbiddenWithGenericMessage() {
        withRole(10L, "USER");
        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> svc.requirePermission("10", AdminPermission.ANALYTICS_VIEW));
        assertEquals("该操作需要管理员权限", ex.getMessage());
    }

    @Test
    void requirePermission_missingDomain_listsRoleAndPermissionInMessage() {
        withRole(11L, "CITY_EDITOR");
        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> svc.requirePermission("11", AdminPermission.CONTENT_REVIEW));
        // 403 文案要说清"我是谁、缺什么"，否则运营只会来问"为什么进不去"
        assertTrue(ex.getMessage().contains("城市内容编辑"), ex.getMessage());
        assertTrue(ex.getMessage().contains(AdminPermission.CONTENT_REVIEW.label()), ex.getMessage());
    }

    @Test
    void requirePermission_grantedDomain_passes() {
        withRole(12L, "CITY_EDITOR");
        assertDoesNotThrow(() -> svc.requirePermission("12", AdminPermission.GUIDE_MANAGE));
    }

    /* ---------- 域隔离矩阵：各角色只开自己那一块 ---------- */

    @Test
    void contentReviewer_seesModerationAndDashboard_only() {
        withRole(20L, "CONTENT_REVIEWER");
        assertTrue(svc.hasPermission("20", AdminPermission.CONTENT_REVIEW));
        assertTrue(svc.hasPermission("20", AdminPermission.ANALYTICS_VIEW));
        assertFalse(svc.hasPermission("20", AdminPermission.GUIDE_MANAGE));
        assertFalse(svc.hasPermission("20", AdminPermission.SPOT_GOVERN));
        assertFalse(svc.hasPermission("20", AdminPermission.USER_GOVERN));
        assertFalse(svc.hasPermission("20", AdminPermission.AUDIT_VIEW));
    }

    @Test
    void cityEditor_seesGuideAndDashboard_only() {
        withRole(21L, "CITY_EDITOR");
        assertTrue(svc.hasPermission("21", AdminPermission.GUIDE_MANAGE));
        assertTrue(svc.hasPermission("21", AdminPermission.ANALYTICS_VIEW));
        assertFalse(svc.hasPermission("21", AdminPermission.CONTENT_REVIEW));
        assertFalse(svc.hasPermission("21", AdminPermission.RECOMMEND_OPS));
    }

    @Test
    void recommendationOperator_seesFeedOpsAndDashboard_only() {
        withRole(22L, "RECOMMENDATION_OPERATOR");
        assertTrue(svc.hasPermission("22", AdminPermission.RECOMMEND_OPS));
        assertTrue(svc.hasPermission("22", AdminPermission.ANALYTICS_VIEW));
        assertFalse(svc.hasPermission("22", AdminPermission.SPOT_GOVERN));
        assertFalse(svc.hasPermission("22", AdminPermission.AUDIT_VIEW));
    }

    @Test
    void superAdmin_holdsEveryPermission() {
        withRole(23L, "SUPER_ADMIN");
        for (AdminPermission p : AdminPermission.values()) {
            assertTrue(svc.hasPermission("23", p), "超管应拥有 " + p.name());
        }
    }

    /* ---------- 高爆炸半径权限只有超管能碰 ---------- */

    @Test
    void highBlastRadiusPermissions_reservedForSuperAdmin() {
        withRole(24L, "CONTENT_REVIEWER");
        withRole(25L, "CITY_EDITOR");
        withRole(26L, "RECOMMENDATION_OPERATOR");
        for (String uid : new String[]{"24", "25", "26"}) {
            assertFalse(svc.hasPermission(uid, AdminPermission.SPOT_GOVERN), uid);
            assertFalse(svc.hasPermission(uid, AdminPermission.USER_GOVERN), uid);
            assertFalse(svc.hasPermission(uid, AdminPermission.AUDIT_VIEW), uid);
        }
    }

    /* ---------- 超管专属：不属于任何业务域的写操作 ---------- */

    @Test
    void requireSuperAdmin_superAdminAndLegacyAdmin_pass() {
        withRole(30L, "SUPER_ADMIN");
        withRole(31L, "ADMIN");
        assertDoesNotThrow(() -> svc.requireSuperAdmin("30"));
        assertDoesNotThrow(() -> svc.requireSuperAdmin("31"), "历史值 ADMIN 权限等同超管，不应被卡");
    }

    @Test
    void requireSuperAdmin_allOtherRoles_forbidden_evenWithAnalyticsView() {
        // 回归（原缺陷）：recompute 这类写操作曾挂在只读的 ANALYTICS_VIEW 上，
        // 结果"能看看板"就等于"能触发重算"。这三个管理端角色都持有 ANALYTICS_VIEW，
        // 但一个都不该通过超管专属校验。
        withRole(32L, "CONTENT_REVIEWER");
        withRole(33L, "CITY_EDITOR");
        withRole(34L, "RECOMMENDATION_OPERATOR");
        withRole(35L, "USER");
        for (String uid : new String[]{"32", "33", "34", "35"}) {
            ForbiddenException ex = assertThrows(ForbiddenException.class,
                    () -> svc.requireSuperAdmin(uid), uid + " 不应通过超管专属校验");
            assertEquals("该操作仅超级管理员可执行", ex.getMessage());
        }
        // 前提成立性：非超管的三个管理端角色确实"能看看板"，所以它们被卡住的
        // 原因只能是超管门槛，而不是"连看板都进不去"。
        for (String uid : new String[]{"32", "33", "34"}) {
            assertTrue(svc.hasPermission(uid, AdminPermission.ANALYTICS_VIEW),
                    uid + " 应持有 ANALYTICS_VIEW（只读看板对所有管理端角色开放）");
        }
        assertFalse(svc.hasPermission("35", AdminPermission.ANALYTICS_VIEW), "普通用户没有看板权限");
    }
}
