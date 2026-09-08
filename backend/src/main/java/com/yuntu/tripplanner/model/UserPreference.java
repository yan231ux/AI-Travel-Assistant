package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户偏好明细（对应 user_preference 表）。
 *
 * <p>个性化阶段一引入的带权重/置信度/来源的结构化偏好，是阶段二「行为反馈增量更新」
 * （收藏 +0.15 / 不感兴趣 -0.30 等）与阶段三「个性化打分」的数据基座。
 * (user_id, category, tag) 唯一：同一偏好重复出现时按规则做增量更新而非新增行。
 */
@Data
@TableName("user_preference")
public class UserPreference {

    /** 偏好来源：问卷显式选择（可信度最高） */
    public static final String SOURCE_QUESTIONNAIRE = "QUESTIONNAIRE";
    /** 偏好来源：历史行程推断 */
    public static final String SOURCE_HISTORY_INFER = "HISTORY_INFER";
    /** 偏好来源：用户行为反馈（阶段二启用：收藏/评分） */
    public static final String SOURCE_FEEDBACK = "FEEDBACK";

    /** 偏好域：旅行风格 */
    public static final String CATEGORY_TRAVEL_STYLE = "travel_style";
    /** 偏好域：节奏 */
    public static final String CATEGORY_PACE = "pace";
    /** 偏好域：住宿档次 */
    public static final String CATEGORY_HOTEL = "hotel";
    /** 偏好域：口味 */
    public static final String CATEGORY_FOOD = "food";
    /** 偏好域：饮食约束 */
    public static final String CATEGORY_DIETARY = "dietary";
    /** 偏好域：行为约束 */
    public static final String CATEGORY_BEHAVIOR = "behavior";
    /** 偏好域：城市（阶段三：帖子/城市行为进入画像后，命中城市的帖子获得加权） */
    public static final String CATEGORY_CITY = "city";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId;

    /** 偏好域：travel_style/pace/hotel/food/dietary/behavior */
    @TableField("category")
    private String category;

    /** 偏好标签（自然风景/轻松/舒适型/少辣/不早起/…） */
    @TableField("tag")
    private String tag;

    /** 偏好权重 0~1（阶段三个性化打分直接使用） */
    @TableField("weight")
    private Double weight;

    /** 置信度 0~1（按来源：主动选择 0.95 > 明确反馈 > 重复行为 > 历史推断 0.5 > LLM 推断） */
    @TableField("confidence")
    private Double confidence;

    /** 来源：QUESTIONNAIRE / HISTORY_INFER / FEEDBACK */
    @TableField("source")
    private String source;

    /** 最近一次被观察到的时间（时间衰减用） */
    @TableField("last_observed_at")
    private LocalDateTime lastObservedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
