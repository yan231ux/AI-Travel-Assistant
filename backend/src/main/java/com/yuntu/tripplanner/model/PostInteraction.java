package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 帖子互动（对应 post_interaction 表，阶段二社区）。
 *
 * <p>动作：LIKE/FAVORITE/DISLIKE。(user_id, post_id, action_type) 唯一 → 重复点击幂等，
 * 不会重复计数（PRODUCT_EVOLUTION_PLAN §9.5：重复点赞不能重复增加计数）。
 */
@Data
@TableName("post_interaction")
public class PostInteraction {

    public static final String ACTION_LIKE = "LIKE";
    public static final String ACTION_FAVORITE = "FAVORITE";
    public static final String ACTION_DISLIKE = "DISLIKE";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId;

    @TableField("post_id")
    private Long postId;

    @TableField("action_type")
    private String actionType;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
