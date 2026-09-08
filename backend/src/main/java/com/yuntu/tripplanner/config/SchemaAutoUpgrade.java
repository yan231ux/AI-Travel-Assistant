package com.yuntu.tripplanner.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 存量库结构自动升级（阶段二 users.role 列起；阶段四持续补充新列）。
 *
 * <p>schema.sql 由 spring.sql.init 每次启动执行，但 `CREATE TABLE IF NOT EXISTS` 对已存在的
 * 旧表不会补新列（MySQL 8 无 ADD COLUMN IF NOT EXISTS）。这里启动时检测 information_schema，
 * 缺列则 ALTER 一次，幂等安全。社区其余新表由 schema.sql 自动创建。
 *
 * <p>审查报告 P0-4：旧库升级失败不能再"记 warn 后继续启动"——缺 role/quality_score/ab_variant
 * 的应用虽在运行，相关接口会运行时才报 Unknown column，比启动失败更难排查。默认
 * schema.auto-upgrade-strict=true：任一关键列 ALTER 失败立即抛异常阻止启动，并输出修复指引；
 * 确需手工处理旧库时可设环境变量 SCHEMA_AUTO_UPGRADE_STRICT=false 逃生（仅记 warn）。
 */
@Slf4j
@Order(1)
@Component
public class SchemaAutoUpgrade implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final boolean strict;

    public SchemaAutoUpgrade(JdbcTemplate jdbcTemplate,
                             @Value("${schema.auto-upgrade-strict:true}") boolean strict) {
        this.jdbcTemplate = jdbcTemplate;
        this.strict = strict;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            log.info("数据库版本: {}", jdbcTemplate.queryForObject("SELECT VERSION()", String.class));
        } catch (Exception e) {
            log.warn("读取数据库版本失败: {}", e.getMessage());
        }
        log.info("存量库结构自动升级开始（strict={}，失败即阻止启动）", strict);
        int checked = 0;
        checked += ensureColumn("users", "role",
                "ALTER TABLE users ADD COLUMN role VARCHAR(20) DEFAULT 'USER' COMMENT '角色：USER/ADMIN（社区审核用）'");
        // 阶段四任务 5/8：帖子内容质量分与低质标记（存量库自动补列）
        checked += ensureColumn("travel_post", "quality_score",
                "ALTER TABLE travel_post ADD COLUMN quality_score INT DEFAULT 0 COMMENT '内容质量分 0~100' AFTER reject_reason");
        checked += ensureColumn("travel_post", "low_quality",
                "ALTER TABLE travel_post ADD COLUMN low_quality TINYINT DEFAULT 0 COMMENT '低质标记' AFTER quality_score");
        // 阶段四任务 6/7：帖子推荐流曝光日志补 A/B 变体列（存量库自动补列；新库由 schema.sql 直接建）
        checked += ensureColumn("post_feed_log", "ab_variant",
                "ALTER TABLE post_feed_log ADD COLUMN ab_variant VARCHAR(20) COMMENT '所属A/B变体：CONTROL/TREATMENT' AFTER ranking_version");
        // P1-5（审查报告）：两条推荐流曝光日志补页面会话幂等键 feed_trace_id，
        // 配合写入前 (user, trace, item) 判重，防止前端重试/重复渲染双写曝光稀释统计分母。
        checked += ensureColumn("post_feed_log", "feed_trace_id",
                "ALTER TABLE post_feed_log ADD COLUMN feed_trace_id VARCHAR(40) COMMENT '曝光幂等键（页面会话trace，防重复渲染双写）' AFTER ab_variant");
        checked += ensureColumn("spot_feed_log", "feed_trace_id",
                "ALTER TABLE spot_feed_log ADD COLUMN feed_trace_id VARCHAR(40) COMMENT '曝光幂等键（页面会话trace，防重复渲染双写）' AFTER ab_variant");
        log.info("存量库结构自动升级完成：共检查 {} 个关键列", checked);
    }

    /**
     * @return 1=本次补齐了列；0=已存在或（非严格模式）升级失败
     */
    private int ensureColumn(String table, String column, String alterSql) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column);
            if (count != null && count > 0) {
                log.debug("存量表 {}.{} 已存在，跳过自动升级", table, column);
                return 0;
            }
            jdbcTemplate.execute(alterSql);
            log.info("存量表自动升级完成：{}.{} 列已补充", table, column);
            return 1;
        } catch (Exception e) {
            if (strict) {
                // P0-4（审查报告）：关键结构升级失败必须 fail-fast，否则应用"看起来在线"、
                // 接口却在运行时才报 Unknown column，比启动失败更难排查。
                log.error("存量表自动升级失败（{}.{}），按 schema.auto-upgrade-strict=true 阻止启动: {}",
                        table, column, e.getMessage());
                throw new IllegalStateException(
                        "数据库结构自动升级失败：" + table + "." + column + "（" + e.getMessage()
                                + "）。请检查旧库结构；确需手工处理可设 SCHEMA_AUTO_UPGRADE_STRICT=false "
                                + "启动（此时仅告警不阻断）。", e);
            }
            log.warn("存量表自动升级失败（{}:{}，可手工执行 ALTER）: {}", table, column, e.getMessage());
            return 0;
        }
    }
}
