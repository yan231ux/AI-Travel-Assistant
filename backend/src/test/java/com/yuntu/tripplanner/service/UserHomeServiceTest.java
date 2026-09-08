package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.model.PostItem;
import com.yuntu.tripplanner.model.PostPage;
import com.yuntu.tripplanner.model.UserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 用户旅行主页服务单测（阶段四任务 3）：
 * - 用户不存在 → 403；
 * - 本人视角：mine=true、无关注按钮；他人视角：mine=false、following 反映真实关注态；
 * - 帖子段失败降级空列表，不影响头部统计。
 */
@ExtendWith(MockitoExtension.class)
class UserHomeServiceTest {

    @Mock
    private CommunityUserService communityUserService;
    @Mock
    private FollowService followService;
    @Mock
    private PostService postService;

    private UserHomeService newService() {
        return new UserHomeService(communityUserService, followService, postService);
    }

    private PostPage onePostPage() {
        PostPage page = new PostPage();
        page.setTotal(1);
        page.setPage(1);
        page.setItems(java.util.List.of(new PostItem()));
        return page;
    }

    @Test
    void home_targetNotExist_throwsForbidden() {
        when(communityUserService.exists("u9")).thenReturn(false);

        UserHomeService service = newService();
        assertThrows(ForbiddenException.class, () -> service.home("u1", "u9"));
        verifyNoInteractions(postService);
    }

    @Test
    void home_otherUser_followingReflectsRealState() {
        when(communityUserService.exists("u2")).thenReturn(true);
        when(communityUserService.nicknameOf("u2")).thenReturn("阿明");
        when(communityUserService.createdAtOf("u2")).thenReturn("2026-01-01 10:00:00");
        when(followService.followerCount("u2")).thenReturn(5L);
        when(followService.followingCount("u2")).thenReturn(3L);
        when(followService.isFollowing("u1", "u2")).thenReturn(true);
        when(postService.userPublishedPosts(eq("u1"), eq("u2"), eq(1), eq(12))).thenReturn(onePostPage());

        UserHome home = newService().home("u1", "u2");

        assertFalse(home.isMine());
        assertTrue(home.isFollowing());
        assertEquals(5L, home.getFollowerCount());
        assertEquals(3L, home.getFollowingCount());
        assertEquals(1L, home.getPostCount());
        assertEquals(1, home.getPosts().size());
        assertEquals("阿明", home.getNickname());
    }

    @Test
    void home_selfView_mineTrue_noFollowingQuery() {
        when(communityUserService.exists("u1")).thenReturn(true);
        when(communityUserService.nicknameOf("u1")).thenReturn("我自己");
        when(followService.followerCount("u1")).thenReturn(2L);
        when(followService.followingCount("u1")).thenReturn(1L);
        when(postService.userPublishedPosts(eq("u1"), eq("u1"), eq(1), eq(12))).thenReturn(onePostPage());

        UserHome home = newService().home("u1", "u1");

        assertTrue(home.isMine());
        assertFalse(home.isFollowing());
        // 本人视角不查关注态
        verify(followService, never()).isFollowing(any(), any());
    }

    @Test
    void home_postFailure_degradesPostsOnly() {
        when(communityUserService.exists("u2")).thenReturn(true);
        when(communityUserService.nicknameOf("u2")).thenReturn("阿明");
        when(followService.followerCount("u2")).thenReturn(0L);
        when(followService.followingCount("u2")).thenReturn(0L);
        when(followService.isFollowing(any(), any())).thenReturn(false);
        when(postService.userPublishedPosts(any(), any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("查询失败"));

        UserHome home = newService().home("u1", "u2");

        assertFalse(home.isMine());
        assertEquals(0, home.getPosts().size());
        assertEquals(0L, home.getPostCount());
        // 头部信息不受影响
        assertEquals("阿明", home.getNickname());
    }
}
