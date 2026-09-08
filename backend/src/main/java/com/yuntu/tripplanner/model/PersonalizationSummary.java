package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 个性化规划摘要（PLAN §5.5：结果页顶部确定性说明，不交给 LLM 自由编写）。
 *
 * <p>由生成收尾 {@code TripGenerationFinalizer} 基于画像/请求/候选证据结构化构建：
 * 匹配了哪些偏好、应用了哪些约束（节奏/饮食/预算）、是否减少重复景点、证据等级。
 * 前端按结构化字段渲染「本次规划结合了：…」；无画像时不构建（前端整块隐藏）。
 */
@Data
public class PersonalizationSummary {

    /** 命中的用户偏好（正权重，如 历史文化/轻松） */
    @JsonProperty("matched_preferences")
    private List<String> matchedPreferences = new ArrayList<>();

    /** 应用的行程约束（来自请求参数，如 少辣 / 预算 3200 元 / 节奏 轻松） */
    @JsonProperty("applied_constraints")
    private List<String> appliedConstraints = new ArrayList<>();

    /** 新颖性说明（候选证据中存在历史已体验项时生成，否则空） */
    @JsonProperty("novelty_note")
    private String noveltyNote;

    /** 证据等级：PROFILE_AND_BEHAVIOR / QUESTIONNAIRE / HISTORY_INFER_ONLY / NONE */
    @JsonProperty("evidence_level")
    private String evidenceLevel;
}
