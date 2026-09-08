package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 城市专题聚合响应（阶段四：GET /city-topic?city=xxx，PRODUCT_EVOLUTION_PLAN §15 任务1）。
 *
 * <p>一个城市的"一页式"聚合：热门景点（复用推荐流的攻略质量/个性化排序语义）+
 * 该城已发布攻略 + 城市规模统计（入库景点数 / 已发布帖子数）。
 * 不新增算法：景点复用 RecommendationFeedService（sort=popular，无画像时攻略质量优先），
 * 帖子复用 PostService 公开流（只含 PUBLISHED），避免专题页与现有推荐口径分叉。
 */
@Data
public class CityTopic {

    @JsonProperty("city")
    private String city;

    /** 该城市已入库景点数（spot 表） */
    @JsonProperty("spot_total")
    private Long spotTotal;

    /** 该城市已发布帖子数（travel_post 公开流统计） */
    @JsonProperty("post_total")
    private Long postTotal;

    /** 热门景点（复用推荐流热门语义，上限 8） */
    @JsonProperty("hot_spots")
    private List<RecommendationItem> hotSpots;

    /** 该城最新公开攻略（上限 6） */
    @JsonProperty("recent_posts")
    private List<PostItem> recentPosts;
}
