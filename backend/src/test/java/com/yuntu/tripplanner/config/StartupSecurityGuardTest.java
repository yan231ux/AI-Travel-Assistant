package com.yuntu.tripplanner.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 启动凭据安全门禁单测（审查报告 P0-2/P0-3）：
 * local 放行默认值；非 local 命中演示密码/demo 密钥/过短密钥 → 阻断并给出指引。
 */
class StartupSecurityGuardTest {

    private static final String GOOD_PASSWORD = "s3cret-Admin-P@ss!";
    private static final String GOOD_SECRET = "this-is-a-64-char-random-secret-for-hs256-signing-0123456789abcdef";

    @Test
    void localProfile_withDefaults_passes() {
        assertNull(StartupSecurityGuard.check(
                new String[]{"local"}, StartupSecurityGuard.DEMO_ADMIN_PASSWORD,
                StartupSecurityGuard.DEMO_JWT_SECRET));
    }

    @Test
    void localProfile_caseInsensitive_passes() {
        assertNull(StartupSecurityGuard.check(
                new String[]{"prod", "LOCAL"}, StartupSecurityGuard.DEMO_ADMIN_PASSWORD,
                StartupSecurityGuard.DEMO_JWT_SECRET));
    }

    @Test
    void prodProfile_defaultCredentials_blocked() {
        String err = StartupSecurityGuard.check(
                new String[]{"prod"}, StartupSecurityGuard.DEMO_ADMIN_PASSWORD,
                StartupSecurityGuard.DEMO_JWT_SECRET);
        assertNotNull(err);
        assertTrue(err.contains("COMMUNITY_ADMIN_PASSWORD"));
        assertTrue(err.contains("JWT_SECRET"));
    }

    @Test
    void prodProfile_goodCredentials_passes() {
        assertNull(StartupSecurityGuard.check(
                new String[]{"prod"}, GOOD_PASSWORD, GOOD_SECRET));
    }

    @Test
    void prodProfile_goodSecret_butDefaultPassword_blocked() {
        String err = StartupSecurityGuard.check(
                new String[]{"prod"}, StartupSecurityGuard.DEMO_ADMIN_PASSWORD, GOOD_SECRET);
        assertNotNull(err);
        assertTrue(err.contains("COMMUNITY_ADMIN_PASSWORD"));
        assertFalse(err.contains("JWT_SECRET"));
    }

    @Test
    void prodProfile_goodPassword_butDemoSecret_blocked() {
        String err = StartupSecurityGuard.check(
                new String[]{"prod"}, GOOD_PASSWORD, StartupSecurityGuard.DEMO_JWT_SECRET);
        assertNotNull(err);
        assertTrue(err.contains("JWT_SECRET"));
    }

    @Test
    void prodProfile_shortSecret_blocked() {
        String err = StartupSecurityGuard.check(
                new String[]{"prod"}, GOOD_PASSWORD, "short-secret");
        assertNotNull(err);
        assertTrue(err.contains("JWT_SECRET"));
    }

    @Test
    void prodProfile_blankPassword_blocked() {
        String err = StartupSecurityGuard.check(
                new String[]{"prod"}, "  ", GOOD_SECRET);
        assertNotNull(err);
        assertTrue(err.contains("COMMUNITY_ADMIN_PASSWORD"));
    }
}
