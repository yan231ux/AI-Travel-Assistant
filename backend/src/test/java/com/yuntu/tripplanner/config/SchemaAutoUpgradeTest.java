package com.yuntu.tripplanner.config;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 存量库自动升级单测（审查报告 P0-4 + 角色细化 §11）：
 * strict=true 时 ALTER 失败 → 抛 IllegalStateException 阻止启动；
 * strict=false 时仅告警不阻断（逃生通道）；
 * 列宽不足（role 原为 VARCHAR(20)，装不下 RECOMMENDATION_OPERATOR）→ 幂等加宽。
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
    void strictMode_allColumnsAndTablesPresent_noDdl_noThrow() {
        JdbcTemplate jt = mock(JdbcTemplate.class);
        // 表已存在（3 参重载：sql, 类型, 表）→ 不执行建表（阶段四收尾新增的日表补建检查）
        when(jt.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(1);
        // 列已存在（4 参重载：sql, 类型, 表, 列）→ 不执行 ALTER
        when(jt.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
        // 角色列宽已足够（≥32）→ 不做 MODIFY（列宽查询与列存在查询同为 4 参，故需按 SQL 区分）
        when(jt.queryForObject(contains("CHARACTER_MAXIMUM_LENGTH"), eq(Integer.class), any(), any()))
                .thenReturn(32);

        SchemaAutoUpgrade upgrade = new SchemaAutoUpgrade(jt, true);
        assertDoesNotThrow(() -> upgrade.run(null));
        verify(jt, never()).execute(anyString());
    }

    @Test
    void roleColumnTooNarrow_widensColumn() {
        JdbcTemplate jt = mock(JdbcTemplate.class);
        when(jt.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(1);
        when(jt.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);
        // 旧库 role 是 VARCHAR(20)：CONTENT_REVIEWER(16)/CITY_EDITOR(11) 装得下，
        // 但 RECOMMENDATION_OPERATOR(23) 会 Data too long → 必须启动期加宽
        when(jt.queryForObject(contains("CHARACTER_MAXIMUM_LENGTH"), eq(Integer.class), any(), any()))
                .thenReturn(20);

        SchemaAutoUpgrade upgrade = new SchemaAutoUpgrade(jt, true);
        assertDoesNotThrow(() -> upgrade.run(null));
        verify(jt, times(1)).execute(contains("MODIFY COLUMN role VARCHAR(32)"));
    }
}
