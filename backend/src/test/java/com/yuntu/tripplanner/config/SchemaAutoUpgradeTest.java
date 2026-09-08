package com.yuntu.tripplanner.config;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 存量库自动升级 fail-fast 单测（审查报告 P0-4）：
 * strict=true 时 ALTER 失败 → 抛 IllegalStateException 阻止启动；
 * strict=false 时仅告警不阻断（逃生通道）。
 */
class SchemaAutoUpgradeTest {

    @Test
    void strictMode_alterFailure_blocksStartup() {
        JdbcTemplate jt = mock(JdbcTemplate.class);
        // information_schema 查列默认返回 0 → 走 ALTER 分支；ALTER 抛异常
        doThrow(new DataAccessException("Table 'users' doesn't exist") {
        }).when(jt).execute(anyString());

        SchemaAutoUpgrade upgrade = new SchemaAutoUpgrade(jt, true);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> upgrade.run(null));
        assertTrue(ex.getMessage().contains("users.role"));
        assertTrue(ex.getMessage().contains("SCHEMA_AUTO_UPGRADE_STRICT"));
    }

    @Test
    void nonStrictMode_alterFailure_onlyWarns() {
        JdbcTemplate jt = mock(JdbcTemplate.class);
        doThrow(new DataAccessException("boom") {
        }).when(jt).execute(anyString());

        SchemaAutoUpgrade upgrade = new SchemaAutoUpgrade(jt, false);
        assertDoesNotThrow(() -> upgrade.run(null));
        verify(jt, atLeastOnce()).execute(anyString());
    }

    @Test
    void strictMode_allColumnsPresent_noAlter_noThrow() {
        JdbcTemplate jt = mock(JdbcTemplate.class);
        // 列已存在（count=1）→ 不执行 ALTER，正常完成
        when(jt.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);

        SchemaAutoUpgrade upgrade = new SchemaAutoUpgrade(jt, true);
        assertDoesNotThrow(() -> upgrade.run(null));
        verify(jt, never()).execute(anyString());
    }
}
