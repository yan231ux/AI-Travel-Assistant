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
}