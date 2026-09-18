package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.exception.PostNotFoundException;
import com.yuntu.tripplanner.model.ContentReport;
import com.yuntu.tripplanner.model.PostComment;
import com.yuntu.tripplanner.model.TravelPost;
import com.yuntu.tripplanner.repository.ContentReportRepository;
import com.yuntu.tripplanner.repository.PostCommentRepository;
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
import static org.mockito.Mockito.*;

/**
 * 举报单测（阶段二 §9.7/§10）：目标必须存在、重复举报幂等、管理员校验、
 * RESOLVE 成立自动隐藏帖子 / DISMISS 驳回。
 */
@ExtendWith(MockitoExtension.class)
class PostReportServiceTest {

    @Mock
    private ContentReportRepository reportRepository;
    @Mock
    private TravelPostRepository postRepository;
    @Mock
    private PostCommentRepository commentRepository;
    @Mock
    private CommunityUserService communityUserService;
    @Mock
    private AuditService auditService;

    private PostReportService service;

    @BeforeEach
    void setUp() {
        service = new PostReportService(reportRepository, postRepository, commentRepository,
                communityUserService, auditService);
        lenient().when(communityUserService.nicknamesOf(any())).thenReturn(java.util.Map.of());
    }

    private TravelPost publishedPost() {
        TravelPost p = new TravelPost();
        p.setId(1L);
        p.setUserId("u2");
        p.setTitle("标题");
        p.setContent("正文内容足够长一些。");
        p.setStatus(TravelPost.STATUS_PUBLISHED);
        p.setPublishedAt(LocalDateTime.now());
        return p;
    }

    @Test
    void create_reportsExistingPost() {
        when(postRepository.selectById(1L)).thenReturn(publishedPost());

        boolean existed = service.create("u1", "POST", 1L, "广告", null);

        assertFalse(existed);
        verify(reportRepository).insert(any(ContentReport.class));
    }

    @Test
    void create_duplicateReport_idempotentExisted() {
        when(postRepository.selectById(1L)).thenReturn(publishedPost());
        when(reportRepository.insert(any())).thenThrow(new DuplicateKeyException("dup"));

        boolean existed = service.create("u1", "POST", 1L, "广告", null);

        assertTrue(existed);
        verify(reportRepository, never()).updateById(any());
    }

    @Test
    void create_missingTarget_notFound() {
        when(postRepository.selectById(99L)).thenReturn(null);
        assertThrows(PostNotFoundException.class,
                () -> service.create("u1", "POST", 99L, "广告", null));
    }

    @Test
    void create_invalidReason_badRequest() {
        when(postRepository.selectById(1L)).thenReturn(publishedPost());
        assertThrows(IllegalArgumentException.class,
                () -> service.create("u1", "POST", 1L, "随便", null));
    }

    @Test
    void create_onOwnPost_rejected() {
        // 作者不该举报自己：对自己内容有编辑/删除手段，举报是给其他用户的工具
        when(postRepository.selectById(1L)).thenReturn(publishedPost()); // 作者 = u2

        assertThrows(IllegalArgumentException.class,
                () -> service.create("u2", "POST", 1L, "广告", null));
        verify(reportRepository, never()).insert(any());
    }

    @Test
    void create_onNonPublishedPost_rejected() {
        // 未公开内容（草稿/审核中/已下架）不对访客暴露，举报无意义且可被用来刷他人违规次数
        TravelPost pending = publishedPost();
        pending.setStatus(TravelPost.STATUS_PENDING_REVIEW);
        pending.setPublishedAt(null);
        when(postRepository.selectById(1L)).thenReturn(pending);

        assertThrows(IllegalArgumentException.class,
                () -> service.create("u1", "POST", 1L, "广告", null));
        verify(reportRepository, never()).insert(any());
    }

    @Test
    void pending_nonAdmin_forbidden() {
        doThrow(new ForbiddenException("该操作需要管理员权限"))
                .when(communityUserService).requirePermission(any(), eq(AdminPermission.CONTENT_REVIEW));
        assertThrows(ForbiddenException.class, () -> service.pending("u1", 1, 20));
    }

    @Test
    void handleResolve_hidesPublishedPost() {
        ContentReport report = new ContentReport();
        report.setId(1L);
        report.setTargetType(ContentReport.TARGET_POST);
        report.setTargetId(1L);
        report.setStatus(ContentReport.STATUS_PENDING);
        when(reportRepository.selectById(1L)).thenReturn(report);
        when(postRepository.selectById(1L)).thenReturn(publishedPost());

        service.handle("admin", 1L, "RESOLVE", null);

        ArgumentCaptor<ContentReport> captor = ArgumentCaptor.forClass(ContentReport.class);
        verify(reportRepository).updateById(captor.capture());
        assertEquals(ContentReport.STATUS_RESOLVED, captor.getValue().getStatus());
        assertEquals("admin", captor.getValue().getHandledBy());
        ArgumentCaptor<TravelPost> postCaptor = ArgumentCaptor.forClass(TravelPost.class);
        verify(postRepository).updateById(postCaptor.capture());
        assertEquals(TravelPost.STATUS_HIDDEN, postCaptor.getValue().getStatus());
    }

    @Test
    void handleDismiss_marksDismissed_noTargetAction() {
        ContentReport report = new ContentReport();
        report.setId(2L);
        report.setTargetType(ContentReport.TARGET_COMMENT);
        report.setTargetId(1L);
        report.setStatus(ContentReport.STATUS_PENDING);
        when(reportRepository.selectById(2L)).thenReturn(report);

        service.handle("admin", 2L, "DISMISS", null);

        ArgumentCaptor<ContentReport> captor = ArgumentCaptor.forClass(ContentReport.class);
        verify(reportRepository).updateById(captor.capture());
        assertEquals(ContentReport.STATUS_DISMISSED, captor.getValue().getStatus());
        verify(commentRepository, never()).updateById(any());
    }

    /* ================= 阶段四任务 4：举报历史（管理后台） ================= */

    @Test
    void history_requiresAdmin_andReturnsHandledRows() {
        doThrow(new ForbiddenException("该操作需要管理员权限"))
                .when(communityUserService).requirePermission(anyString(), eq(AdminPermission.CONTENT_REVIEW));

        assertThrows(ForbiddenException.class, () -> service.history("u1", 1, 20));
    }

    @Test
    void history_returnsResolvedAndDismissed_withHandler() {
        ContentReport resolved = new ContentReport();
        resolved.setId(10L);
        resolved.setTargetType(ContentReport.TARGET_POST);
        resolved.setTargetId(1L);
        resolved.setReason("广告");
        resolved.setReporterId("u1");
        resolved.setStatus(ContentReport.STATUS_RESOLVED);
        resolved.setHandledBy("admin");
        resolved.setHandledAt(LocalDateTime.now());
        when(reportRepository.selectCount(any())).thenReturn(1L);
        when(reportRepository.selectList(any())).thenReturn(java.util.List.of(resolved));
        when(postRepository.selectBatchIds(any())).thenReturn(java.util.List.of(publishedPost()));

        var page = service.history("admin", 1, 20);

        assertEquals(1L, page.getTotal());
        assertEquals(1, page.getItems().size());
        assertEquals(ContentReport.STATUS_RESOLVED, page.getItems().get(0).getStatus());
        assertNotNull(page.getItems().get(0).getHandledAt());
        // 处理人昵称解析兜底（mock 空 map → 显示 id）
        assertEquals("admin", page.getItems().get(0).getHandledBy());
    }
}
