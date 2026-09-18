package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 创建帖子请求（POST /community/posts）。
 *
 * <p>入参 key 一律 snake_case（与后端输出风格一致）。创建后默认 DRAFT，
 * 需再调 POST /community/posts/{id}/submit 提交审核 —— 帖子不能绕过审核直接公开。
 *
 * <p><b>2026-09-13 契约修复</b>：cover_image / travel_days / post_type 三个字段历史上
 * 前端直发 camelCase（coverImage / travelDays / postType），而本类只声明了 snake_case，
 * Spring Boot 默认静默忽略未知属性 → 字段被丢弃、且不报错（封面丢失、天数全 NULL、
 * 类型永远回落 NOTE）。现补 {@code @JsonAlias} 兼容两种写法（属于"两道保险"：
 * snake_case 是唯一对外契约，camelCase 只为兼容旧客户端），并同步把前端改为发 snake_case。
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
