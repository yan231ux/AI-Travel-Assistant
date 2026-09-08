package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 用户旅行主页聚合（阶段四任务 3：GET /users/{userId}/home）。
 *
 * <p>展示维度刻意只取「用户主动公开」的数据：昵称/注册时间、粉丝与关注数、
 * 已发布帖子列表（PUBLISHED 公开流语义，与社区口径一致）。不暴露收藏、
 * 历史行程等私有数据 —— 对外主页不等于管理后台，避免隐私越界。
 */
@Data
public class UserHome {

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("nickname")
    private String nickname;

    /** 注册时间（yyyy-MM-dd HH:mm:ss；不存在为 null） */
    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("follower_count")
    private long followerCount;

    @JsonProperty("following_count")
    private long followingCount;

    /** 已发布帖子数 */
    @JsonProperty("post_count")
    private long postCount;

    /** 是否本人（前端隐藏关注按钮） */
    @JsonProperty("mine")
    private boolean mine;

    /** 当前查看者是否已关注该用户 */
    @JsonProperty("following")
    private boolean following;

    /** 该用户已发布帖子（最新在前，单页） */
    @JsonProperty("posts")
    private List<PostItem> posts;
}
