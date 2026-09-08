package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 推荐日志（对应 recommendation_log 表，个性化阶段四：可解释/可追溯）。
 *
 * <p>每次行程生成收尾时，为最终行程里的每个景点/餐厅写一条日志，记录它被推荐时
 * 的画像依据（偏好匹配分/新颖性分/最终分）与人读理由（explanation），例如：
 * 「匹配你的偏好：历史文化」。hit_preference 是否命中正偏好标签 = 偏好命中率的分子，
 * 供阶段四实验指标（PLAN 11.2）统计。
 */
@Data
@TableName("recommendation_log")
public class RecommendationLog {

    /** 对象类型：景点 */
    public static final String ITEM_TYPE_SPOT = "SPOT";

    /** 对象类型：餐厅 */
    public static final String ITEM_TYPE_RESTAURANT = "RESTAURANT";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId;

    @TableField("trip_id")
    private String tripId;

    /** 推荐对象名称（景点名） */
    @TableField("item_name")
    private String itemName;

    /** SPOT */
    @TableField("item_type")
    private String itemType;

    /** 偏好匹配分（命中画像权重的加权累加，0~1 量纲的放大值） */
    @TableField("preference_score")
    private Double preferenceScore;

    /** 新颖性分（候选曾出现于历史行程则降；默认 1） */
    @TableField("novelty_score")
    private Double noveltyScore;

    /** 距离惩罚（预留，候选层无成组锚点暂不计算） */
    @TableField("distance_penalty")
    private Double distancePenalty;

    /** 最终分（口径见 PersonalizedRankingService） */
    @TableField("final_score")
    private Double finalScore;

    /** 是否命中正偏好标签（1=命中，0=未命中；偏好命中率分子） */
    @TableField("hit_preference")
    private Integer hitPreference;

    /** 推荐理由（人读："匹配你的偏好：历史文化"） */
    @TableField("explanation")
    private String explanation;

    /** 打分时的画像版本（与结果缓存 key 同源：行为反馈使画像版本递增后，旧日志可区分） */
    @TableField("profile_version")
    private Integer profileVersion;

    /** 排序算法版本（与候选证据 ranking_version 同源，防规则变更后复用旧日志） */
    @TableField("ranking_version")
    private Integer rankingVersion;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
