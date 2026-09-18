package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 索引任务（对应 rag_index_task 表：攻略发布后按 revision 派生向量索引的任务记录）。
 *
 * <p>语义（管理员后台与内容运营中心设计方案 §9.2）：数据库已发布攻略版本 = 唯一线上主数据、
 * 向量是派生数据。发布/手动重建时登记 {@code PENDING} 任务，由 GuideIndexingService 消费执行：
 * 切分向量化成功 → {@code READY}（原子切换检索源，并把 published_revision_id 推进到该版本）；
 * 失败 → {@code FAILED}（线上保持旧版本继续服务，保留失败原因供手动重试）。
 *
 * <p>任务按 revision_id 绑定。执行前会校验该 revision 仍是攻略当前有效版本（P0-2）：
 * 若攻略已被更新的版本发布/回滚、或已下线/归档，则任务标记 {@code SUPERSEDED} 跳过，
 * 杜绝旧任务晚完成时把过期内容写回检索源。{@code DEFERRED} 为过渡期历史状态
 * （当时检索源仍为 classpath Markdown），已不再写入，常量保留仅用于读取/迁移存量数据。
 */
@Data
@TableName("rag_index_task")
public class RagIndexTask {

    public static final String STATUS_PENDING = "PENDING";
    /** 过渡期历史状态（已废弃不再写入，仅存量读取） */
    public static final String STATUS_DEFERRED = "DEFERRED";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";
    /** 任务登记后该 revision 已不再是攻略当前有效版本（被更新发布/回滚/下线取代），跳过不执行 */
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("guide_id")
    private Long guideId;

    @TableField("revision_id")
    private Long revisionId;

    @TableField("status")
    private String status;

    @TableField("error_message")
    private String errorMessage;

    @TableField("triggered_by")
    private String triggeredBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;
}
