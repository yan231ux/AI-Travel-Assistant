package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 更新帖子请求（PUT /community/posts/{postId}）。
 * 仅作者本人可改；可编辑状态：DRAFT / REJECTED（改后需重新提交审核），PUBLISHED 仅允许改正文。
 * 全部字段可空 = 不改（null 字段跳过），spots=null 表示保留现有关联。
 *
 * <p><b>2026-09-13 契约修复</b>：与 {@link PostCreateRequest} 同因 —— 补 camelCase
 * 别名，避免编辑时再次把封面/天数/类型悄悄丢掉。
 */
@Data
public class PostUpdateRequest {

    @JsonProperty("title")
    private String title;

    @JsonProperty("summary")
    private String summary;

    @JsonProperty("content")
    private String content;

    @JsonProperty("cover_image")
    @JsonAlias({"coverImage"})
    private String coverImage;

    @JsonProperty("city")
    private String city;

    @JsonProperty("travel_days")
    @JsonAlias({"travelDays"})
    private Integer travelDays;

    @JsonProperty("budget")
    private Double budget;

    @JsonProperty("pace")
    private String pace;

    @JsonProperty("post_type")
    @JsonAlias({"postType"})
    private String postType;

    @JsonProperty("spots")
    private List<PostSpotRef> spots;
}
