package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 行程主数据结构
 */
@Data
public class Itinerary {
    
    @JsonProperty("trip_id")
    private String tripId;
    
    @JsonProperty("destination")
    private String destination;
    
    @JsonProperty("summary")
    private String summary;
    
    @JsonProperty("days")
    private List<DayPlan> days;
    
    @JsonProperty("estimated_budget")
    private Double estimatedBudget;
    
    @JsonProperty("budget_breakdown")
    private BudgetBreakdown budgetBreakdown;
    
    @JsonProperty("tips")
    private List<String> tips;
    
    @JsonProperty("source_notes")
    private List<String> sourceNotes;

    /**
     * 生成时刻的天气快照：保证结果页天气表与行程每日备注口径一致。
     * 随行程 JSON 一起存库，历史数据缺失时为 null（前端回退实时拉取）。
     */
    @JsonProperty("weather")
    private WeatherForecastResponse weather;

    @JsonProperty("token_usage")
    private TokenUsage tokenUsage;

    /** 行程请求快照：用户提交的预算约束（可为 null=未填），供结果页计算预算使用率（PLAN §5.3） */
    @JsonProperty("requested_budget")
    private Double requestedBudget;

    /** 行程请求快照：出行人数（默认 1） */
    @JsonProperty("travelers")
    private Integer travelers;

    /** 行程请求快照：行程天数（首尾日期差 + 1，≤31）；历史行程缺失时为 null，前端回退不展示 */
    @JsonProperty("trip_days")
    private Integer tripDays;

    /** 个性化规划摘要（PLAN §5.5：收尾时基于画像/请求/候选证据结构化构建；无画像为 null） */
    @JsonProperty("personalization_summary")
    private PersonalizationSummary personalizationSummary;

    /**
     * 被过滤/降级的候选说明（PLAN §5.6/§8.3：为什么没有推荐某些内容）。
     * 收尾时基于候选证据确定性构建：硬约束沉底（不感兴趣）或历史重复且未入选才给原因；
     * 无此类候选为 null（前端整块隐藏）。
     */
    @JsonProperty("filtered_candidates")
    private List<FilteredCandidate> filteredCandidates;
}