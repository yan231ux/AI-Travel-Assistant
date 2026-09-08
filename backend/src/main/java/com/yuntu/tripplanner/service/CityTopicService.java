package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.model.CityTopic;
import com.yuntu.tripplanner.model.PostItem;
import com.yuntu.tripplanner.model.PostPage;
import com.yuntu.tripplanner.model.RecommendationFeed;
import com.yuntu.tripplanner.model.RecommendationItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 城市专题服务（阶段四任务 1：城市专题页聚合）。
 *
 * <p>职责是把一个城市的「热门景点 + 最新公开攻略 + 规模统计」组装成单一响应，
 * 供前端 /city/:name 专题页一次拉齐。设计上刻意不新增算法：
 * <ul>
 *   <li>景点：复用 {@link RecommendationFeedService#feed}（sort=popular —— 无画像降级为
 *       攻略质量优先，避免专题页与首页/发现页推荐口径分叉）；</li>
 *   <li>帖子：复用 {@link PostService#publicFeed}（公开流只含 PUBLISHED，未审核/隐藏/删除
 *       不会出现在专题页，与社区口径一致）；</li>
 *   <li>统计：spotTotal/postTotal 直接取自上述两接口的 total（单一数据源，不重复查库）。</li>
 * </ul>
 */
@Slf4j
@Service
public class CityTopicService {

    /** 专题页景点上限（页面上限 8 个热门景点 + 6 篇攻略，避免首屏过重） */
    private static final int MAX_HOT_SPOTS = 8;
    private static final int MAX_RECENT_POSTS = 6;

    private final RecommendationFeedService feedService;
    private final PostService postService;

    public CityTopicService(RecommendationFeedService feedService, PostService postService) {
        this.feedService = feedService;
        this.postService = postService;
    }

    /**
     * 城市专题聚合。
     *
     * @param viewerId 当前查看者（未登录可为 null；用于景点 isCollected/推荐理由个性化）
     * @param city     城市名（如"成都"）；空/空白返回空专题（不报错）
     */
    public CityTopic topic(String viewerId, String city) {
        CityTopic topic = new CityTopic();
        if (city == null || city.isBlank()) {
            topic.setCity(null);
            topic.setSpotTotal(0L);
            topic.setPostTotal(0L);
            topic.setHotSpots(List.of());
            topic.setRecentPosts(List.of());
            return topic;
        }
        String cityKey = city.trim();
        topic.setCity(cityKey);

        // 景点：热门语义（攻略质量优先；无画像自动降级，与发现页一致）
        try {
            RecommendationFeed feed = feedService.feed(viewerId, cityKey, 1, MAX_HOT_SPOTS, "popular");
            List<RecommendationItem> spots = feed.getItems() == null ? List.of() : feed.getItems();
            topic.setSpotTotal(feed.getTotal() == null ? (long) spots.size() : feed.getTotal());
            topic.setHotSpots(spots.size() > MAX_HOT_SPOTS ? spots.subList(0, MAX_HOT_SPOTS) : spots);
        } catch (Exception e) {
            log.warn("城市专题景点聚合失败（降级空列表）: {} - {}", cityKey, e.getMessage());
            topic.setSpotTotal(0L);
            topic.setHotSpots(List.of());
        }

        // 攻略：公开流最新（PUBLISHED 过滤在 PostService 内保证）
        try {
            PostPage page = postService.publicFeed(viewerId, cityKey, null, "latest", 1, MAX_RECENT_POSTS);
            List<PostItem> posts = page.getItems() == null ? List.of() : page.getItems();
            topic.setPostTotal(page.getTotal());
            topic.setRecentPosts(posts.size() > MAX_RECENT_POSTS ? posts.subList(0, MAX_RECENT_POSTS) : posts);
        } catch (Exception e) {
            log.warn("城市专题攻略聚合失败（降级空列表）: {} - {}", cityKey, e.getMessage());
            topic.setPostTotal(0L);
            topic.setRecentPosts(List.of());
        }
        return topic;
    }
}
