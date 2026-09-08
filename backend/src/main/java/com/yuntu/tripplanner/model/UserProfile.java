package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户画像主档（对应 user_profile 表，每用户一行）。
 *
 * <p>个性化阶段一的结构化画像：由「偏好问卷显式选择」与「历史行程推断」合并维护，
 * 直接面向展示（前端画像页/记忆卡片）与生成注入（prompt 文本由其 + user_preference 生成）。
 * 列表型字段用逗号分隔字符串存储，服务层负责切分/拼接，避免引入 JSON typeHandler。
 */
@Data
@TableName("user_profile")
public class UserProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId;

    /** 旅行风格（逗号分隔：自然风景/历史文化/美食探索/城市漫游/拍照打卡/户外运动…） */
    @TableField("travel_styles")
    private String travelStyles;

    /** 节奏偏好（轻松/适中/紧凑） */
    @TableField("pace_preference")
    private String pacePreference;

    /** 住宿偏好（经济型/舒适型/高档型） */
    @TableField("hotel_preference")
    private String hotelPreference;

    /** 预算参考（历史行程均值，元/次；无历史为 null） */
    @TableField("budget_preference")
    private Double budgetPreference;

    /** 口味偏好（逗号分隔：火锅/海鲜/本帮菜/…） */
    @TableField("food_preferences")
    private String foodPreferences;

    /** 饮食约束（逗号分隔：少辣/不吃香菜/不吃葱/…） */
    @TableField("dietary_restrictions")
    private String dietaryRestrictions;

    /** 行为约束（逗号分隔：不早起/少换酒店/少走路/优先公共交通/避开人多的景点/适合老人或儿童） */
    @TableField("behavior_notes")
    private String behaviorNotes;

    /** 去过的城市（逗号分隔，来自历史行程；重复城市新玩法/新颖性控制用） */
    @TableField("visited_cities")
    private String visitedCities;

    /** 画像统计时的历史行程数 */
    @TableField("trip_count")
    private Integer tripCount;

    /** 是否填写过偏好问卷 */
    @TableField("filled_from_questionnaire")
    private Integer filledFromQuestionnaire;

    /** 画像版本号（每次显式修改 +1） */
    @TableField("profile_version")
    private Integer profileVersion;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
