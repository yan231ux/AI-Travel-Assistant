package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 帖子列表卡片（GET /community/posts 与"我的帖子"共用）。
 * 公开流只含 PUBLISHED；mine 列表额外带 status/reject_reason 供作者操作。
 */
@Data
public class PostItem {

    @JsonProperty("id")
    private Long id;

    @JsonProperty("title")
    private String title;

    @JsonProperty("summary")
    private String summary;

    @JsonProperty("cover_image")
    private String coverImage;

    @JsonProperty("city")
    private String city;

    @JsonProperty("post_type")
    private String postType;

    @JsonProperty("status")
    private String status;

    @JsonProperty("author")
    private PostAuthor author;

    @JsonProperty("like_count")
    private Integer likeCount;

    @JsonProperty("favorite_count")
    private Integer favoriteCount;

    @JsonProperty("comment_count")
    private Integer commentCount;

    @JsonProperty("view_count")
    private Integer viewCount;

    @JsonProperty("published_at")
    private String publishedAt;

    @JsonProperty("created_at")
    private String createdAt;

    /** 当前用户是否点过赞（列表复用详情接口合查时填充） */
    @JsonProperty("liked")
    private Boolean liked;

    /** 当前用户是否收藏过 */
    @JsonProperty("favorited")
    private Boolean favorited;

    /** 审核拒绝原因（仅作者视角可见） */
    @JsonProperty("reject_reason")
    private String rejectReason;

    /** 作者是否本人（前端据此显示编辑/删除/提交入口） */
    @JsonProperty("mine")
    private Boolean mine;

    /* ================= 阶段三：个性化推荐流返回（为你推荐排序时填充） ================= */

    /** 推荐理由（"匹配你的偏好：历史文化"；仅 personalized 排序返回） */
    @JsonProperty("recommend_reason")
    private String recommendReason;

    /** 命中的画像标签（前端 🎯 徽标展示用） */
    @JsonProperty("matched_tags")
    private java.util.List<String> matchedTags;

    /** 个性化得分（0~1，排序列透出，供"反馈后排序变化"演示参考） */
    @JsonProperty("score")
    private Double score;

    /* ================= 阶段四：内容质量（管理后台/审核队列展示） ================= */

    /** 内容质量分 0~100（提交审核时确定性计算落库） */
    @JsonProperty("quality_score")
    private Integer qualityScore;

    /** 是否低质内容（低于阈值；审核队列标记提示） */
    @JsonProperty("low_quality")
    private Boolean lowQuality;
}
