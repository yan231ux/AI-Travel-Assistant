package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.model.CityTopic;
import com.yuntu.tripplanner.model.PostItem;
import com.yuntu.tripplanner.model.PostPage;
import com.yuntu.tripplanner.model.RecommendationFeed;
import com.yuntu.tripplanner.model.RecommendationItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 城市专题服务单测（阶段四任务 1）：
 * - 空城市 → 返回空专题不报错；
 * - 正常城市 → 景点/帖子复用两个既有服务的响应组装（不新造口径）；
 * - 景点或帖子聚合失败 → 对应段降级空列表，不影响另一段。
 */
@ExtendWith(MockitoExtension.class)
class CityTopicServiceTest {

    @Mock
    private RecommendationFeedService feedService;
    @Mock
    private PostService postService;

    private CityTopicService service;

    private CityTopicService newService() {
        return new CityTopicService(feedService, postService);
    }

    private RecommendationFeed feedWith(int n) {
        RecommendationFeed feed = new RecommendationFeed();
        java.util.List<RecommendationItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            items.add(new RecommendationItem());
        }
        feed.setItems(items);
        feed.setTotal((long) n);
        feed.setPersonalized(false);
        return feed;
    }

    private PostPage pageWith(int n) {
        PostPage page = new PostPage();
        java.util.List<PostItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            items.add(new PostItem());
        }
        page.setItems(items);
        page.setTotal((long) n);
        page.setPage(1);
        page.setPageSize(6);
        return page;
    }

    @Test
    void blankCity_returnsEmptyTopic_withoutCallingDeps() {
        service = newService();

        CityTopic topic = service.topic("u1", "  ");

        assertNull(topic.getCity());
        assertEquals(0L, topic.getSpotTotal());
        assertEquals(0L, topic.getPostTotal());
        assertTrue(topic.getHotSpots().isEmpty());
        assertTrue(topic.getRecentPosts().isEmpty());
        verifyNoInteractions(feedService, postService);
    }

    @Test
    void topic_aggregatesSpotsAndPosts() {
        service = newService();
        when(feedService.feed(eq("u1"), eq("成都"), eq(1), eq(8), eq("popular")))
                .thenReturn(feedWith(3));
        when(postService.publicFeed(eq("u1"), eq("成都"), eq(null), eq("latest"), eq(1), eq(6)))
                .thenReturn(pageWith(2));

        CityTopic topic = service.topic("u1", "成都");

        assertEquals("成都", topic.getCity());
        assertEquals(3L, topic.getSpotTotal());
        assertEquals(2L, topic.getPostTotal());
        assertEquals(3, topic.getHotSpots().size());
        assertEquals(2, topic.getRecentPosts().size());
        verify(feedService).feed(eq("u1"), eq("成都"), eq(1), eq(8), eq("popular"));
        verify(postService).publicFeed(eq("u1"), eq("成都"), eq(null), eq("latest"), eq(1), eq(6));
    }

    @Test
    void topic_feedFailure_degradesSpotsOnly() {
        service = newService();
        when(feedService.feed(anyString(), anyString(), anyInt(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("高德不可用"));
        when(postService.publicFeed(anyString(), anyString(), isNull(), anyString(), anyInt(), anyInt()))
                .thenReturn(pageWith(1));

        CityTopic topic = service.topic("u1", "西安");

        assertEquals("西安", topic.getCity());
        assertEquals(0L, topic.getSpotTotal());
        assertTrue(topic.getHotSpots().isEmpty());
        // 帖子段不受影响
        assertEquals(1L, topic.getPostTotal());
        assertEquals(1, topic.getRecentPosts().size());
    }

    @Test
    void topic_postFailure_degradesPostsOnly() {
        service = newService();
        when(feedService.feed(anyString(), anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(feedWith(2));
        when(postService.publicFeed(anyString(), anyString(), isNull(), anyString(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("帖子查询失败"));

        CityTopic topic = service.topic("u1", "大理");

        assertEquals(2L, topic.getSpotTotal());
        assertEquals(2, topic.getHotSpots().size());
        assertEquals(0L, topic.getPostTotal());
        assertTrue(topic.getRecentPosts().isEmpty());
    }
}
