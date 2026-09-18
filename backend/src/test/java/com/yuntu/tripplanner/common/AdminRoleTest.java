package com.yuntu.tripplanner.common;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 角色权限矩阵单测（设计方案 §11 第五阶段）。
 *
 * <p>矩阵是"谁能做什么"的唯一事实来源，一旦写错就是越权或误拦。测试锁三件事：
 * <ol>
 *   <li>每个角色的权限集与设计一致（多一个少一个都算错）；</li>
 *   <li>未知/空角色码必须 fail-safe 成 USER —— 绝不能因为角色名拼错而意外提权；</li>
 *   <li>历史值 ADMIN 权限等同超管 —— 迁移失败时管理员不得失权。</li>
 * </ol>
 */
class AdminRoleTest {

    /* ---------- 各角色权限集 ---------- */

    @Test
    void user_hasNoAdminPermission() {
        assertFalse(AdminRole.USER.isAdminSide());
        assertTrue(AdminRole.USER.permissions().isEmpty());
        for (AdminPermission p : AdminPermission.values()) {
            assertFalse(AdminRole.USER.has(p), "普通用户不应拥有 " + p.name());
        }
    }

    @Test
    void contentReviewer_reviewsContentAndSeesDashboard() {
        assertTrue(AdminRole.CONTENT_REVIEWER.isAdminSide());
        assertTrue(AdminRole.CONTENT_REVIEWER.has(AdminPermission.CONTENT_REVIEW));
        assertTrue(AdminRole.CONTENT_REVIEWER.has(AdminPermission.ANALYTICS_VIEW));
        // 审核员不该碰内容生产、推荐策略、底座数据、账号与审计
        assertFalse(AdminRole.CONTENT_REVIEWER.has(AdminPermission.GUIDE_MANAGE));
        assertFalse(AdminRole.CONTENT_REVIEWER.has(AdminPermission.RECOMMEND_OPS));
        assertFalse(AdminRole.CONTENT_REVIEWER.has(AdminPermission.SPOT_GOVERN));
        assertFalse(AdminRole.CONTENT_REVIEWER.has(AdminPermission.USER_GOVERN));
        assertFalse(AdminRole.CONTENT_REVIEWER.has(AdminPermission.AUDIT_VIEW));
    }

    @Test
    void cityEditor_managesGuidesAndSeesDashboard() {
        assertTrue(AdminRole.CITY_EDITOR.has(AdminPermission.GUIDE_MANAGE));
        assertTrue(AdminRole.CITY_EDITOR.has(AdminPermission.ANALYTICS_VIEW));
        // 城市编辑管内容，不管底座数据与账号
        assertFalse(AdminRole.CITY_EDITOR.has(AdminPermission.SPOT_GOVERN));
        assertFalse(AdminRole.CITY_EDITOR.has(AdminPermission.USER_GOVERN));
        assertFalse(AdminRole.CITY_EDITOR.has(AdminPermission.CONTENT_REVIEW));
        assertFalse(AdminRole.CITY_EDITOR.has(AdminPermission.RECOMMEND_OPS));
    }

    @Test
    void recommendationOperator_runsExperimentsAndSeeDashboard() {
        assertTrue(AdminRole.RECOMMENDATION_OPERATOR.has(AdminPermission.RECOMMEND_OPS));
        assertTrue(AdminRole.RECOMMENDATION_OPERATOR.has(AdminPermission.ANALYTICS_VIEW));
        assertFalse(AdminRole.RECOMMENDATION_OPERATOR.has(AdminPermission.SPOT_GOVERN));
        assertFalse(AdminRole.RECOMMENDATION_OPERATOR.has(AdminPermission.USER_GOVERN));
        assertFalse(AdminRole.RECOMMENDATION_OPERATOR.has(AdminPermission.AUDIT_VIEW));
        assertFalse(AdminRole.RECOMMENDATION_OPERATOR.has(AdminPermission.GUIDE_MANAGE));
    }

    @Test
    void superAdmin_ownsEverything() {
        assertTrue(AdminRole.SUPER_ADMIN.isAdminSide());
        assertEquals(EnumSet.allOf(AdminPermission.class),
                EnumSet.copyOf(AdminRole.SUPER_ADMIN.permissions()));
        for (AdminPermission p : AdminPermission.values()) {
            assertTrue(AdminRole.SUPER_ADMIN.has(p), "超管应拥有 " + p.name());
        }
    }

    /** 数据看板是只读的，规则上对所有管理端角色开放；这条锁住"别哪天只留给超管" */
    @Test
    void analyticsView_openToEveryAdminSideRole() {
        for (AdminRole r : AdminRole.values()) {
            if (r.isAdminSide()) {
                assertTrue(r.has(AdminPermission.ANALYTICS_VIEW),
                        r.name() + " 应有数据看板权限");
            }
        }
        assertFalse(AdminRole.USER.has(AdminPermission.ANALYTICS_VIEW));
    }

    /** 景点底座与账号治理是全系统影响面最大的两块，只应留给超管 */
    @Test
    void spotAndUserGovernance_superAdminOnly() {
        for (AdminRole r : AdminRole.values()) {
            if (r == AdminRole.SUPER_ADMIN || r == AdminRole.ADMIN) {
                continue;
            }
            assertFalse(r.has(AdminPermission.SPOT_GOVERN), r.name() + " 不应有景点治理权限");
            assertFalse(r.has(AdminPermission.USER_GOVERN), r.name() + " 不应有用户治理权限");
            assertFalse(r.has(AdminPermission.AUDIT_VIEW), r.name() + " 不应有审计查询权限");
        }
    }

    /* ---------- 角色码解析 ---------- */

    @Test
    void fromCode_isCaseInsensitiveAndTrimmed() {
        assertEquals(AdminRole.CONTENT_REVIEWER, AdminRole.fromCode("content_reviewer"));
        assertEquals(AdminRole.CONTENT_REVIEWER, AdminRole.fromCode("  CONTENT_REVIEWER  "));
        assertEquals(AdminRole.SUPER_ADMIN, AdminRole.fromCode("super_admin"));
    }

    /**
     * fail-safe：空值/未知值一律 USER。这条最关键 ——
     * 如果这里返回了 SUPER_ADMIN，数据库里一个拼错的角色名就等于开后门。
     */
    @Test
    void fromCode_unknownOrBlank_fallsBackToPlainUser() {
        assertEquals(AdminRole.USER, AdminRole.fromCode(null));
        assertEquals(AdminRole.USER, AdminRole.fromCode(""));
        assertEquals(AdminRole.USER, AdminRole.fromCode("   "));
        assertEquals(AdminRole.USER, AdminRole.fromCode("ROOT"));
        assertEquals(AdminRole.USER, AdminRole.fromCode("ADMINISTRATOR"));
        assertEquals(AdminRole.USER, AdminRole.fromCode("SUPER_ADMIN_X"));
    }

    /** 历史值 ADMIN 兼容：迁移没跑到也不能让管理员失权 */
    @Test
    void legacyAdminRole_keepsSuperAdminPermissions() {
        assertEquals(AdminRole.ADMIN, AdminRole.fromCode("ADMIN"));
        assertTrue(AdminRole.ADMIN.isAdminSide());
        assertEquals(EnumSet.allOf(AdminPermission.class),
                EnumSet.copyOf(AdminRole.ADMIN.permissions()));
    }

    /* ---------- 可分配性 ---------- */

    @Test
    void isAssignable_acceptsDesignRolesOnly() {
        assertTrue(AdminRole.isAssignable("USER"));
        assertTrue(AdminRole.isAssignable("CONTENT_REVIEWER"));
        assertTrue(AdminRole.isAssignable("CITY_EDITOR"));
        assertTrue(AdminRole.isAssignable("RECOMMENDATION_OPERATOR"));
        assertTrue(AdminRole.isAssignable("SUPER_ADMIN"));
        assertTrue(AdminRole.isAssignable("content_reviewer")); // 大小写不敏感便于接口调用
    }

    /** 历史值 ADMIN 与臆造值都不能被当作"可分配角色"写回库 */
    @Test
    void isAssignable_rejectsLegacyAndUnknownCodes() {
        assertFalse(AdminRole.isAssignable("ADMIN"));
        assertFalse(AdminRole.isAssignable("SUPER_ADMIN_X"));
        assertFalse(AdminRole.isAssignable("ROOT"));
        assertFalse(AdminRole.isAssignable(null));
        assertFalse(AdminRole.isAssignable("  "));
    }

    /* ---------- 展示名 ---------- */

    @Test
    void everyRoleHasNonBlankLabel() {
        for (AdminRole r : AdminRole.values()) {
            assertNotNull(r.label());
            assertFalse(r.label().isBlank(), r.name() + " 缺少中文名");
        }
        for (AdminPermission p : AdminPermission.values()) {
            assertNotNull(p.label());
            assertFalse(p.label().isBlank(), p.name() + " 缺少中文名");
        }
    }

    @Test
    void has_nullPermission_isFalseNotThrow() {
        assertFalse(AdminRole.SUPER_ADMIN.has(null));
        assertFalse(AdminRole.USER.has(null));
    }
}
