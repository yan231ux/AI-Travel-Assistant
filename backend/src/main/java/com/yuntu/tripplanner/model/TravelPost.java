package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户帖子（对应 travel_post 表，阶段二社区）。
 *
 * <p>状态机（PRODUCT_EVOLUTION_PLAN §7.3）：DRAFT 草稿 → PENDING_REVIEW 待审核 →
 * PUBLISHED 已发布（进公开流）；REJECTED 可改后重提；HIDDEN/DELETED 不进公开流。
 * 「未审核帖子不得进入公开推荐流」由 {@link #isPubliclyVisible()} 约束。
 */
@Data
@TableName("travel_post")
public class TravelPost {

    /** 类型：城市攻略 */
    public static final String TYPE_GUIDE = "GUIDE";
    /** 类型：景点推荐 */
    public static final String TYPE_SPOT_RECOMMENDATION = "SPOT_RECOMMENDATION";
    /** 类型：行程分享 */
    public static final String TYPE_ITINERARY = "ITINERARY";
    /** 类型：旅行随笔 */
    public static final String TYPE_NOTE = "NOTE";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_HIDDEN = "HIDDEN";
    public static final String STATUS_DELETED = "DELETED";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId;

    @TableField("title")
    private String title;

    @TableField("summary")
    private String summary;

    @TableField("content")
    private String content;

    @TableField("cover_image")
    private String coverImage;

    @TableField("city")
    private String city;

    @TableField("travel_days")
    private Integer travelDays;

    @TableField("budget")
    private Double budget;

    @TableField("pace")
    private String pace;

    @TableField("post_type")
    private String postType;

    @TableField("status")
    private String status;

    @TableField("like_count")
    private Integer likeCount;

    @TableField("favorite_count")
    private Integer favoriteCount;

    @TableField("view_count")
    private Integer viewCount;

    @TableField("comment_count")
    private Integer commentCount;

    @TableField("reject_reason")
    private String rejectReason;

    /** 内容质量分 0~100（阶段四任务 5：确定性规则，提交审核时计算落库） */
    @TableField("quality_score")
    private Integer qualityScore;

    /** 低质标记（阶段四任务 8：内容过短/缺结构时置 1，管理员审核时展示） */
    @TableField("low_quality")
    private Integer lowQuality;

    /**
     * 待审核的编辑版本ID（审查报告 P1-1：公开版本 / 编辑版本分离）。
     * 非空 = 作者对已发布内容提交了修改稿，本行仍是线上公开版本，修改稿在 travel_post_revision。
     */
    @TableField("pending_revision_id")
    private Long pendingRevisionId;

    @TableField("published_at")
    private LocalDateTime publishedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 仅 PUBLISHED 对公众可见（草稿/待审/拒绝/隐藏/删除一律进不了公开流） */
    public boolean isPubliclyVisible() {
        return STATUS_PUBLISHED.equals(status);
    }
}
