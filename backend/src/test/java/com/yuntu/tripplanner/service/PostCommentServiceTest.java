package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.exception.PostNotFoundException;
import com.yuntu.tripplanner.model.PostComment;
import com.yuntu.tripplanner.model.TravelPost;
import com.yuntu.tripplanner.repository.PostCommentRepository;
import com.yuntu.tripplanner.repository.TravelPostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 评论单测（阶段二 §9.6）：只能在已发布帖子评论、计数联动、删除权限。
 */
@ExtendWith(MockitoExtension.class)
class PostCommentServiceTest {

    @Mock
    private PostCommentRepository commentRepository;
    @Mock
    private TravelPostRepository postRepository;
    @Mock
    private CommunityUserService communityUserService;

    private PostCommentService service;

    @BeforeEach
    void setUp() {
        service = new PostCommentService(commentRepository, postRepository, communityUserService);
        lenient().when(communityUserService.nicknameOf(any())).thenReturn("用户");
        lenient().when(communityUserService.nicknamesOf(any())).thenReturn(java.util.Map.of());
    }

    private TravelPost publishedPost(Long id) {
        TravelPost p = new TravelPost();
        p.setId(id);
        p.setUserId("u2");
        p.setTitle("标题");
        p.setContent("正文内容足够长，正文内容足够长。");
        p.setStatus(TravelPost.STATUS_PUBLISHED);
        p.setPublishedAt(LocalDateTime.now());
        p.setCommentCount(0);
        return p;
    }

    @Test
    void add_onDraftPost_forbidden() {
        TravelPost draft = publishedPost(1L);
        draft.setStatus(TravelPost.STATUS_DRAFT);
        draft.setPublishedAt(null);
        when(postRepository.selectById(1L)).thenReturn(draft);

        assertThrows(ForbiddenException.class,
                () -> service.add("u1", 1L, "写得很实用", null));
    }

    @Test
    void add_onPublishedPost_incrementsCommentCount() {
        when(postRepository.selectById(1L)).thenReturn(publishedPost(1L));
        doAnswer(inv -> {
            PostComment c = inv.getArgument(0);
            c.setId(5L);
            c.setCreatedAt(LocalDateTime.now());
            return 1;
        }).when(commentRepository).insert(any(PostComment.class));

        var item = service.add("u1", 1L, "写得很实用", null);

        assertEquals(5L, item.getId());
        assertEquals("写得很实用", item.getContent());
        verify(postRepository).update(any(), any()); // comment_count + 1
    }

    @Test
    void add_blankContent_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.add("u1", 1L, "   ", null));
    }

    @Test
    void delete_byOtherNonAdmin_forbidden() {
        PostComment c = new PostComment();
        c.setId(1L);
        c.setPostId(1L);
        c.setUserId("u2");
        c.setContent("评论");
        c.setStatus(PostComment.STATUS_PUBLISHED);
        when(commentRepository.selectById(1L)).thenReturn(c);
        when(communityUserService.isAdmin(any())).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> service.delete("u1", 1L));
    }

    @Test
    void delete_byOwner_softDeletesAndDecrements() {
        PostComment c = new PostComment();
        c.setId(1L);
        c.setPostId(1L);
        c.setUserId("u1");
        c.setContent("我的评论");
        c.setStatus(PostComment.STATUS_PUBLISHED);
        when(commentRepository.selectById(1L)).thenReturn(c);

        service.delete("u1", 1L);

        ArgumentCaptor<PostComment> captor = ArgumentCaptor.forClass(PostComment.class);
        verify(commentRepository).updateById(captor.capture());
        assertEquals(PostComment.STATUS_DELETED, captor.getValue().getStatus());
        verify(postRepository).update(any(), any()); // comment_count - 1
    }

    @Test
    void delete_missingComment_notFound() {
        when(commentRepository.selectById(9L)).thenReturn(null);
        assertThrows(PostNotFoundException.class, () -> service.delete("u1", 9L));
    }
}
