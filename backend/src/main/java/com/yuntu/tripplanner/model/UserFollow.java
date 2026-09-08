package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户关注关系（对应 user_follow 表，阶段四任务 2）。
 *
 * <p>方向语义：{@code userId} 关注 {@code followUserId}；(user_id, follow_user_id) 唯一，
 * 重复关注幂等（DuplicateKey 忽略）；取消关注即删除行。关注关系只用于「用户旅行主页/关注流」
 * 这类社交展示，不参与画像权重（与景点/帖子行为分工明确）。
 */
@Data
@TableName("user_follow")
public class UserFollow {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关注者用户 ID */
    @TableField("user_id")
    private String userId;

    /** 被关注者用户 ID */
    @TableField("follow_user_id")
    private String followUserId;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
