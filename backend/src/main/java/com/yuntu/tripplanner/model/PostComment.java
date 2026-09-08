package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 帖子评论（对应 post_comment 表，阶段二社区）。
 *
 * <p>软删：删除=status 置 DELETED（作者删自己的、管理员删违规），已删评论从列表隐藏；
 * 父评论删除后其子回复保留但父内容显示"评论已删除"。
 */
@Data
@TableName("post_comment")
public class PostComment {

    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_DELETED = "DELETED";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("post_id")
    private Long postId;

    @TableField("user_id")
    private String userId;

    @TableField("parent_id")
    private Long parentId;

    @TableField("content")
    private String content;

    @TableField("status")
    private String status;

    @TableField("like_count")
    private Integer likeCount;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
