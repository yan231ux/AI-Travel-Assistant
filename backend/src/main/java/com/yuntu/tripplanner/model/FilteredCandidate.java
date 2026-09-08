package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 被过滤/降级的候选（PLAN §5.6 / §8.3「为什么没有推荐某些内容」）。
 *
 * <p>由生成收尾 {@code TripGenerationFinalizer} 基于候选证据（candidate_evidence）确定性构建：
 * 候选阶段被硬约束沉底（hard_avoid=1，用户近期不感兴趣）或历史已体验（visited=1）的景点/餐厅，
 * 若最终未入选行程，则以结构化条目说明「为什么没有推荐」，供结果页逐条展示。
 * reason 全部由代码生成，不交给 LLM 自由编写；无依据的普通落选候选不展示（避免噪音）。
 */
@Data
public class FilteredCandidate {

    /** 候选名称（景点/餐厅） */
    @JsonProperty("name")
    private String name;

    /** 候选桶：景点/餐厅（复用 CandidateEvidence 桶语义） */
    @JsonProperty("bucket")
    private String bucket;

    /** 命中的回避标签（hard_avoid 触发时非空；历史重复场景为空） */
    @JsonProperty("tag")
    private String tag;

    /** 未推荐的确定性原因（中文，代码生成） */
    @JsonProperty("reason")
    private String reason;

    /** 证据来源：USER_BEHAVIOR（不感兴趣反馈）/ HISTORY_TRIP（历史行程） */
    @JsonProperty("evidence")
    private String evidence;

    /** 约束级别：HARD（硬约束沉底）/ SOFT（软降权，历史重复） */
    @JsonProperty("severity")
    private String severity;
}
