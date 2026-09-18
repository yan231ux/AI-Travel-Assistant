package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 采集方案存档（对应 agent_plan_archive 表，ReAct 优化批次 3：采集结果存档复用）。
 *
 * <p>解决的冲突：全自主模式（llm.agent-mode=autonomous）下，模型每轮自由决定调哪些工具，
 * 同一输入两次生成可能拿到**不同的候选池**，于是"个性化结果可复现、变化可归因"这个卖点
 * 被引入了第二个自变量（工具选择）而说不清。解法是"<b>只自主一次</b>"：
 *
 * <ol>
 *   <li>某用户 + 某目的地 + 某偏好首次生成 → 走完整自主链路，得到最终生效的工具计划；</li>
 *   <li>把该计划（+ 关键观察摘要）按 {@code plan_key} 落库存档；</li>
 *   <li>同 key 再次生成 → <b>直接复用存档计划</b>（不再问模型），候选池因此稳定，
 *       个性化排序的输入被钉死，结果可复现、变化可归因。</li>
 * </ol>
 *
 * <p>副产品：复用命中时省掉一次 think LLM 调用（自主模式 think 是大头），
 * 这是"压 token"最实在的一刀——比在 prompt 里抠字有效得多。
 *
 * <p>与内存 {@code TravelAgent.planCache}（TTL 1h）的分工：内存缓存快但进程重启即失效、
 * 也不可解释；本表持久、可查询、可展示 reuse_count，是"可解释的持久化复用"。
 */
@Data
@TableName("agent_plan_archive")
public class AgentPlanArchive {

    /** 方案来源：自主模式 + 原生 tool_calls 决策 */
    public static final String SOURCE_AUTONOMOUS_NATIVE = "autonomous-native";
    /** 方案来源：自主模式 + 文本 JSON 决策 */
    public static final String SOURCE_AUTONOMOUS_TEXT = "autonomous-text";
    /** 方案来源：legacy 模式文本 JSON 决策（保留口径，当前不落库） */
    public static final String SOURCE_LEGACY_TEXT = "legacy-text";
    /**
     * 方案来源：规则兜底（LLM 计划解析失败时的 buildDefaultPlan）。
     * <b>此来源绝不落库</b>——否则一次 LLM 抖动命中的兜底方案会被复用 7 天，
     * 把所有人钉在"默认采集全工具"上，得不偿失。
     */
    public static final String SOURCE_RULE = "rule";

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 复用键（唯一）：{@code userId|destination|preferences|pace|hotelLevel|dietary|specialNotes}，
     * 与 {@code TravelAgent#buildPlanCacheKey} 同源 —— 只取影响"调哪些工具"的参数，
     * 排除日期/人数/预算以扩大命中面（这些参数不影响工具选择）。
     */
    @TableField("plan_key")
    private String planKey;

    /** 用户 ID（从 plan_key 拆出，便于按用户排查/清理；匿名用户为 "null" 字面量） */
    @TableField("user_id")
    private String userId;

    /** 目的地（从 plan_key 拆出，便于按城市排查） */
    @TableField("destination")
    private String destination;

    /** 方案快照 JSON：{"planDescription":...,"toolCalls":[{"tool":...,"query":...}]} */
    @TableField("plan_json")
    private String planJson;

    /** 计划说明（人读，进 trace 展示"沿用上次成功的采集方案"时一并带上） */
    @TableField("plan_desc")
    private String planDesc;

    /** 来源：autonomous-native / autonomous-text / legacy-text */
    @TableField("source")
    private String source;

    /** 工具调用数量（便于运营/答辩一眼看出方案规模） */
    @TableField("tool_count")
    private Integer toolCount;

    /**
     * 关键观察摘要：本次采集各数据源实际拿到的规模（搜索条数/POI 类别/天气/攻略），
     * 用于"复用是否仍然成立"的人工判断，也是答辩里"候选池稳定"的证据。
     */
    @TableField("observation_summary")
    private String observationSummary;

    /** 被复用次数（每次命中 +1，可解释性：这个方案被沿用了多少次） */
    @TableField("reuse_count")
    private Integer reuseCount;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
