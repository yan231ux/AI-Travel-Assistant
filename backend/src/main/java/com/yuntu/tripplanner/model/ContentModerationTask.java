package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 内容审核任务（设计方案 §4.4，阶段三）。
 *
 * <p>审核流水线：内容创建/编辑 → 规则检查（确定、可解释）→ AI 结构化初筛 →
 * 阈值聚合 → 自动放行 / 人工复核 → 人工最终决策 → 审计。
 *
 * <p>状态机：PENDING → RUNNING → PASSED（规则+AI 均低风险，自动放行）/
 * REVIEW（需人工复核）/ FAILED（AI 失败进入待审，不能自动放行高风险内容）。
 *
 * <p>防"旧结果覆盖新内容"（§4.4）：任务必须绑 content_hash（攻略再绑 revision_id）；
 * 同目标同 hash 已有未终局任务时不重复建任务。
 *
 * <p>AI 只是建议：管理员可覆盖 AI 结论（decision 列留痕），覆盖必须填原因。
 */
@Data
@TableName("content_moderation_task")
public class ContentModerationTask {

    /* ---- 状态 ---- */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_PASSED = "PASSED";
    public static final String STATUS_REVIEW = "REVIEW";
    public static final String STATUS_FAILED = "FAILED";

    /* ---- 风险等级 ---- */
    public static final String RISK_LOW = "LOW";
    public static final String RISK_MEDIUM = "MEDIUM";
    public static final String RISK_HIGH = "HIGH";
    public static final String RISK_CRITICAL = "CRITICAL";

    /* ---- 对象类型 ---- */
    public static final String TARGET_POST = "POST";
    public static final String TARGET_COMMENT = "COMMENT";
    public static final String TARGET_GUIDE = "GUIDE";
    public static final String TARGET_SPOT = "SPOT";

    /* ---- 管理员最终决策（覆盖 AI 结论） ---- */
    public static final String DECISION_APPROVE = "APPROVE";
    public static final String DECISION_REJECT = "REJECT";

    @TableId(type = IdType.AUTO)
    @JsonProperty("id")
    private Long id;

    /** POST / COMMENT / GUIDE / SPOT */
    @JsonProperty("target_type")
    private String targetType;

    @JsonProperty("target_id")
    private String targetId;

    /** 攻略必绑版本（防旧 AI 结果覆盖新版本内容）；其他类型为空 */
    @JsonProperty("revision_id")
    private Long revisionId;

    /** 内容指纹 SHA-256(type + 标题 + 正文)，去重与版本防错绑 */
    @JsonProperty("content_hash")
    private String contentHash;

    /** 任务类型：SAFETY（v1 仅安全初筛，预留 QUALITY/FACT_CHECK/DUPLICATE） */
    @JsonProperty("task_type")
    private String taskType;

    @JsonProperty("status")
    private String status;

    @JsonProperty("risk_level")
    private String riskLevel;

    @JsonProperty("risk_score")
    private Double riskScore;

    @JsonProperty("model_name")
    private String modelName;

    @JsonProperty("prompt_version")
    private String promptVersion;

    /** AI 结构化输出原文（decision/risk/categories/fact_risks/suggestion） */
    @JsonProperty("result_json")
    private String resultJson;

    /** 规则命中 JSON：[{code,name,matched,severity}] */
    @JsonProperty("matched_rules_json")
    private String matchedRulesJson;

    /** 规则命中条数（列表页直读，免解析 JSON） */
    @JsonProperty("rule_hit_count")
    private Integer ruleHitCount;

    /** 内容快照（详情页展示原文，防源内容被删后无法追溯） */
    @JsonProperty("content_title")
    private String contentTitle;

    @JsonProperty("content_text")
    private String contentText;

    @JsonProperty("city")
    private String city;

    @JsonProperty("error_message")
    private String errorMessage;

    @JsonProperty("retry_count")
    private Integer retryCount;

    /** 管理员最终决策：APPROVE/REJECT（覆盖 AI 结论必须填原因） */
    @JsonProperty("decision")
    private String decision;

    @JsonProperty("decision_by")
    private String decisionBy;

    @JsonProperty("decision_reason")
    private String decisionReason;

    @JsonProperty("decided_at")
    private LocalDateTime decidedAt;

    @JsonProperty("created_by")
    private String createdBy;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("finished_at")
    private LocalDateTime finishedAt;
}
