package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** 帖子详情响应（GET /community/posts/{postId}） */
@Data
public class PostDetail {

    @JsonProperty("id")
    private Long id;

    @JsonProperty("title")
    private String title;

    @JsonProperty("summary")
    private String summary;

    @JsonProperty("content")
    private String content;

    @JsonProperty("cover_image")
    private String coverImage;

    @JsonProperty("city")
    private String city;

    @JsonProperty("travel_days")
    private Integer travelDays;

    @JsonProperty("budget")
    private Double budget;

    @JsonProperty("pace")
    private String pace;

    @JsonProperty("post_type")
    private String postType;

    @JsonProperty("status")
    private String status;

    @JsonProperty("author")
    private PostAuthor author;

    @JsonProperty("spots")
    private List<PostSpotRef> spots;

    @JsonProperty("like_count")
    private Integer likeCount;

    @JsonProperty("favorite_count")
    private Integer favoriteCount;

    @JsonProperty("comment_count")
    private Integer commentCount;

    @JsonProperty("view_count")
    private Integer viewCount;

    @JsonProperty("reject_reason")
    private String rejectReason;

    @JsonProperty("published_at")
    private String publishedAt;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("liked")
    private Boolean liked;

    @JsonProperty("favorited")
    private Boolean favorited;

    /**
     * 当前用户是否点过「不感兴趣」（DISLIKE）。
     * 2026-09-18：补齐详情页的互动状态回填，避免详情页"不感兴趣"按钮状态刷新即丢。
     */
    @JsonProperty("disliked")
    private Boolean disliked;

    /** 作者是否本人 */
    @JsonProperty("mine")
    private Boolean mine;

    /* ================= P1-1 编辑版本化：公开版本 / 编辑版本分离 ================= */

    /** 是否存在「待审修改版本」（已发布帖被编辑 → 修改稿独立待审，线上版本不变） */
    @JsonProperty("has_pending_revision")
    private Boolean hasPendingRevision;

    /** 待审修改版本号（1=首版，2=第 2 版…） */
    @JsonProperty("pending_revision_no")
    private Integer pendingRevisionNo;

    /**
     * 待审修改版本内容快照（作者/审核员可见，无待审版本时为 null）。
     * 用于「线上版本 vs 待审版本」对比，避免管理员盲审。
     */
    @JsonProperty("pending_revision")
    private Map<String, Object> pendingRevision;
}
