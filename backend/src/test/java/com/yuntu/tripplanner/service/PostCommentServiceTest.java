package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.exception.PostNotFoundException;
import com.yuntu.tripplanner.model.AuditLog;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock
    private AuditService auditService;
    @Mock
    private ContentModerationService moderationService;

    private PostCommentService service;

    @BeforeEach
    void setUp() {
        service = new PostCommentService(commentRepository, postRepository, communityUserService, auditService);
        org.springframework.test.util.ReflectionTestUtils.setField(service,
                "moderationService", moderationService);
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

    /* ================= 阶段三：楼主可删自己帖子下的评论（三方权限 + 审计） ================= */

    private PostComment comment(Long id, Long postId, String authorId) {
        PostComment c = new PostComment();
        c.setId(id);
        c.setPostId(postId);
        c.setUserId(authorId);
        c.setContent("评论内容");
        c.setStatus(PostComment.STATUS_PUBLISHED);
        return c;
    }

    @Test
    void delete_byPostAuthor_allowedAndAudited() {
        // 评论作者 u3，帖子作者 u1（楼主）→ 楼主有权删自己帖子下的他人评论
        when(commentRepository.selectById(1L)).thenReturn(comment(1L, 5L, "u3"));
        TravelPost post = publishedPost(5L);
        post.setUserId("u1");
        when(postRepository.selectById(5L)).thenReturn(post);
        when(communityUserService.isAdmin("u1")).thenReturn(false);

        service.delete("u1", 1L);

        ArgumentCaptor<PostComment> captor = ArgumentCaptor.forClass(PostComment.class);
        verify(commentRepository).updateById(captor.capture());
        assertEquals(PostComment.STATUS_DELETED, captor.getValue().getStatus());
        verify(postRepository).update(any(), any()); // comment_count - 1
        // 审计留痕：以 post_author 身份删除，并带上评论作者/楼主，便于事后追溯
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq("u1"), eq(AuditLog.CAT_SOCIAL), eq("comment_deleted"),
                eq("comment"), eq("1"), detail.capture());
        assertEquals("post_author", detail.getValue().get("as"));
        assertEquals("u3", detail.getValue().get("comment_author"));
        assertEquals("u1", detail.getValue().get("post_author"));
    }

    @Test
    void delete_strangerOnMissingPost_stillForbidden() {
        // 帖子已查不到且操作者既非评论作者也非管理员 → 不因帖子缺失而放行
        when(commentRepository.selectById(1L)).thenReturn(comment(1L, 5L, "u3"));
        when(postRepository.selectById(5L)).thenReturn(null);
        when(communityUserService.isAdmin("u1")).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> service.delete("u1", 1L));
        verify(commentRepository, never()).updateById(any());
    }

    @Test
    void listComments_canDeleteDependsOnViewerIdentity() {
        TravelPost post = publishedPost(5L); // 楼主 u2
        when(postRepository.selectById(5L)).thenReturn(post);
        when(commentRepository.selectList(any())).thenReturn(List.of(comment(1L, 5L, "u3")));
        when(commentRepository.selectCount(any())).thenReturn(1L);
        when(communityUserService.isAdmin("u1")).thenReturn(false);

        // 路人 u1：既非评论作者也非楼主 → 前端不显示删除按钮
        assertEquals(Boolean.FALSE,
                service.listComments("u1", 5L, 1, 20).getItems().get(0).getCanDelete());
        // 楼主 u2：可删他人评论
        assertEquals(Boolean.TRUE,
                service.listComments("u2", 5L, 1, 20).getItems().get(0).getCanDelete());
        // 评论作者 u3 本人：可删自己的评论
        assertEquals(Boolean.TRUE,
                service.listComments("u3", 5L, 1, 20).getItems().get(0).getCanDelete());
    }
}
