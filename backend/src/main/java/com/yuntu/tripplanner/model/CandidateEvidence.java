package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 候选证据（对应 candidate_evidence 表，个性化口径统一轮：PLAN §2.2 问题一；
 * P0② OPTIMIZATION_TODO：增加 poi_id/经纬度，最终入选判定升级多级关联）。
 *
 * <p>记录候选阶段（排序前/后）每个景点的完整依据：原始顺序、偏好匹配分、新颖性、
 * 命中标签、回避标签、是否硬约束沉底、最终是否入选 —— 与 {@link RecommendationLog}
 * （只记最终入选者的依据）互补，回答"为什么这样排 / 为什么某候选没被推荐"。
 * 打分口径统一见 {@code PersonalizedScoreCalculator}，避免排序与日志两套算法漂移。
 * poi_id/经纬度 由候选收集（高德 POI 检索返回）写入；finalizer 判定 selected 时按
 * poi_id 精确 → 名称匹配 → 经纬度近似 多级关联，校验层替换/别名场景不靠名称硬匹配。
 */
@Data
@TableName("candidate_evidence")
public class CandidateEvidence {

    /** 候选桶：景点（poiResults map 键同义） */
    public static final String BUCKET_SPOT = "景点";
    /** 候选桶：餐厅 */
    public static final String BUCKET_RESTAURANT = "餐厅";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId;

    @TableField("trip_id")
    private String tripId;

    /** 候选桶：景点/餐厅 */
    @TableField("bucket")
    private String bucket;

    /** 候选名称 */
    @TableField("item_name")
    private String itemName;

    /** 高德 POI id（OPTIMIZATION_TODO P0②：候选证据关联优先键，替换/别名场景不靠名称硬匹配） */
    @TableField("poi_id")
    private String poiId;

    /** 候选经纬度（高德坐标；经纬度近似匹配兜底用，可空） */
    @TableField("longitude")
    private Double longitude;

    @TableField("latitude")
    private Double latitude;

    /** 候选原始顺序（检索返回序，从 0 起） */
    @TableField("original_order")
    private Integer originalOrder;

    /** 偏好匹配分（命中画像 权重×置信度 加权累加，与推荐日志同源） */
    @TableField("preference_score")
    private Double preferenceScore;

    /** 新颖性分（历史行程出现过则降为 0.75，否则 1.0） */
    @TableField("novelty_score")
    private Double noveltyScore;

    /** 距离惩罚（预留；候选层无成组锚点暂不计算） */
    @TableField("distance_penalty")
    private Double distancePenalty;

    /** 最终分（统一口径：硬约束沉底=0，否则 clamp01(0.5 + (boost-visited惩罚)*0.6)） */
    @TableField("final_score")
    private Double finalScore;

    /** 命中偏好标签（/ 分隔，无则空） */
    @TableField("matched_tags")
    private String matchedTags;

    /** 命中的回避标签（无则空） */
    @TableField("avoid_tag")
    private String avoidTag;

    /** 是否触发硬约束沉底（回避标签命中且非点名豁免） */
    @TableField("hard_avoid")
    private Integer hardAvoid;

    /** 是否历史行程中出现过 */
    @TableField("visited")
    private Integer visited;

    /** 最终是否入选行程（finalizer 依据最终行程 spots/meals 名称回填） */
    @TableField("selected")
    private Integer selected;

    /** 打分时的画像版本（行为反馈失效缓存用，与结果缓存 key 同源） */
    @TableField("profile_version")
    private Integer profileVersion;

    /** 排序算法版本（防规则变更后复用旧证据） */
    @TableField("ranking_version")
    private Integer rankingVersion;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
