package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

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

    /** 作者是否本人 */
    @JsonProperty("mine")
    private Boolean mine;
}
