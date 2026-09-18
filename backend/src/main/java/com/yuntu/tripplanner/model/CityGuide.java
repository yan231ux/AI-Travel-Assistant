package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 攻略主档（对应 city_guide 表，管理后台内容运营：管理员后台与内容运营中心设计方案 §4/§9）。
 *
 * <p>唯一线上主数据 = 「数据库中已发布的攻略版本」：{@link #publishedRevisionId} 指向当前线上
 * 实际生效（RAG 已索引/即将恢复）的版本；{@link #currentRevisionId} 指向最新版本——审核通过
 * 发布时先置 PUBLISHED 并登记索引任务，待 RAG 索引成功后 {@code published_revision_id} 才推进
 * 到 current（两段式激活），失败则保持旧版本继续服务（见 GuideIndexingService）。
 * RAG 分片/向量是已发布版本的派生数据；classpath Markdown 仅作为初始化导入与备份载体。
 *
 * <p>状态机与帖子同构：DRAFT → PENDING_REVIEW → PUBLISHED；REJECTED 可改后重提；
 * HIDDEN 下线（暂不进任何公开消费，保留版本可恢复）；ARCHIVED 归档（长期留存，
 * 不进运营列表默认视图与 RAG 检索源，可取消归档回草稿继续维护）。
 */
@Data
@TableName("city_guide")
public class CityGuide {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_HIDDEN = "HIDDEN";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    /** 来源：CURATED=管理员精选 / COMMUNITY=用户精选 / IMPORTED=外部导入 / SYSTEM=系统初始化 Markdown */
    public static final String SOURCE_CURATED = "CURATED";
    public static final String SOURCE_COMMUNITY = "COMMUNITY";
    public static final String SOURCE_IMPORTED = "IMPORTED";
    public static final String SOURCE_SYSTEM = "SYSTEM";

    /** RAG 索引状态：NOT_INDEXED 未索引 / READY 已索引（执行器成功原子切换检索源）/
     *  FAILED 索引失败（旧版本继续服务，可手动重试）。DEFERRED 为过渡期历史口径，
     *  仅启动迁移读取存量（见 GuideIndexingService.migrateLegacyDeferred），不再新写入。 */
    public static final String RAG_NOT_INDEXED = "NOT_INDEXED";
    public static final String RAG_DEFERRED = "DEFERRED";
    public static final String RAG_READY = "READY";
    public static final String RAG_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("city")
    private String city;

    @TableField("title")
    private String title;

    @TableField("summary")
    private String summary;

    /** 当前编辑版本正文（冗余镜像 current_revision 的正文，列表/预览免 join；同一事务内写入） */
    @TableField("content_markdown")
    private String contentMarkdown;

    @TableField("cover_image")
    private String coverImage;

    @TableField("source_type")
    private String sourceType;

    @TableField("source_name")
    private String sourceName;

    /** 初始导入来源文件名（如 beijing_guide.md；只记录溯源，不代表线上继续读该文件） */
    @TableField("source_file")
    private String sourceFile;

    @TableField("author")
    private String author;

    @TableField("status")
    private String status;

    @TableField("quality_score")
    private Integer qualityScore;

    @TableField("reject_reason")
    private String rejectReason;

    @TableField("current_revision_id")
    private Long currentRevisionId;

    @TableField("published_revision_id")
    private Long publishedRevisionId;

    @TableField("rag_status")
    private String ragStatus;

    @TableField("rag_indexed_revision")
    private Long ragIndexedRevision;

    @TableField("version")
    private Integer version;

    @TableField("submitted_by")
    private String submittedBy;

    @TableField("reviewed_by")
    private String reviewedBy;

    @TableField("published_at")
    private LocalDateTime publishedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 已发布版本是否可公开消费（PUBLISHED 且存在已发布版本） */
    public boolean isPubliclyServing() {
        return STATUS_PUBLISHED.equals(status) && publishedRevisionId != null;
    }
}
