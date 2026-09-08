package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/** 帖子分页结果（公开流 / 我的帖子 / 审核队列共用） */
@Data
public class PostPage {

    @JsonProperty("items")
    private List<PostItem> items;

    @JsonProperty("total")
    private long total;

    @JsonProperty("page")
    private int page;

    @JsonProperty("page_size")
    private int pageSize;

    /** 本页是否真正个性化排序（recommended 且画像命中时为 true；降级热门/最新/热门为 false/null） */
    @JsonProperty("personalized")
    private Boolean personalized;
}
