package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 攻略版本（对应 city_guide_revision 表：每次保存追加一条不可变版本）。
 *
 * <p>append-only：编辑一律新建版本（revision_no 递增），不原地覆盖旧版本；
 * content_hash（SHA-256）用于：1) 幂等导入判重（source_file+content_hash 防重复导入）；
 * 2) 无变化保存跳过（同 guide 内 (guide_id, content_hash) 唯一）。
 * 成为 published_revision 的版本 status 置 PUBLISHED（审计与回滚依据）。
 */
@Data
@TableName("city_guide_revision")
public class CityGuideRevision {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_REJECTED = "REJECTED";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("guide_id")
    private Long guideId;

    @TableField("revision_no")
    private Integer revisionNo;

    @TableField("content_hash")
    private String contentHash;

    @TableField("content_markdown")
    private String contentMarkdown;

    @TableField("change_summary")
    private String changeSummary;

    @TableField("editor_id")
    private String editorId;

    @TableField("status")
    private String status;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
