package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体（对应 users 表）
 *
 * <p>注意：passwordHash 字段不对外序列化——返回用户信息时由 Controller 手动构造
 * {id, username, nickname}，避免密码哈希泄露。
 */
@Data
@TableName(value = "users")
public class User {

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

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
