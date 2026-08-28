package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 行程请求参数
 */
@Data
public class TripRequest {
    
    @NotBlank(message = "目的地不能为空")
    @JsonProperty("destination")
    private String destination;
    
    @NotNull(message = "开始日期不能为空")
    @JsonProperty("start_date")
    private LocalDate startDate;
    
    @NotNull(message = "结束日期不能为空")
    @JsonProperty("end_date")
    private LocalDate endDate;
    
    @Min(value = 1, message = "旅行人数至少为1")
    @JsonProperty("travelers")
    private Integer travelers = 1;
    
    @Min(value = 0, message = "预算不能为负数")
    @JsonProperty("budget")
    private Double budget;
    
    @JsonProperty("preferences")
    private List<String> preferences;
    
    @JsonProperty("pace")
    private String pace = "适中";
    
    @JsonProperty("dietary_preferences")
    private List<String> dietaryPreferences;
    
    @JsonProperty("hotel_level")
    private String hotelLevel = "舒适型";
    
    @JsonProperty("special_notes")
    private String specialNotes;

    /**
     * 当前登录用户 id（后端从 JWT 注入，前端不传，防伪造；用于用户记忆与缓存隔离）
     */
    @JsonIgnore
    private String userId;

    /**
     * 用户记忆画像文本（后端生成前构建并注入，前端不传；空=无历史，不注入）
     */
    @JsonIgnore
    private String userMemory;

    @AssertTrue(message = "结束日期必须晚于或等于开始日期")
    @JsonIgnore
    public boolean isDateRangeValid() {
        return startDate == null || endDate == null || !endDate.isBefore(startDate);
    }

    @AssertTrue(message = "行程天数不能超过31天")
    @JsonIgnore
    public boolean isTripLengthValid() {
        if (startDate == null || endDate == null) {
            return true;
        }
        return ChronoUnit.DAYS.between(startDate, endDate) + 1 <= 31;
    }
}