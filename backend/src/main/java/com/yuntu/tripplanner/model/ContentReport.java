package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 内容举报（对应 content_report 表，阶段二社区）。
 *
 * <p>对象：POST/COMMENT。(reporter_id, target_type, target_id) 唯一 —— 同一用户对同一对象
 * 只保留一条举报，重复举报幂等返回已存在（不刷屏审核队列）。
 */
@Data
@TableName("content_report")
public class ContentReport {

    public static final String TARGET_POST = "POST";
    public static final String TARGET_COMMENT = "COMMENT";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_DISMISSED = "DISMISSED";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("reporter_id")
    private String reporterId;

    @TableField("target_type")
    private String targetType;

    @TableField("target_id")
    private Long targetId;

    @TableField("reason")
    private String reason;

    @TableField("detail")
    private String detail;

    @TableField("status")
    private String status;

    @TableField("handled_by")
    private String handledBy;

    @TableField("handled_at")
    private LocalDateTime handledAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
