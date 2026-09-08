package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A/B 实验用户分桶记录（对应 ab_assignment 表，阶段四任务 6）。
 *
 * <p>同一用户在同一实验只落一个桶（(user_id, exp_name) 唯一键 + 幂等写入），
 * 保证「分桶后不变」的粘性：实验期间用户反复访问都走同一变体，
 * 曝光日志才能按变体干净地累计对照结果。
 * 不参与流量的用户不落行（避免膨胀；其行为不进入任何实验口径）。
 */
@Data
@TableName("ab_assignment")
public class AbAssignment {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId;

    @TableField("exp_name")
    private String expName;

    /** CONTROL / TREATMENT */
    @TableField("variant")
    private String variant;

    @TableField(value = "assigned_at", fill = FieldFill.INSERT)
    private LocalDateTime assignedAt;
}
