package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.model.UserFollow;
import com.yuntu.tripplanner.repository.UserFollowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 关注服务单测（阶段四任务 2）：幂等 / 自关注禁止 / 目标存在校验 / 取关幂等 / 统计与列表。
 */
@ExtendWith(MockitoExtension.class)
class FollowServiceTest {

    @Mock
    private UserFollowRepository followRepository;
    @Mock
    private CommunityUserService communityUserService;
    @Mock
    private AuditService auditService;

    private FollowService service;

    @BeforeEach
    void setUp() {
        service = new FollowService(followRepository, communityUserService, auditService);
    }

    private void stubTargetExists() {
        when(communityUserService.exists(anyString())).thenReturn(true);
    }

    @Test
    void follow_new_insertsAndReturnsTrue() {
        stubTargetExists();
        when(followRepository.insert(any(UserFollow.class))).thenReturn(1);

        boolean created = service.follow("u1", "u2");

        assertTrue(created);
        verify(followRepository).insert(any(UserFollow.class));
    }

    @Test
    void follow_duplicate_keyIgnored_returnsFalse() {
        stubTargetExists();
        doThrow(new DuplicateKeyException("dup")).when(followRepository).insert(any(UserFollow.class));

        boolean created = service.follow("u1", "u2");

        assertFalse(created);
    }

    @Test
    void follow_self_throwsForbidden() {
        assertThrows(ForbiddenException.class, () -> service.follow("u1", "u1"));
        verifyNoInteractions(followRepository);
    }

    @Test
    void follow_targetNotExist_throwsForbidden() {
        when(communityUserService.exists("u2")).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> service.follow("u1", "u2"));
        verifyNoInteractions(followRepository);
    }

    @Test
    void unfollow_deletesRow_ignoresMissing() {
        stubTargetExists();

        service.unfollow("u1", "u2");

        verify(followRepository).delete(any());
    }

    @Test
    void isFollowing_true_whenRowExists() {
        when(followRepository.selectCount(any())).thenReturn(1L);

        assertTrue(service.isFollowing("u1", "u2"));
    }

    @Test
    void isFollowing_false_whenBlank() {
        assertFalse(service.isFollowing(null, "u2"));
        assertFalse(service.isFollowing("", "u2"));
        verifyNoInteractions(followRepository);
    }

    @Test
    void counts_delegateToRepository() {
        when(followRepository.selectCount(any())).thenReturn(3L);

        assertEquals(3L, service.followingCount("u1"));
        assertEquals(3L, service.followerCount("u2"));
        verify(followRepository, times(2)).selectCount(any());
    }

    @Test
    void following_list_resolvesNicknames() {
        UserFollow row = new UserFollow();
        row.setUserId("u1");
        row.setFollowUserId("u2");
        when(followRepository.selectList(any())).thenReturn(java.util.List.of(row));
        when(communityUserService.nicknamesOf(any())).thenReturn(java.util.Map.of("u2", "阿明"));

        var list = service.following("u1", 10);

        assertEquals(1, list.size());
        assertEquals("u2", list.get(0).getId());
        assertEquals("阿明", list.get(0).getNickname());
    }

    @Test
    void followers_list_returnsFollowerIds() {
        UserFollow row = new UserFollow();
        row.setUserId("u1");
        row.setFollowUserId("u9");
        when(followRepository.selectList(any())).thenReturn(java.util.List.of(row));
        when(communityUserService.nicknamesOf(any())).thenReturn(java.util.Map.of("u1", "粉丝一号"));

        var list = service.followers("u9", 10);

        assertEquals(1, list.size());
        assertEquals("u1", list.get(0).getId());
        assertEquals("粉丝一号", list.get(0).getNickname());
    }
}
