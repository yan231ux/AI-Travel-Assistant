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
    
    @JsonProperty("meal_type")
    private String mealType;
    
    @JsonProperty("estimated_cost")
    private Double estimatedCost;
    
    @JsonProperty("notes")
    private String notes;

    /** 数据来源：高德POI / 本地攻略 / LLM建议（需核实），由校验层填充 */
    @JsonProperty("source")
    private String source;
}