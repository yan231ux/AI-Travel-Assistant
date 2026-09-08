package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** 发评论请求（POST /community/posts/{postId}/comments） */
@Data
public class CommentCreateRequest {

    @JsonProperty("content")
    private String content;

    /** 回复的父评论 id（一级评论为空） */
    @JsonProperty("parent_id")
    private Long parentId;
}
