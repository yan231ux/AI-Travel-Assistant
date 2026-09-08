package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 创建帖子请求（POST /community/posts）。
 *
 * <p>入参 key 一律 snake_case（与后端输出风格一致）。创建后默认 DRAFT，
 * 需再调 POST /community/posts/{id}/submit 提交审核 —— 帖子不能绕过审核直接公开。
 */
@Data
public class PostCreateRequest {

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

    @JsonProperty("spots")
    private List<PostSpotRef> spots;
}
