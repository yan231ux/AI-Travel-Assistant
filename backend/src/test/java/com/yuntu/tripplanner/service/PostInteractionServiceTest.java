package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.exception.PostNotFoundException;
import com.yuntu.tripplanner.model.BehaviorRequest;
import com.yuntu.tripplanner.model.TravelPost;
import com.yuntu.tripplanner.model.UserBehavior;
import com.yuntu.tripplanner.repository.PostInteractionRepository;
import com.yuntu.tripplanner.repository.TravelPostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 帖子互动单测（阶段二 §9.5）：点赞/收藏/不喜欢幂等。
 * - 重复点赞不重复插行、不重复计数（唯一键冲突按幂等处理）；
 * - 取消 = 删行 + 递减计数器（防负）；
 * - 只有 PUBLISHED 帖子可互动；帖子不存在 → 404。
 */
@ExtendWith(MockitoExtension.class)
class PostInteractionServiceTest {

    @Mock
    private PostInteractionRepository interactionRepository;
    @Mock
    private TravelPostRepository postRepository;
    @Mock
    private UserProfileService userProfileService;

    private PostInteractionService service;

    @BeforeEach
    void setUp() {
        service = new PostInteractionService(interactionRepository, postRepository, userProfileService);
    }

    private TravelPost published(Long id) {
        TravelPost p = new TravelPost();
        p.setId(id);
        p.setUserId("u2");
        p.setTitle("标题");
        p.setContent("正文内容足够长一些，正文内容足够长一些。");
        p.setStatus(TravelPost.STATUS_PUBLISHED);
        p.setPublishedAt(LocalDateTime.now());
        p.setLikeCount(3);
        p.setFavoriteCount(1);
        return p;
    }

    @Test
    void like_repeatClick_idempotent_noDoubleCount() {
        when(postRepository.selectById(1L)).thenReturn(published(1L));
        when(interactionRepository.insert(any())).thenThrow(new DuplicateKeyException("dup"));
        // 取消相关查询：LIKE 已存在、FAVORITE 不存在、DISLIKE 不存在
        when(interactionRepository.selectCount(any()))
                .thenReturn(1L).thenReturn(0L).thenReturn(0L);
        when(postRepository.selectById(1L)).thenReturn(published(1L));

        var state = service.interact("u1", 1L, "LIKE", true);

        assertTrue(state.liked());
        // 计数不应被重复 +1（update 一次都没发生，因为 insert 被唯一键拦截）
        verify(postRepository, never()).update(any(), any());
    }

    @Test
    void unlike_removesRowAndDecrementsCount() {
        when(postRepository.selectById(1L)).thenReturn(published(1L));
        when(interactionRepository.delete(any())).thenReturn(1);
        when(interactionRepository.selectCount(any()))
                .thenReturn(0L).thenReturn(0L).thenReturn(0L);
        TravelPost fresh = published(1L);
        fresh.setLikeCount(2);
        when(postRepository.selectById(1L)).thenReturn(fresh);

        var state = service.interact("u1", 1L, "LIKE", false);

        assertFalse(state.liked());
        assertEquals(2, state.likeCount());
        verify(interactionRepository).delete(any());
        verify(postRepository, atLeastOnce()).update(any(), any());
    }

    @Test
    void dislike_trackedWithoutCounterColumn() {
        when(postRepository.selectById(1L)).thenReturn(published(1L));
        when(interactionRepository.insert(any())).thenReturn(1);
        when(interactionRepository.selectCount(any())).thenReturn(1L);
        when(postRepository.selectById(1L)).thenReturn(published(1L));

        var state = service.interact("u1", 1L, "DISLIKE", true);

        assertTrue(state.disliked());
        // DISLIKE 不维护计数列 → 不做计数器 update
        verify(postRepository, never()).update(any(), any());
    }

    @Test
    void interact_draftPost_forbidden() {
        TravelPost draft = published(1L);
        draft.setStatus(TravelPost.STATUS_DRAFT);
        draft.setPublishedAt(null);
        when(postRepository.selectById(1L)).thenReturn(draft);

        assertThrows(ForbiddenException.class,
                () -> service.interact("u1", 1L, "LIKE", true));
    }

    @Test
    void dislike_ownPost_forbidden() {
        // published(1L) 的作者是 u2：用 u2 自己对帖子点"不感兴趣" → 必须 403
        when(postRepository.selectById(1L)).thenReturn(published(1L));

        assertThrows(ForbiddenException.class,
                () -> service.interact("u2", 1L, "DISLIKE", true));
        verify(interactionRepository, never()).insert(any());
    }

    @Test
    void interact_missingPost_notFound() {
        when(postRepository.selectById(99L)).thenReturn(null);

        assertThrows(PostNotFoundException.class,
                () -> service.interact("u1", 99L, "LIKE", true));
    }

    /* ---- P1-4（审查报告）：互动与画像更新强一致，画像失败整体回滚 ---- */

    @Test
    void interact_whenProfileUpdateFails_exceptionPropagates() {
        when(postRepository.selectById(1L)).thenReturn(published(1L));
        when(interactionRepository.insert(any())).thenReturn(1);
        doThrow(new IllegalStateException("画像更新失败（模拟）"))
                .when(userProfileService).recordBehavior(anyString(), any(BehaviorRequest.class));

        // 画像/行为写入失败 → 异常上抛（interact 同事务 → 互动行与计数一并回滚，不返回"假成功"）
        assertThrows(IllegalStateException.class,
                () -> service.interact("u1", 1L, "LIKE", true));
        verify(interactionRepository).insert(any());
    }

    /* ---- 画像可撤销：取消收藏回退（与「收藏 +0.15」对称，回退幅度更小） ---- */

    @Test
    void unfavorite_rollsBackProfileWithUnsave() {
        when(postRepository.selectById(1L)).thenReturn(published(1L));
        when(interactionRepository.delete(any())).thenReturn(1);
        when(interactionRepository.selectCount(any()))
                .thenReturn(0L).thenReturn(0L).thenReturn(0L);

        var state = service.interact("u1", 1L, "FAVORITE", false);

        assertFalse(state.favorited());
        // 确实删掉了收藏行 → 触发 UNSAVE 画像回退（不是 DISLIKE：用户只是改主意，不是"不喜欢"）
        ArgumentCaptor<BehaviorRequest> cap = ArgumentCaptor.forClass(BehaviorRequest.class);
        verify(userProfileService).recordBehavior(eq("u1"), cap.capture());
        assertEquals(UserBehavior.ACTION_UNSAVE, cap.getValue().getActionType());
        assertEquals(UserBehavior.ITEM_TYPE_POST, cap.getValue().getItemType());
        assertEquals("1", cap.getValue().getItemId());
    }

    @Test
    void unfavorite_whenNotFavorited_doesNotTouchProfile() {
        // 幂等空删 → 无正向贡献可退，不扣分
        when(postRepository.selectById(1L)).thenReturn(published(1L));
        when(interactionRepository.delete(any())).thenReturn(0);
        when(interactionRepository.selectCount(any()))
                .thenReturn(0L).thenReturn(0L).thenReturn(0L);

        service.interact("u1", 1L, "FAVORITE", false);

        verify(userProfileService, never()).recordBehavior(anyString(), any());
    }

    @Test
    void unlike_doesNotRollBackProfile() {
        // 点赞是 0.05 弱信号，取消点赞不回退：高频轻操作回退会反复扰动画像
        when(postRepository.selectById(1L)).thenReturn(published(1L));
        when(interactionRepository.delete(any())).thenReturn(1);
        when(interactionRepository.selectCount(any()))
                .thenReturn(0L).thenReturn(0L).thenReturn(0L);

        service.interact("u1", 1L, "LIKE", false);

        verify(userProfileService, never()).recordBehavior(anyString(), any());
    }
}
