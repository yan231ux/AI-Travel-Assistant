package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 帖子编辑版本（对应 travel_post_revision，审查报告 P1-1）。
 *
 * <p>解决的问题：{@code travel_post} 单表只有一份正文，已发布帖子被作者编辑时
 * 只能"整篇下架重新审核"——读者会看到帖子凭空消失，作者也失去继续打磨的机会。
 *
 * <p>版本化后的语义（公开版本 / 编辑版本分离）：
 * <ul>
 *   <li>{@code travel_post} 始终保存并服务<b>线上公开版本</b>；</li>
 *   <li>作者编辑已发布帖子 → 生成一条 {@code PENDING_REVIEW} 版本，线上不受影响；</li>
 *   <li>审核通过 → 版本内容原子回写主表（{@code published_at} 保持不变，老帖不会"变新"）；</li>
 *   <li>审核拒绝 → 版本置 {@code REJECTED} 并记录原因，主表仍是原公开版本。</li>
 * </ul>
 *
 * <p>每篇帖子同一时刻至多一个待审版本，由 {@code travel_post.pending_revision_id} 指明。
 * 版本被更新（作者改第二次）/ 被新版本取代时，旧版本置 {@code SUPERSEDED}。
 */
@Data
@TableName("travel_post_revision")
public class TravelPostRevision {

    /** 待审核（当前挂主表的待审版本） */
    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    /** 审核通过并已切换为线上版本 */
    public static final String STATUS_APPROVED = "APPROVED";
    /** 审核拒绝（作者需重新编辑） */
    public static final String STATUS_REJECTED = "REJECTED";
    /** 被更新的版本取代（写历史用，永不激活） */
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("post_id")
    private Long postId;

    /** 版本号，同帖内自增（1 起），仅用于展示与追溯 */
    @TableField("revision_no")
    private Integer revisionNo;

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

    /**
     * 关联景点快照（JSON 数组：[{spot_id,poi_id,spot_name}]）。
     * 用 String 存 + 服务层序列化：避免给单列引入 MyBatis TypeHandler 配置，
     * 也让"审核通过才切换"这一步能一次性拿到完整快照。
     */
    @TableField("spots_json")
    private String spotsJson;

    @TableField("status")
    private String status;

    @TableField("reject_reason")
    private String rejectReason;

    @TableField("reviewed_by")
    private String reviewedBy;

    @TableField("reviewed_at")
    private LocalDateTime reviewedAt;

    @TableField("editor_id")
    private String editorId;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    public boolean isPending() {
        return STATUS_PENDING_REVIEW.equals(status);
    }
}
