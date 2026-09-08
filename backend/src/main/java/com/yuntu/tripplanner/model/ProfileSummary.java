package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户旅行摘要（GET /user/profile/summary 返回体，Q5 修复：实时聚合、不作废快照）。
 *
 * <p>背景：user_profile 表中的 trip_count / visited_cities 是画像创建时的历史快照，
 * 新增行程后不会自动更新（曾导致 ProfileView 显示"5 次行程/4 城"而实际 11 次/8 城）。
 * 本接口改为服务端实时基于未删除 trip_record 计算，首页画像摘要与 ProfileView 一律用它，
 * 过时快照不再作为展示事实源（PRODUCT_EVOLUTION_PLAN §18.2）。
 */
@Data
public class ProfileSummary {

    /** 历史行程总数（实时） */
    @JsonProperty("trip_count")
    private Integer tripCount;

    /** 去重目的地列表（实时，按最近一次出现排序） */
    @JsonProperty("visited_cities")
    private List<String> visitedCities;

    /** 最近一次行程生成时间（无行程为 null） */
    @JsonProperty("latest_trip_at")
    private LocalDateTime latestTripAt;

    /** 当前画像版本（反馈闭环/缓存失效参考） */
    @JsonProperty("profile_version")
    private Integer profileVersion;

    /* ============ 画像字段（来自 user_profile 主档，供首页画像摘要展示） ============ */

    /** 昵称/用户名（调用方注入，此处可为空） */
    @JsonProperty("nickname")
    private String nickname;

    /** 旅行风格标签（逗号分隔字符串，直接展示） */
    @JsonProperty("travel_styles")
    private String travelStyles;

    @JsonProperty("pace_preference")
    private String pacePreference;

    @JsonProperty("hotel_preference")
    private String hotelPreference;

    @JsonProperty("food_preferences")
    private String foodPreferences;

    @JsonProperty("budget_preference")
    private Double budgetPreference;
}
