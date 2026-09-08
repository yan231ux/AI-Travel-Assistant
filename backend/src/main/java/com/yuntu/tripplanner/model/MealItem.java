package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 餐饮项目
 */
@Data
public class MealItem {
    
    @JsonProperty("name")
    private String name;

    /**
     * 开始时间 HH:mm（时间轴交错排序用，OPTIMIZATION_TODO P0①：LLM 生成时按真实餐点给出，
     * 如午餐 11:30-13:00 / 晚餐 17:30-19:30，与相邻景点衔接；缺省为 null → 前端归"其他安排"不编造）
     */
    @JsonProperty("start_time")
    private String startTime;

    @JsonProperty("meal_type")
    private String mealType;
    
    @JsonProperty("estimated_cost")
    private Double estimatedCost;
    
    @JsonProperty("notes")
    private String notes;

    /** 数据来源：高德POI / 本地攻略 / LLM建议（需核实），由校验层填充 */
    @JsonProperty("source")
    private String source;

    /** 个性化推荐理由（口径统一轮：收尾按 food 域标签回填，如"匹配你的偏好：火锅"；未命中为空） */
    @JsonProperty("personal_note")
    private String personalNote;
}