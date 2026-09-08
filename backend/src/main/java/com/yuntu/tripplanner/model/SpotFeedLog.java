package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 推荐景点流曝光日志（对应 spot_feed_log 表，阶段四任务 7：推荐流监控）。
 *
 * <p>镜像 post_feed_log 的口径（产品化阶段三任务 5/8 的帖子版已先行）：
 * 「为你推荐」每次向登录用户返回一页个性化景点时，为页内每条写一行曝光
 * （排序位置/得分/是否命中偏好/攻略质量/画像与算法版本/A/B 变体），作为
 * 推荐流监控的分母；与 user_behavior(item_type=SPOT, item_id=poi_id) 的
 * 收藏/不感兴趣相除得到反馈率。仅在真正个性化分支写日志，
 * 热门/最新浏览不写，保证效果统计口径纯净。
 */
@Data
@TableName("spot_feed_log")
public class SpotFeedLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId;

    /** 系统景点稳定 ID（spot_{city}_{poiId}） */
    @TableField("spot_id")
    private String spotId;

    /** 高德 POI ID：与 user_behavior(item_type=SPOT).item_id 同键，用于反馈漏斗关联 */
    @TableField("poi_id")
    private String poiId;

    @TableField("city")
    private String city;

    /** 排序类型（当前固定 recommended） */
    @TableField("sort")
    private String sort;

    /** 页内排序位置（0 起） */
    @TableField("position")
    private Integer position;

    /** 个性化最终得分（统一评分器口径） */
    @TableField("final_score")
    private Double finalScore;

    /** 是否命中正偏好（1=命中；推荐命中率分子） */
    @TableField("hit_preference")
    private Integer hitPreference;

    /** 候选数据质量（POI_ONLY/GUIDE_MATCHED/VERIFIED，观测实验是否真的抬高了内容质量） */
    @TableField("data_quality")
    private String dataQuality;

    /** 推荐理由（人读） */
    @TableField("recommend_reason")
    private String recommendReason;

    @TableField("profile_version")
    private Integer profileVersion;

    @TableField("ranking_version")
    private Integer rankingVersion;

    /** 所属 A/B 变体（CONTROL/TREATMENT；无实验为 null，不入实验口径） */
    @TableField("ab_variant")
    private String abVariant;


    /** 曝光会话幂等键（P1-5 审查报告）：前端一次页面会话生成的 trace（防刷新/重试/重复渲染
     *  双写曝光稀释分母）；null=旧客户端/未带，仅按原逻辑落一条。配合写入前 (user, trace, item) 判重。 */
    @TableField("feed_trace_id")
    private String feedTraceId;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
