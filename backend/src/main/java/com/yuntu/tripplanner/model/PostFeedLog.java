package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 帖子推荐流曝光日志（对应 post_feed_log 表，产品化阶段三任务 5/8）。
 *
 * <p>「社区为你推荐」每次向登录用户返回一页推荐帖子时，为该页每篇帖子写一条曝光记录
 * （排序位置/得分/是否命中画像/推荐理由/画像版本/算法版本）。曝光数作为分母，
 * 与 user_behavior(item_type=POST) 的点击/收藏/不感兴趣数相除，得出帖子推荐流的
 * 点击率/收藏率/负反馈率（阶段三验收「推荐行为可以进入效果统计」）。
 * 仅在 personalized 分支写日志，避免污染热门/最新排序的统计口径。
 */
@Data
@TableName("post_feed_log")
public class PostFeedLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId;

    @TableField("post_id")
    private Long postId;

    /** 推荐排序类型（当前固定 recommended，为未来 A/B 预留） */
    @TableField("sort")
    private String sort;

    /** 该页中的排序位置（0 起） */
    @TableField("position")
    private Integer position;

    /** 最终个性化得分（口径见 PersonalizedScoreCalculator#evaluatePost） */
    @TableField("final_score")
    private Double finalScore;

    /** 是否命中正偏好标签（1=命中；帖子推荐命中率分子） */
    @TableField("hit_preference")
    private Integer hitPreference;

    /** 推荐理由（人读："匹配你的偏好：历史文化"） */
    @TableField("recommend_reason")
    private String recommendReason;

    /** 打分时的画像版本（行为反馈递增后，旧曝光可与新画像行为区分） */
    @TableField("profile_version")
    private Integer profileVersion;

    /** 帖子推荐流算法版本（阶段三任务 7：推荐流算法版本，规则变更后 bump 隔离旧曝光） */
    @TableField("ranking_version")
    private Integer rankingVersion;

    /** 所属 A/B 变体（阶段四任务 6：CONTROL/TREATMENT；无实验为 null，不入实验口径） */
    @TableField("ab_variant")
    private String abVariant;


    /** 曝光会话幂等键（P1-5 审查报告）：前端一次页面会话生成的 trace（防刷新/重试/重复渲染
     *  双写曝光稀释分母）；null=旧客户端/未带，仅按原逻辑落一条。配合写入前 (user, trace, item) 判重。 */
    @TableField("feed_trace_id")
    private String feedTraceId;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
