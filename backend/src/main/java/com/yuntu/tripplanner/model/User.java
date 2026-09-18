package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体（对应 users 表）
 *
 * <p>注意：passwordHash 字段不对外序列化——返回用户信息时由 Controller 手动构造
 * {id, username, nickname}，避免密码哈希泄露。
 *
 * <p>治理字段（管理员后台与内容运营中心设计方案 §7 用户与账号治理）：
 * account_status=ACTIVE/SUSPENDED（暂停账号登录即拒绝）、post_limited（限制发帖）、
 * comment_banned（暂停评论）、violation_count（举报成立自动累计）、last_login_at。
 */
@Data
@TableName(value = "users")
public class User {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("username")
    private String username;

    @TableField("password_hash")
    private String passwordHash;

    @TableField("nickname")
    private String nickname;

    /** 角色：USER/ADMIN（阶段二社区；存量老用户为 NULL 时按 USER 处理） */
    @TableField("role")
    private String role;

    /** 账号状态：ACTIVE/SUSPENDED（§7：暂停账号后登录即拒绝） */
    @TableField("account_status")
    private String accountStatus;

    /** 限制发帖标记（§7：true 时发布新帖被拒绝） */
    @TableField("post_limited")
    private Boolean postLimited;

    /** 暂停评论标记（§7：true 时发表评论被拒绝） */
    @TableField("comment_banned")
    private Boolean commentBanned;

    /** 违规次数（举报成立自动累计，§7 列表字段） */
    @TableField("violation_count")
    private Integer violationCount;

    /** 最近登录时间（登录成功更新） */
    @TableField("last_login_at")
    private LocalDateTime lastLoginAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
