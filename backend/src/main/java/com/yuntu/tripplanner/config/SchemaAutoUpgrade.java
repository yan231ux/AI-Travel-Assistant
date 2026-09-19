package com.yuntu.tripplanner.config;

import com.yuntu.tripplanner.common.AdminRole;
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
                "ALTER TABLE users ADD COLUMN role VARCHAR(32) DEFAULT 'USER' COMMENT '角色：USER/CONTENT_REVIEWER/CITY_EDITOR/RECOMMENDATION_OPERATOR/SUPER_ADMIN'");
        // 用户与账号治理（设计方案 §7，存量库自动补列）
        checked += ensureColumn("users", "account_status",
                "ALTER TABLE users ADD COLUMN account_status VARCHAR(16) DEFAULT 'ACTIVE' COMMENT '账号状态：ACTIVE/SUSPENDED' AFTER role");
        checked += ensureColumn("users", "post_limited",
                "ALTER TABLE users ADD COLUMN post_limited TINYINT DEFAULT 0 COMMENT '限制发帖标记' AFTER account_status");
        checked += ensureColumn("users", "comment_banned",
                "ALTER TABLE users ADD COLUMN comment_banned TINYINT DEFAULT 0 COMMENT '暂停评论标记' AFTER post_limited");
        checked += ensureColumn("users", "violation_count",
                "ALTER TABLE users ADD COLUMN violation_count INT DEFAULT 0 COMMENT '违规次数（举报成立累计）' AFTER comment_banned");
        checked += ensureColumn("users", "last_login_at",
                "ALTER TABLE users ADD COLUMN last_login_at DATETIME COMMENT '最近登录时间' AFTER violation_count");
        // 确认去过功能：trip_record 加 visited_confirmed 列（规划默认 0，用户确认后置 1）。
        // 「去过」全系统只认确认过的行程，城市芯片/景点"你曾去过"/推荐去重统一走 VisitedService。
        checked += ensureColumn("trip_record", "visited_confirmed",
                "ALTER TABLE trip_record ADD COLUMN visited_confirmed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已确认去过（0=仅规划，1=确认去过）' AFTER deleted");
        // 阶段四任务 5/8：帖子内容质量分与低质标记（存量库自动补列）
        checked += ensureColumn("travel_post", "quality_score",
                "ALTER TABLE travel_post ADD COLUMN quality_score INT DEFAULT 0 COMMENT '内容质量分 0~100' AFTER reject_reason");
        checked += ensureColumn("travel_post", "low_quality",
                "ALTER TABLE travel_post ADD COLUMN low_quality TINYINT DEFAULT 0 COMMENT '低质标记' AFTER quality_score");
        // 审查报告 P1-1：帖子版本化 —— 主表存线上公开版本，待审编辑版本落 travel_post_revision。
        // 存量库既要补指针列，也要补版本表（schema.sql 的 CREATE TABLE IF NOT EXISTS 只对新库生效）。
        checked += ensureColumn("travel_post", "pending_revision_id",
                "ALTER TABLE travel_post ADD COLUMN pending_revision_id BIGINT COMMENT '待审核的编辑版本ID（NULL=无）' AFTER low_quality");
        checked += ensureTable("travel_post_revision", "CREATE TABLE IF NOT EXISTS travel_post_revision ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',"
                + "post_id BIGINT NOT NULL COMMENT '帖子ID',"
                + "revision_no INT NOT NULL DEFAULT 1 COMMENT '版本号（同帖内自增，1 起）',"
                + "title VARCHAR(120) NOT NULL COMMENT '标题（版本快照）',"
                + "summary VARCHAR(300) COMMENT '摘要',"
                + "content TEXT NOT NULL COMMENT '正文',"
                + "cover_image VARCHAR(500) COMMENT '封面图URL',"
                + "city VARCHAR(80) COMMENT '关联城市',"
                + "travel_days INT COMMENT '行程天数',"
                + "budget DOUBLE COMMENT '预算（元）',"
                + "pace VARCHAR(20) COMMENT '节奏：轻松/适中/紧凑',"
                + "post_type VARCHAR(30) COMMENT '类型：GUIDE/SPOT_RECOMMENDATION/ITINERARY/NOTE',"
                + "spots_json TEXT COMMENT '关联景点快照JSON',"
                + "status VARCHAR(20) NOT NULL DEFAULT 'PENDING_REVIEW' COMMENT 'PENDING_REVIEW/APPROVED/REJECTED/SUPERSEDED',"
                + "reject_reason VARCHAR(300) COMMENT '审核拒绝原因',"
                + "reviewed_by VARCHAR(50) COMMENT '审核人',"
                + "reviewed_at DATETIME COMMENT '审核时间',"
                + "editor_id VARCHAR(50) COMMENT '提交该版本的用户ID',"
                + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',"
                + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',"
                + "INDEX idx_rev_post (post_id, revision_no),"
                + "INDEX idx_rev_status_time (status, created_at)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子编辑版本';");
        // 阶段四任务 6/7：帖子推荐流曝光日志补 A/B 变体列（存量库自动补列；新库由 schema.sql 直接建）
        checked += ensureColumn("post_feed_log", "ab_variant",
                "ALTER TABLE post_feed_log ADD COLUMN ab_variant VARCHAR(20) COMMENT '所属A/B变体：CONTROL/TREATMENT' AFTER ranking_version");
        // P1-5（审查报告）：两条推荐流曝光日志补页面会话幂等键 feed_trace_id，
        // 配合写入前 (user, trace, item) 判重，防止前端重试/重复渲染双写曝光稀释统计分母。
        checked += ensureColumn("post_feed_log", "feed_trace_id",
                "ALTER TABLE post_feed_log ADD COLUMN feed_trace_id VARCHAR(40) COMMENT '曝光幂等键（页面会话trace，防重复渲染双写）' AFTER ab_variant");
        checked += ensureColumn("spot_feed_log", "feed_trace_id",
                "ALTER TABLE spot_feed_log ADD COLUMN feed_trace_id VARCHAR(40) COMMENT '曝光幂等键（页面会话trace，防重复渲染双写）' AFTER ab_variant");
        // 管理后台内容运营（设计方案 §3.4）：举报处理备注列（存量库自动补列，详情页/历史可追溯）
        checked += ensureColumn("content_report", "handle_note",
                "ALTER TABLE content_report ADD COLUMN handle_note VARCHAR(500) COMMENT '处理备注（管理员处理时填写）' AFTER handled_at");
        // B组 景点数据治理（设计方案 §5）：spot 表 8 个治理列，存量库自动补列。
        // 说明：schema.sql 的 CREATE TABLE IF NOT EXISTS 只对新库生效，老库必须走这里 ALTER。
        checked += ensureColumn("spot", "status",
                "ALTER TABLE spot ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ONLINE' COMMENT '上下架：ONLINE/OFFLINE' AFTER last_synced_at");
        checked += ensureColumn("spot", "flag",
                "ALTER TABLE spot ADD COLUMN flag VARCHAR(30) COMMENT '治理标记：NON_SPOT/CLOSED/OUTDATED/ERROR_POI' AFTER status");
        checked += ensureColumn("spot", "flag_reason",
                "ALTER TABLE spot ADD COLUMN flag_reason VARCHAR(500) COMMENT '治理原因（管理员填写）' AFTER flag");
        checked += ensureColumn("spot", "manual_override",
                "ALTER TABLE spot ADD COLUMN manual_override TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否有人工修正（同步不覆盖锁定字段）' AFTER flag_reason");
        checked += ensureColumn("spot", "manual_override_fields",
                "ALTER TABLE spot ADD COLUMN manual_override_fields VARCHAR(300) COMMENT '人工锁定字段（逗号分隔）' AFTER manual_override");
        checked += ensureColumn("spot", "last_verified_by",
                "ALTER TABLE spot ADD COLUMN last_verified_by VARCHAR(50) COMMENT '最近人工审核人' AFTER manual_override_fields");
        checked += ensureColumn("spot", "last_verified_at",
                "ALTER TABLE spot ADD COLUMN last_verified_at DATETIME COMMENT '最近人工审核时间' AFTER last_verified_by");
        checked += ensureColumn("spot", "merged_into",
                "ALTER TABLE spot ADD COLUMN merged_into VARCHAR(100) COMMENT '重复合并目标 spot_id' AFTER last_verified_at");
        // 阶段四收尾（设计方案 §7.3 + 首页热度反哺）：三张日表的存量库建表兜底。
        // 说明：schema.sql 的 CREATE TABLE IF NOT EXISTS 只对新库生效，老库必须在这里建。
        checked += ensureTable("spot_trending_daily", "CREATE TABLE IF NOT EXISTS spot_trending_daily ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',"
                + "stat_date DATE NOT NULL COMMENT '统计日期',"
                + "item_id VARCHAR(100) NOT NULL COMMENT '景点ID（spot_id/poi_id/name:xxx，与事件口径一致）',"
                + "item_name VARCHAR(200) COMMENT '名称快照',"
                + "city VARCHAR(64) COMMENT '城市',"
                + "generated_count INT NOT NULL DEFAULT 0 COMMENT '被规划采用次数（SPOT_GENERATED）',"
                + "saved_count INT NOT NULL DEFAULT 0 COMMENT '随行程保存次数（SPOT_SAVED）',"
                + "favorited_count INT NOT NULL DEFAULT 0 COMMENT '被收藏次数（SPOT_FAVORITED）',"
                + "user_count INT NOT NULL DEFAULT 0 COMMENT '去重用户数（任意事件）',"
                + "planning_user_count INT NOT NULL DEFAULT 0 COMMENT '规划采用去重用户数（SPOT_GENERATED，日级）',"
                + "click_count INT NOT NULL DEFAULT 0 COMMENT '详情点击次数（user_behavior CLICK，日级）',"
                + "dislike_count INT NOT NULL DEFAULT 0 COMMENT '负反馈次数（user_behavior DISLIKE，日级）',"
                + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',"
                + "UNIQUE KEY uk_trending_date_item (stat_date, item_id),"
                + "INDEX idx_trending_city_date (city, stat_date)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='景点热度按天预聚合'");
        // 热度分所需的三项指标列：表已存在但缺列时补上（新建的表上面已含这三列，此处会跳过）
        checked += ensureColumn("spot_trending_daily", "planning_user_count",
                "ALTER TABLE spot_trending_daily ADD COLUMN planning_user_count INT NOT NULL DEFAULT 0 "
                        + "COMMENT '规划采用去重用户数（SPOT_GENERATED，日级）' AFTER user_count");
        checked += ensureColumn("spot_trending_daily", "click_count",
                "ALTER TABLE spot_trending_daily ADD COLUMN click_count INT NOT NULL DEFAULT 0 "
                        + "COMMENT '详情点击次数（user_behavior CLICK，日级）' AFTER planning_user_count");
        checked += ensureColumn("spot_trending_daily", "dislike_count",
                "ALTER TABLE spot_trending_daily ADD COLUMN dislike_count INT NOT NULL DEFAULT 0 "
                        + "COMMENT '负反馈次数（user_behavior DISLIKE，日级）' AFTER click_count");
        checked += ensureTable("city_trending_daily", "CREATE TABLE IF NOT EXISTS city_trending_daily ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',"
                + "stat_date DATE NOT NULL COMMENT '统计日期',"
                + "city VARCHAR(64) NOT NULL COMMENT '城市',"
                + "trip_generated_count INT NOT NULL DEFAULT 0 COMMENT '行程生成次数（TRIP_GENERATED）',"
                + "trip_saved_count INT NOT NULL DEFAULT 0 COMMENT '行程保存次数（TRIP_SAVED）',"
                + "spot_adopt_count INT NOT NULL DEFAULT 0 COMMENT '景点被规划采用次数（SPOT_GENERATED）',"
                + "user_count INT NOT NULL DEFAULT 0 COMMENT '去重活跃用户数',"
                + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',"
                + "UNIQUE KEY uk_city_trending_date_city (stat_date, city),"
                + "INDEX idx_city_trending_date (stat_date)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='城市热度按天预聚合'");
        checked += ensureTable("content_moderation_daily", "CREATE TABLE IF NOT EXISTS content_moderation_daily ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',"
                + "stat_date DATE NOT NULL COMMENT '统计日期（取任务创建时间所在日）',"
                + "total_count INT NOT NULL DEFAULT 0 COMMENT '任务总量',"
                + "auto_passed_count INT NOT NULL DEFAULT 0 COMMENT '自动放行（PASSED）',"
                + "review_count INT NOT NULL DEFAULT 0 COMMENT '转人工复核（REVIEW）',"
                + "failed_count INT NOT NULL DEFAULT 0 COMMENT 'AI 失败（FAILED）',"
                + "human_approved_count INT NOT NULL DEFAULT 0 COMMENT '人工通过（decision=APPROVE）',"
                + "human_rejected_count INT NOT NULL DEFAULT 0 COMMENT '人工拒绝（decision=REJECT）',"
                + "rule_hit_count INT NOT NULL DEFAULT 0 COMMENT '规则命中任务数',"
                + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',"
                + "UNIQUE KEY uk_moderation_daily_date (stat_date)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容审核按天预聚合'");
        // ================= 角色细化迁移（设计方案 §11 第五阶段） =================
        // ① 先加宽 role 列：新角色码最长 23 字符（RECOMMENDATION_OPERATOR），而列宽是建表时的
        // VARCHAR(20)。不加宽时"分配推荐运营"会直接 500（Data too long for column 'role'），
        // 是只在特定角色上才触发的隐性坑 —— 冒烟测试实测踩到，故做成启动期幂等加宽。
        checked += ensureColumnWidth("users", "role", 32,
                "ALTER TABLE users MODIFY COLUMN role VARCHAR(32) DEFAULT 'USER' "
                        + "COMMENT '角色：USER/CONTENT_REVIEWER/CITY_EDITOR/RECOMMENDATION_OPERATOR/SUPER_ADMIN'");
        // ② 再迁移角色值：存量 role 只有 'ADMIN'，新模型是 CONTENT_REVIEWER/CITY_EDITOR/
        // RECOMMENDATION_OPERATOR/SUPER_ADMIN。不迁移的后果很严重：既有管理员会变成"角色未知"
        // 从而失去全部管理权限（锁死），因此这里必须幂等地把历史 ADMIN 抬成 SUPER_ADMIN。
        // 失败只告警不阻止启动：列已存在、且代码层 AdminRole.ADMIN 仍映射为超管权限，
        // 功能上不会失权，没必要为一次数据订正把整个应用拦在门外（与结构升级的 fail-fast 区别在此）。
        try {
            int migrated = jdbcTemplate.update("UPDATE users SET role = ? WHERE role = ?",
                    AdminRole.SUPER_ADMIN.name(), AdminRole.ADMIN.name());
            log.info("角色细化迁移完成：{} 个历史 ADMIN 账号已升级为 SUPER_ADMIN", migrated);
        } catch (Exception e) {
            log.warn("角色迁移失败（不影响启动，代码内 ADMIN→超管兼容映射仍生效）: {}", e.getMessage());
        }
        // ReAct 优化批次 3：采集方案存档表（存量库兜底建表；新库由 schema.sql 直接建）。
        // 自主模式"只自主一次 + 复用"依赖此表持久化工具计划，缺表时复用会静默降级为不复用
        // （不阻断生成），但那样答辩演示看不到复用效果，故这里与其它新表一致走启动期建表。
        checked += ensureTable("agent_plan_archive", "CREATE TABLE IF NOT EXISTS agent_plan_archive ("
                + "id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',"
                + "plan_key VARCHAR(255) NOT NULL COMMENT '复用键：userId|destination|preferences|pace|hotelLevel|dietary|specialNotes',"
                + "user_id VARCHAR(50) COMMENT '用户ID（从 plan_key 拆出）',"
                + "destination VARCHAR(64) COMMENT '目的地（从 plan_key 拆出）',"
                + "plan_json TEXT NOT NULL COMMENT '方案快照JSON',"
                + "plan_desc VARCHAR(500) COMMENT '计划说明',"
                + "source VARCHAR(32) COMMENT '方案来源：autonomous-native/autonomous-text/legacy-text',"
                + "tool_count INT NOT NULL DEFAULT 0 COMMENT '工具调用数量',"
                + "observation_summary VARCHAR(1000) COMMENT '关键观察摘要',"
                + "reuse_count INT NOT NULL DEFAULT 0 COMMENT '被复用次数',"
                + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',"
                + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',"
                + "UNIQUE KEY uk_plan_key (plan_key),"
                + "INDEX idx_plan_user (user_id, updated_at),"
                + "INDEX idx_plan_dest (destination, updated_at)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ReAct 采集方案存档'");
        log.info("存量库结构自动升级完成：共检查 {} 个关键列/表", checked);
    }

    /**
     * 列宽兜底：列已存在但宽度不足时 MODIFY 加宽（MySQL 8 没有 "MODIFY COLUMN IF ..."）。
     *
     * <p>只加宽、绝不缩窄 —— 缩窄可能静默截断既有数据。典型场景：枚举值随业务演进变长
     * （角色码从 ADMIN 扩到 RECOMMENDATION_OPERATOR），建表时的宽度就成了隐性上限。
     *
     * @return 1=本次加宽了列；0=宽度已足够或（非严格模式）升级失败
     */
    private int ensureColumnWidth(String table, String column, int minLength, String modifySql) {
        try {
            Integer current = jdbcTemplate.queryForObject(
                    "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column);
            if (current == null || current >= minLength) {
                log.debug("存量表 {}.{} 列宽 {} 已满足 >= {}，跳过", table, column, current, minLength);
                return 0;
            }
            jdbcTemplate.execute(modifySql);
            log.info("存量表自动升级完成：{}.{} 列宽 {} → {}", table, column, current, minLength);
            return 1;
        } catch (Exception e) {
            if (strict) {
                log.error("列宽升级失败（{}.{}），按 schema.auto-upgrade-strict=true 阻止启动: {}",
                        table, column, e.getMessage());
                throw new IllegalStateException("数据库列宽升级失败：" + table + "." + column
                        + "（" + e.getMessage() + "）。确需手工处理可设 SCHEMA_AUTO_UPGRADE_STRICT=false 启动。", e);
            }
            log.warn("列宽升级失败（{}:{}，可手工执行 ALTER）: {}", table, column, e.getMessage());
            return 0;
        }
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

    /**
     * 存量库补建表：与 {@link #ensureColumn} 同语义——已存在则跳过；
     * strict 模式下建表失败立即阻止启动（否则运行期才报 "table doesn't exist"，更难排查）。
     *
     * @return 1=本次建了表；0=已存在或（非严格模式）失败
     */
    private int ensureTable(String table, String createSql) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                    Integer.class, table);
            if (count != null && count > 0) {
                log.debug("存量表 {} 已存在，跳过自动建表", table);
                return 0;
            }
            jdbcTemplate.execute(createSql);
            log.info("存量库自动建表完成：{}", table);
            return 1;
        } catch (Exception e) {
            if (strict) {
                log.error("存量库自动建表失败（{}），按 schema.auto-upgrade-strict=true 阻止启动: {}",
                        table, e.getMessage());
                throw new IllegalStateException(
                        "数据库结构自动升级失败：建表 " + table + "（" + e.getMessage()
                                + "）。请检查旧库结构与数据库账号的建表权限；确需手工处理可设 "
                                + "SCHEMA_AUTO_UPGRADE_STRICT=false 启动（此时仅告警不阻断）。", e);
            }
            log.warn("存量库自动建表失败（{}，可手工执行建表）: {}", table, e.getMessage());
            return 0;
        }
    }
}
