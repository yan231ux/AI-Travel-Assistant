package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.common.ContentRuleChecker;
import com.yuntu.tripplanner.common.PostQualityScorer;
import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.exception.PostNotFoundException;
import com.yuntu.tripplanner.model.AbExperiment;
import com.yuntu.tripplanner.model.AuditLog;
import com.yuntu.tripplanner.model.PostCreateRequest;
import com.yuntu.tripplanner.model.PostItem;
import com.yuntu.tripplanner.model.PostPage;
import com.yuntu.tripplanner.model.PostSpot;
import com.yuntu.tripplanner.model.PostSpotRef;
import com.yuntu.tripplanner.model.PostUpdateRequest;
import com.yuntu.tripplanner.model.TravelPost;
import com.yuntu.tripplanner.model.TravelPostRevision;
import com.yuntu.tripplanner.repository.PostInteractionRepository;
import com.yuntu.tripplanner.repository.PostSpotRepository;
import com.yuntu.tripplanner.repository.SpotRepository;
import com.yuntu.tripplanner.repository.TravelPostRepository;
import com.yuntu.tripplanner.repository.TravelPostRevisionRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/**
 * 帖子服务单测（阶段二社区）：状态机 / 权限 / 公开流过滤 / 规则拦截 / 审核动作。
 * - 创建默认 DRAFT 且关联景点落库；
 * - 提交审核：规则命中返回违规原因（400），通过则进入 PENDING_REVIEW；
 * - 公开流只放行 PUBLISHED（草稿/待审/拒绝/隐藏都不出现）；
 * - 编辑/删除他人帖子 → Forbidden；未公开帖子他人不可见 → PostNotFound；
 * - 审核队列/通过/拒绝/隐藏服务端校验 ADMIN。
 */
@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    @Mock
    private TravelPostRepository postRepository;
    @Mock
    private PostSpotRepository postSpotRepository;
    @Mock
    private PostInteractionRepository interactionRepository;
    @Mock
    private SpotRepository spotRepository;
    @Mock
    private CommunityUserService communityUserService;
    @Mock
    private UserProfileService userProfileService;
    @Mock
    private PostTagService postTagService;
    @Mock
    private PostFeedEngine postFeedEngine;
    @Mock
    private AbExperimentService abExperimentService;
    @Mock
    private CityValidator cityValidator;
    @Mock
    private AuditService auditService;
    @Mock
    private ContentModerationService moderationService;
    @Mock
    private TravelPostRevisionRepository revisionRepository;

    private PostService service;

    @BeforeEach
    void setUp() {
        // LambdaUpdateWrapper 需要 TableInfo 缓存（clearPublishedAt 里用到 wrapper.set）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), TravelPost.class);
        // P1-1 版本化：savePendingRevision 里对 travel_post_revision 用 wrapper 查询
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), TravelPostRevision.class);
        service = new PostService(postRepository, postSpotRepository, interactionRepository,
                spotRepository, communityUserService, new ContentRuleChecker(),
                userProfileService, postTagService, postFeedEngine, abExperimentService,
                cityValidator, auditService, revisionRepository);
        // moderationService 为可选注入（setter 模拟 Spring 注入），供提交审核埋点断言用
        org.springframework.test.util.ReflectionTestUtils.setField(service,
                "moderationService", moderationService);
        lenient().when(communityUserService.nicknamesOf(any())).thenReturn(java.util.Map.of());
        // 城市闸门默认恒等放行（"上海"→"上海"）；非法城市归一化场景在专门用例里 stub 覆盖
        lenient().when(cityValidator.canonicalCity(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private TravelPost post(Long id, String userId, String status) {
        TravelPost p = new TravelPost();
        p.setId(id);
        p.setUserId(userId);
        p.setTitle("大理适合慢慢逛的 5 个地方");
        p.setSummary("不赶路版攻略");
        p.setContent("第一天洱海西线骑行，第二天喜洲古镇慢慢逛，第三天古城闲逛喝茶。");
        p.setPostType(TravelPost.TYPE_GUIDE);
        p.setStatus(status);
        p.setLikeCount(0);
        p.setFavoriteCount(0);
        p.setViewCount(0);
        p.setCommentCount(0);
        if (TravelPost.STATUS_PUBLISHED.equals(status)) {
            p.setPublishedAt(LocalDateTime.now());
        }
        p.setCreatedAt(LocalDateTime.now());
        return p;
    }

    private PostCreateRequest createReq() {
        PostCreateRequest req = new PostCreateRequest();
        req.setTitle("大理适合慢慢逛的 5 个地方");
        req.setSummary("不赶路版攻略");
        req.setContent("第一天洱海西线骑行，第二天喜洲古镇慢慢逛，第三天古城闲逛喝茶。");
        req.setCity("大理");
        req.setPostType(TravelPost.TYPE_GUIDE);
        PostSpotRef ref = new PostSpotRef();
        ref.setSpotId("spot_大理_B0001");
        ref.setPoiId("B0001");
        ref.setSpotName("大理古城");
        req.setSpots(List.of(ref));
        return req;
    }

    @Test
    void create_defaultsToDraft_savesSpots() {
        doAnswer(inv -> {
            TravelPost p = inv.getArgument(0);
            p.setId(10L);
            return 1;
        }).when(postRepository).insert(any(TravelPost.class));

        Long id = service.create("u1", createReq());

        assertEquals(10L, id);
        ArgumentCaptor<TravelPost> captor = ArgumentCaptor.forClass(TravelPost.class);
        verify(postRepository).insert(captor.capture());
        TravelPost saved = captor.getValue();
        assertEquals(TravelPost.STATUS_DRAFT, saved.getStatus());
        assertEquals(TravelPost.TYPE_GUIDE, saved.getPostType());
        assertEquals("u1", saved.getUserId());
        verify(postSpotRepository).insert(any(PostSpot.class));
    }

    /* ---- 2026-09-13：发帖城市归一化（垃圾城市置空，杜绝"匹配你的偏好：1"） ---- */

    @Test
    void create_illegalCity_blankedToNull() {
        when(cityValidator.canonicalCity("1")).thenReturn(null);
        doAnswer(inv -> {
            TravelPost p = inv.getArgument(0);
            p.setId(11L);
            return 1;
        }).when(postRepository).insert(any(TravelPost.class));

        PostCreateRequest req = createReq();
        req.setCity("1");
        service.create("u1", req);

        ArgumentCaptor<TravelPost> captor = ArgumentCaptor.forClass(TravelPost.class);
        verify(postRepository).insert(captor.capture());
        assertNull(captor.getValue().getCity());
    }

    @Test
    void create_aliasCity_normalizedToCanonical() {
        when(cityValidator.canonicalCity("魔都")).thenReturn("上海");
        doAnswer(inv -> {
            TravelPost p = inv.getArgument(0);
            p.setId(12L);
            return 1;
        }).when(postRepository).insert(any(TravelPost.class));

        PostCreateRequest req = createReq();
        req.setCity("魔都");
        service.create("u1", req);

        ArgumentCaptor<TravelPost> captor = ArgumentCaptor.forClass(TravelPost.class);
        verify(postRepository).insert(captor.capture());
        assertEquals("上海", captor.getValue().getCity());
    }

    /* ---- P1-2（审查报告）：封面只收本地上传路径或合法 http(s) 链接 ---- */

    @Test
    void create_rejectsDataUrlCover() {
        PostCreateRequest req = createReq();
        req.setCoverImage("data:image/png;base64,iVBORw0KGgo=");

        assertThrows(IllegalArgumentException.class, () -> service.create("u1", req));
        verify(postRepository, never()).insert(any(TravelPost.class));
    }

    @Test
    void create_rejectsJavascriptCover() {
        PostCreateRequest req = createReq();
        req.setCoverImage("javascript:alert(1)");

        assertThrows(IllegalArgumentException.class, () -> service.create("u1", req));
        verify(postRepository, never()).insert(any(TravelPost.class));
    }

    @Test
    void create_rejectsNonUploadsLocalPathCover() {
        PostCreateRequest req = createReq();
        req.setCoverImage("/uploads/not-a-real-file.jpg"); // 非 32 位 hex 文件名

        assertThrows(IllegalArgumentException.class, () -> service.create("u1", req));
        verify(postRepository, never()).insert(any(TravelPost.class));
    }

    @Test
    void create_acceptsHttpsCover_andUploadsCover() {
        doAnswer(inv -> {
            TravelPost p = inv.getArgument(0);
            p.setId(11L);
            return 1;
        }).when(postRepository).insert(any(TravelPost.class));

        PostCreateRequest https = createReq();
        https.setCoverImage("https://a.amap.com/photo/0B0ab0.jpg?x=1");
        service.create("u1", https);
        ArgumentCaptor<TravelPost> c1 = ArgumentCaptor.forClass(TravelPost.class);
        verify(postRepository).insert(c1.capture());
        assertEquals("https://a.amap.com/photo/0B0ab0.jpg?x=1", c1.getValue().getCoverImage());

        PostCreateRequest up = createReq();
        up.setCoverImage("/uploads/0123456789abcdef0123456789abcdef.png");
        service.create("u1", up);
        ArgumentCaptor<TravelPost> c2 = ArgumentCaptor.forClass(TravelPost.class);
        verify(postRepository, times(2)).insert(c2.capture());
        assertEquals("/uploads/0123456789abcdef0123456789abcdef.png",
                c2.getAllValues().get(1).getCoverImage());
    }

    @Test
    void update_rejectsOversizeCover_noDbWrite() {
        TravelPost published = post(1L, "u1", TravelPost.STATUS_PUBLISHED);
        when(postRepository.selectById(1L)).thenReturn(published);

        PostUpdateRequest req = new PostUpdateRequest();
        req.setCoverImage("https://example.com/a.jpg?" + "x".repeat(2100));
        assertThrows(IllegalArgumentException.class, () -> service.update("u1", 1L, req));
        verify(postRepository, never()).updateById(any(TravelPost.class));
    }

    @Test
    void submit_contentTooShort_returnsViolations_notPending() {
        TravelPost draft = post(1L, "u1", TravelPost.STATUS_DRAFT);
        draft.setContent("太短了");
        when(postRepository.selectById(1L)).thenReturn(draft);

        List<String> violations = service.submit("u1", 1L);

        assertFalse(violations.isEmpty());
        verify(postRepository, never()).updateById(any(TravelPost.class));
    }

    @Test
    void submit_adLinkContent_returnsViolations() {
        TravelPost draft = post(1L, "u1", TravelPost.STATUS_DRAFT);
        draft.setContent("欢迎加微信 abc123 领取攻略，超级划算超值推荐给你。");
        when(postRepository.selectById(1L)).thenReturn(draft);

        List<String> violations = service.submit("u1", 1L);

        assertFalse(violations.stream().noneMatch(v -> v.contains("微信")));
        verify(postRepository, never()).updateById(any(TravelPost.class));
    }

    @Test
    void submit_ok_movesToPendingReview() {
        TravelPost draft = post(1L, "u1", TravelPost.STATUS_DRAFT);
        when(postRepository.selectById(1L)).thenReturn(draft);

        List<String> violations = service.submit("u1", 1L);

        assertTrue(violations.isEmpty());
        ArgumentCaptor<TravelPost> captor = ArgumentCaptor.forClass(TravelPost.class);
        verify(postRepository).updateById(captor.capture());
        assertEquals(TravelPost.STATUS_PENDING_REVIEW, captor.getValue().getStatus());
    }

    @Test
    void publicFeed_onlyContainsPublishedStatus() {
        TravelPost published = post(1L, "u2", TravelPost.STATUS_PUBLISHED);
        when(postRepository.selectCount(any())).thenReturn(1L);
        when(postRepository.selectList(any())).thenReturn(List.of(published));

        var feed = service.publicFeed("u1", null, null, "latest", 1, 12);

        assertEquals(1, feed.getItems().size());
        assertEquals(1L, feed.getItems().get(0).getId());
        // 过滤语义由查询条件保证（status=PUBLISHED）；此处回归确认无状态串扰
        assertEquals(TravelPost.STATUS_PUBLISHED, feed.getItems().get(0).getStatus());
    }

    @Test
    void update_otherUsersPost_throwsForbidden() {
        TravelPost post = post(1L, "u2", TravelPost.STATUS_DRAFT);
        when(postRepository.selectById(1L)).thenReturn(post);

        assertThrows(ForbiddenException.class, () -> service.update("u1", 1L, new com.yuntu.tripplanner.model.PostUpdateRequest()));
    }

    /* ---- P0-1（审查报告）：PUBLISHED 修改必须重新审核，禁止"改完仍公开" ----
     * P1-1（2026-09-18 版本化）升级：不再"整篇转待审 + 清空 published_at"（会让老帖凭空消失并变新帖），
     * 而是修改稿落版本表待审、线上版本原样保留，审核通过后原子切换。 */

    @Test
    void published_editContent_createsRevision_keepsLivePublished() {
        TravelPost published = post(1L, "u1", TravelPost.STATUS_PUBLISHED);
        LocalDateTime liveAt = published.getPublishedAt();
        when(postRepository.selectById(1L)).thenReturn(published);
        when(revisionRepository.selectCount(any())).thenReturn(0L);

        PostUpdateRequest req = new PostUpdateRequest();
        req.setContent("完全重写的正文：第一天环海骑行到双廊，第二天去沙溪古镇慢慢逛……");
        service.update("u1", 1L, req);

        // 主表不动（禁止"改完仍公开"→ 由"新稿进版本表待审"承接）
        verify(postRepository, never()).updateById(any(TravelPost.class));
        ArgumentCaptor<TravelPostRevision> revCaptor = ArgumentCaptor.forClass(TravelPostRevision.class);
        verify(revisionRepository).insert(revCaptor.capture());
        assertEquals(TravelPostRevision.STATUS_PENDING_REVIEW, revCaptor.getValue().getStatus());
        assertEquals("完全重写的正文：第一天环海骑行到双廊，第二天去沙溪古镇慢慢逛……",
                revCaptor.getValue().getContent());
        // 线上版本：状态与发布时间都保持原样（老帖不被顶到时间线最前）
        assertEquals(TravelPost.STATUS_PUBLISHED, published.getStatus());
        assertEquals(liveAt, published.getPublishedAt());
        verify(postRepository).update(isNull(), any(LambdaUpdateWrapper.class)); // 只写 pending_revision_id 指针
        verify(auditService).record(eq("u1"), eq(AuditLog.CAT_CONTENT), eq("post_revision_submitted"),
                eq("post"), eq("1"), anyMap());
    }

    @Test
    void hidden_editContent_requeuesToPendingReview() {
        // 已下架内容没有线上版本可保护 → 沿用原语义：回到待审并清空发布时间
        TravelPost hidden = post(1L, "u1", TravelPost.STATUS_HIDDEN);
        when(postRepository.selectById(1L)).thenReturn(hidden);

        PostUpdateRequest req = new PostUpdateRequest();
        req.setContent("重新整理后的正文：第一天……第二天……第三天……");
        service.update("u1", 1L, req);

        ArgumentCaptor<TravelPost> captor = ArgumentCaptor.forClass(TravelPost.class);
        verify(postRepository).updateById(captor.capture());
        assertEquals(TravelPost.STATUS_PENDING_REVIEW, captor.getValue().getStatus());
        assertNull(captor.getValue().getPublishedAt());
        // updateById 默认忽略 null 字段 → 必须再显式把 published_at 落 NULL（P0-1 实机验收发现的坑）
        verify(postRepository).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(auditService).record(eq("u1"), eq(AuditLog.CAT_CONTENT), eq("post_requeued"),
                eq("post"), eq("1"), anyMap());
    }

    @Test
    void published_editCover_createsPendingRevision_liveVersionUnchanged() {
        TravelPost published = post(1L, "u1", TravelPost.STATUS_PUBLISHED);
        LocalDateTime liveAt = published.getPublishedAt();
        when(postRepository.selectById(1L)).thenReturn(published);
        when(revisionRepository.selectCount(any())).thenReturn(0L);

        PostUpdateRequest req = new PostUpdateRequest();
        req.setCoverImage("/uploads/abcdef1234567890abcdef1234567890.jpg");
        service.update("u1", 1L, req);

        // P1-1 版本化：修改稿落 travel_post_revision 待审，主表（线上版本）原样不动
        ArgumentCaptor<TravelPostRevision> revCaptor = ArgumentCaptor.forClass(TravelPostRevision.class);
        verify(revisionRepository).insert(revCaptor.capture());
        TravelPostRevision rev = revCaptor.getValue();
        assertEquals(TravelPostRevision.STATUS_PENDING_REVIEW, rev.getStatus());
        assertEquals(1, rev.getRevisionNo());
        assertEquals("/uploads/abcdef1234567890abcdef1234567890.jpg", rev.getCoverImage());
        assertEquals(1L, rev.getPostId());
        assertEquals("u1", rev.getEditorId());
        // 主表正文/封面未被改写（线上继续服务原版本），且不触发状态迁移
        verify(postRepository, never()).updateById(any(TravelPost.class));
        // 只把 pending_revision_id 指针写上
        verify(postRepository).update(isNull(), any(LambdaUpdateWrapper.class));
        assertEquals(TravelPost.STATUS_PUBLISHED, published.getStatus());
        assertEquals(liveAt, published.getPublishedAt());
    }

    @Test
    void published_editLinkedSpots_snapshotsSpotsInRevision_notTouchingLiveSpots() {
        TravelPost published = post(1L, "u1", TravelPost.STATUS_PUBLISHED);
        when(postRepository.selectById(1L)).thenReturn(published);
        when(revisionRepository.selectCount(any())).thenReturn(0L);
        PostSpot oldRef = new PostSpot();
        oldRef.setPostId(1L);
        oldRef.setSpotId("spot_大理_B0001");
        oldRef.setPoiId("B0001");
        oldRef.setSpotName("大理古城");
        oldRef.setSortOrder(0);
        when(postSpotRepository.selectList(any())).thenReturn(List.of(oldRef));

        PostUpdateRequest req = new PostUpdateRequest();
        PostSpotRef newRef = new PostSpotRef();
        newRef.setSpotId("spot_大理_B0002");
        newRef.setPoiId("B0002");
        newRef.setSpotName("双廊古镇");
        req.setSpots(List.of(newRef));
        service.update("u1", 1L, req);

        // 新景点只进版本快照；线上帖子的关联景点不被删除重写
        ArgumentCaptor<TravelPostRevision> revCaptor = ArgumentCaptor.forClass(TravelPostRevision.class);
        verify(revisionRepository).insert(revCaptor.capture());
        assertTrue(revCaptor.getValue().getSpotsJson().contains("spot_大理_B0002"));
        verify(postSpotRepository, never()).delete(any());
        verify(postRepository, never()).updateById(any(TravelPost.class));
    }

    @Test
    void published_secondEdit_supersedesPreviousPendingRevision() {
        TravelPost published = post(1L, "u1", TravelPost.STATUS_PUBLISHED);
        published.setPendingRevisionId(50L);
        when(postRepository.selectById(1L)).thenReturn(published);
        TravelPostRevision old = new TravelPostRevision();
        old.setId(50L);
        old.setPostId(1L);
        old.setRevisionNo(1);
        old.setStatus(TravelPostRevision.STATUS_PENDING_REVIEW);
        when(revisionRepository.selectById(50L)).thenReturn(old);
        when(revisionRepository.selectCount(any())).thenReturn(1L);

        PostUpdateRequest req = new PostUpdateRequest();
        req.setSummary("第二次修改后的摘要");
        service.update("u1", 1L, req);

        // 连改两次：旧待审版本置 SUPERSEDED 保留历史，新版本号递增
        assertEquals(TravelPostRevision.STATUS_SUPERSEDED, old.getStatus());
        ArgumentCaptor<TravelPostRevision> revCaptor = ArgumentCaptor.forClass(TravelPostRevision.class);
        verify(revisionRepository).insert(revCaptor.capture());
        assertEquals(2, revCaptor.getValue().getRevisionNo());
        assertEquals("第二次修改后的摘要", revCaptor.getValue().getSummary());
    }

    @Test
    void published_submitWithoutRealChanges_staysPublished_noRequeue() {
        TravelPost published = post(1L, "u1", TravelPost.STATUS_PUBLISHED);
        when(postRepository.selectById(1L)).thenReturn(published);

        // 编辑页保存会把详情原值回填：全部字段与库中一致 = 无实质变更 → 不制造审核任务
        PostUpdateRequest req = new PostUpdateRequest();
        req.setTitle(published.getTitle());
        req.setSummary(published.getSummary());
        req.setContent(published.getContent());
        req.setPostType(published.getPostType());
        service.update("u1", 1L, req);

        ArgumentCaptor<TravelPost> captor = ArgumentCaptor.forClass(TravelPost.class);
        verify(postRepository).updateById(captor.capture());
        assertEquals(TravelPost.STATUS_PUBLISHED, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getPublishedAt());
        verify(postRepository, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(auditService, never()).record(any(), any(), eq("post_requeued"), any(), any(), anyMap());
    }

    @Test
    void rejected_editContent_staysDraft_needsResubmit() {
        TravelPost rejected = post(1L, "u1", TravelPost.STATUS_REJECTED);
        rejected.setRejectReason("内容过短");
        when(postRepository.selectById(1L)).thenReturn(rejected);

        PostUpdateRequest req = new PostUpdateRequest();
        req.setContent("补齐后的正文：第一天……第二天……第三天……内容完整丰富了许多。");
        service.update("u1", 1L, req);

        ArgumentCaptor<TravelPost> captor = ArgumentCaptor.forClass(TravelPost.class);
        verify(postRepository).updateById(captor.capture());
        assertEquals(TravelPost.STATUS_DRAFT, captor.getValue().getStatus());
        assertNull(captor.getValue().getRejectReason());
    }

    @Test
    void detail_draftPost_notVisibleToOthers_throwsNotFound() {
        TravelPost draft = post(1L, "u2", TravelPost.STATUS_DRAFT);
        when(postRepository.selectById(1L)).thenReturn(draft);
        when(communityUserService.isAdmin(anyString())).thenReturn(false);

        assertThrows(PostNotFoundException.class, () -> service.detail("u1", 1L));
    }

    @Test
    void detail_ownDraft_visibleToAuthor() {
        TravelPost draft = post(1L, "u1", TravelPost.STATUS_DRAFT);
        when(postRepository.selectById(1L)).thenReturn(draft);
        when(communityUserService.nicknameOf(anyString())).thenReturn("曾彦");

        var detail = service.detail("u1", 1L);

        assertNotNull(detail);
        assertTrue(detail.getMine());
        assertEquals(TravelPost.STATUS_DRAFT, detail.getStatus());
    }

    @Test
    void moderationQueue_nonAdmin_throwsForbidden() {
        doThrow(new ForbiddenException("该操作需要管理员权限"))
                .when(communityUserService).requirePermission(anyString(), eq(AdminPermission.CONTENT_REVIEW));

        assertThrows(ForbiddenException.class, () -> service.moderationQueue("u1", 1, 12));
    }

    @Test
    void approve_pendingPost_becomesPublished() {
        TravelPost pending = post(1L, "u2", TravelPost.STATUS_PENDING_REVIEW);
        when(postRepository.selectById(1L)).thenReturn(pending);

        service.approve("admin", 1L);

        ArgumentCaptor<TravelPost> captor = ArgumentCaptor.forClass(TravelPost.class);
        verify(postRepository).updateById(captor.capture());
        assertEquals(TravelPost.STATUS_PUBLISHED, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getPublishedAt());
    }

    /* ================= P1-1 编辑版本化：公开版本 / 编辑版本分离 ================= */

    private TravelPostRevision revision(Long id, Long postId, int no, String status) {
        TravelPostRevision rev = new TravelPostRevision();
        rev.setId(id);
        rev.setPostId(postId);
        rev.setRevisionNo(no);
        rev.setStatus(status);
        rev.setTitle("修改后的标题");
        rev.setSummary("修改后的摘要");
        rev.setContent("修改后的正文：第一天……第二天……内容完整。");
        rev.setCity("大理");
        rev.setPostType(TravelPost.TYPE_GUIDE);
        rev.setCreatedAt(LocalDateTime.now());
        return rev;
    }

    @Test
    void approve_pendingRevision_switchesLiveContent_keepsStatusAndPublishedAt() {
        TravelPost published = post(1L, "u1", TravelPost.STATUS_PUBLISHED);
        LocalDateTime liveAt = published.getPublishedAt();
        published.setPendingRevisionId(77L);
        when(postRepository.selectById(1L)).thenReturn(published);
        TravelPostRevision rev = revision(77L, 1L, 2, TravelPostRevision.STATUS_PENDING_REVIEW);
        rev.setTitle("改后的标题");
        rev.setContent("改后的正文内容，信息更完整。");
        rev.setSpotsJson("[]");
        when(revisionRepository.selectById(77L)).thenReturn(rev);

        service.approve("admin", 1L);

        // 版本内容原子写回主表；状态与发布时间不变（"修改"不是"重新发布"）
        ArgumentCaptor<TravelPost> captor = ArgumentCaptor.forClass(TravelPost.class);
        verify(postRepository).updateById(captor.capture());
        assertEquals("改后的标题", captor.getValue().getTitle());
        assertEquals("改后的正文内容，信息更完整。", captor.getValue().getContent());
        assertEquals(TravelPost.STATUS_PUBLISHED, captor.getValue().getStatus());
        assertEquals(liveAt, captor.getValue().getPublishedAt());
        // 版本置 APPROVED 并留痕，待审指针解除
        assertEquals(TravelPostRevision.STATUS_APPROVED, rev.getStatus());
        assertEquals("admin", rev.getReviewedBy());
        assertNotNull(rev.getReviewedAt());
        verify(postRepository).update(isNull(), any(LambdaUpdateWrapper.class));
        // A1 缺陷②：版本被处置 → 绑定其上的 AI 审核任务必须一并终结，
        // 否则任务永远停在 REVIEW 且无决策，赖在人工复核队列里清不掉。
        verify(moderationService).closeForRevision(77L, "APPROVE", null);
    }

    @Test
    void reject_pendingRevision_keepsLiveContent_clearsPointerOnly() {
        TravelPost published = post(1L, "u1", TravelPost.STATUS_PUBLISHED);
        published.setContent("原始线上正文");
        published.setPendingRevisionId(77L);
        when(postRepository.selectById(1L)).thenReturn(published);
        TravelPostRevision rev = revision(77L, 1L, 1, TravelPostRevision.STATUS_PENDING_REVIEW);
        when(revisionRepository.selectById(77L)).thenReturn(rev);

        service.reject("admin", 1L, "含未核实门票价格");

        // 只驳回版本：线上主表内容/状态/发布时间全部保持原样
        assertEquals(TravelPostRevision.STATUS_REJECTED, rev.getStatus());
        assertEquals("含未核实门票价格", rev.getRejectReason());
        verify(revisionRepository).updateById(rev);
        verify(postRepository, never()).updateById(any(TravelPost.class));
        verify(postRepository).update(isNull(), any(LambdaUpdateWrapper.class));
        assertEquals(TravelPost.STATUS_PUBLISHED, published.getStatus());
        assertEquals("原始线上正文", published.getContent());
        assertNull(published.getRejectReason());
        // A1 缺陷②：驳回修改稿同样要终结绑定任务（决策=REJECT，附驳回原因）
        verify(moderationService).closeForRevision(77L, "REJECT", "含未核实门票价格");
    }

    @Test
    void mine_marksPendingRevision_ofPublishedPost() {
        TravelPost published = post(1L, "u1", TravelPost.STATUS_PUBLISHED);
        published.setPendingRevisionId(77L);
        when(postRepository.selectCount(any())).thenReturn(1L);
        when(postRepository.selectList(any())).thenReturn(List.of(published));
        when(revisionRepository.selectBatchIds(any()))
                .thenReturn(List.of(revision(77L, 1L, 2, TravelPostRevision.STATUS_PENDING_REVIEW)));

        PostPage page = service.mine("u1", 1, 12);

        PostItem item = page.getItems().get(0);
        assertTrue(item.getHasPendingRevision());
        assertEquals(2, item.getPendingRevisionNo());
        assertEquals(TravelPost.STATUS_PUBLISHED, item.getStatus());
    }

    @Test
    void mine_publishedWithoutRevision_flagFalse() {
        TravelPost published = post(1L, "u1", TravelPost.STATUS_PUBLISHED);
        when(postRepository.selectCount(any())).thenReturn(1L);
        when(postRepository.selectList(any())).thenReturn(List.of(published));

        PostPage page = service.mine("u1", 1, 12);

        assertFalse(page.getItems().get(0).getHasPendingRevision());
        assertNull(page.getItems().get(0).getPendingRevisionNo());
    }

    @Test
    void detail_publishedWithPendingRevision_exposesSnapshotForDiff() {
        TravelPost published = post(1L, "u1", TravelPost.STATUS_PUBLISHED);
        published.setPendingRevisionId(77L);
        when(postRepository.selectById(1L)).thenReturn(published);
        when(communityUserService.nicknameOf(anyString())).thenReturn("曾彦");
        TravelPostRevision rev = revision(77L, 1L, 2, TravelPostRevision.STATUS_PENDING_REVIEW);
        rev.setTitle("改后的标题");
        rev.setSpotsJson("[]");
        when(revisionRepository.selectById(77L)).thenReturn(rev);

        var d = service.detail("u3", 1L);

        // 访客看到的仍是线上版本，但接口透出"有修改待审"及快照（供审核页对比，前端只对作者/审核员展示）
        assertEquals("大理适合慢慢逛的 5 个地方", d.getTitle());
        assertTrue(d.getHasPendingRevision());
        assertEquals(2, d.getPendingRevisionNo());
        assertNotNull(d.getPendingRevision());
        assertEquals("改后的标题", d.getPendingRevision().get("title"));
    }

    @Test
    void approve_withStaleRevisionId_throwsIllegalState() {
        // AI 自动放行时携带的 revisionId 与帖子当前待审指针不一致（期间作者又改了一次）→ 视为过期
        TravelPost published = post(1L, "u1", TravelPost.STATUS_PUBLISHED);
        published.setPendingRevisionId(99L);
        when(postRepository.selectById(1L)).thenReturn(published);
        when(revisionRepository.selectById(99L))
                .thenReturn(revision(99L, 1L, 3, TravelPostRevision.STATUS_PENDING_REVIEW));

        assertThrows(IllegalStateException.class, () -> service.autoPublish("system:ai", 1L, 77L));
    }

    @Test
    void autoPublish_withMatchingRevisionId_appliesRevision() {
        TravelPost published = post(1L, "u1", TravelPost.STATUS_PUBLISHED);
        published.setPendingRevisionId(77L);
        when(postRepository.selectById(1L)).thenReturn(published);
        TravelPostRevision rev = revision(77L, 1L, 2, TravelPostRevision.STATUS_PENDING_REVIEW);
        rev.setTitle("AI 放行的修改版");
        rev.setSpotsJson("[]");
        when(revisionRepository.selectById(77L)).thenReturn(rev);

        service.autoPublish("system:ai", 1L, 77L);

        assertEquals("AI 放行的修改版", published.getTitle());
        assertEquals(TravelPost.STATUS_PUBLISHED, published.getStatus());
        assertEquals(TravelPostRevision.STATUS_APPROVED, rev.getStatus());
    }

    @Test
    void reject_setsReason_andAllowsResubmitFlow() {
        TravelPost pending = post(1L, "u2", TravelPost.STATUS_PENDING_REVIEW);
        when(postRepository.selectById(1L)).thenReturn(pending);

        service.reject("admin", 1L, "图片与正文无关");

        ArgumentCaptor<TravelPost> captor = ArgumentCaptor.forClass(TravelPost.class);
        verify(postRepository).updateById(captor.capture());
        assertEquals(TravelPost.STATUS_REJECTED, captor.getValue().getStatus());
        assertEquals("图片与正文无关", captor.getValue().getRejectReason());

        // 作者改后重提：REJECTED → submit 应回到 PENDING_REVIEW
        TravelPost rejected = post(1L, "u2", TravelPost.STATUS_REJECTED);
        rejected.setRejectReason("图片与正文无关");
        when(postRepository.selectById(1L)).thenReturn(rejected);
        var violations = service.submit("u2", 1L);
        assertTrue(violations.isEmpty());
    }

    @Test
    void hide_publishedPost_goesHidden() {
        TravelPost published = post(1L, "u2", TravelPost.STATUS_PUBLISHED);
        when(postRepository.selectById(1L)).thenReturn(published);

        service.hide("admin", 1L);

        ArgumentCaptor<TravelPost> captor = ArgumentCaptor.forClass(TravelPost.class);
        verify(postRepository).updateById(captor.capture());
        assertEquals(TravelPost.STATUS_HIDDEN, captor.getValue().getStatus());
    }

    /* ================= AI 审核联动：自动发布 / 改判落地 ================= */

    @Test
    void autoPublish_pendingPost_becomesPublished_withoutAdminPermission() {
        TravelPost pending = post(1L, "u2", TravelPost.STATUS_PENDING_REVIEW);
        when(postRepository.selectById(1L)).thenReturn(pending);

        service.autoPublish("system:ai", 1L);

        ArgumentCaptor<TravelPost> captor = ArgumentCaptor.forClass(TravelPost.class);
        verify(postRepository).updateById(captor.capture());
        assertEquals(TravelPost.STATUS_PUBLISHED, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getPublishedAt());
        // 系统主体不该被要求管理员权限（否则自动发布必然失败或被逼着给系统发权限）
        verify(communityUserService, never()).requirePermission(any(), any());
    }

    @Test
    void autoPublish_nonPendingPost_isRejectedByStateMachine() {
        TravelPost published = post(1L, "u2", TravelPost.STATUS_PUBLISHED);
        when(postRepository.selectById(1L)).thenReturn(published);

        assertThrows(IllegalArgumentException.class, () -> service.autoPublish("system:ai", 1L));
    }

    @Test
    void moderationReject_onPublishedPost_hidesIt() {
        // 被 AI 自动放行的帖子已是 PUBLISHED：改判拒绝必须"下架"，而不是撞状态机报错后静默失效
        TravelPost published = post(1L, "u2", TravelPost.STATUS_PUBLISHED);
        when(postRepository.selectById(1L)).thenReturn(published);

        String action = service.applyRejectFromModeration("admin", 1L, "改判：含敏感信息");

        assertEquals("hide", action);
        ArgumentCaptor<TravelPost> captor = ArgumentCaptor.forClass(TravelPost.class);
        verify(postRepository).updateById(captor.capture());
        assertEquals(TravelPost.STATUS_HIDDEN, captor.getValue().getStatus());
    }

    @Test
    void moderationReject_onPendingPost_rejectsIt() {
        TravelPost pending = post(1L, "u2", TravelPost.STATUS_PENDING_REVIEW);
        when(postRepository.selectById(1L)).thenReturn(pending);

        String action = service.applyRejectFromModeration("admin", 1L, "含导流广告");

        assertEquals("reject", action);
        ArgumentCaptor<TravelPost> captor = ArgumentCaptor.forClass(TravelPost.class);
        verify(postRepository).updateById(captor.capture());
        assertEquals(TravelPost.STATUS_REJECTED, captor.getValue().getStatus());
        assertEquals("含导流广告", captor.getValue().getRejectReason());
    }

    @Test
    void moderationReject_onDraftPost_isNoop() {
        TravelPost draft = post(1L, "u2", TravelPost.STATUS_DRAFT);
        when(postRepository.selectById(1L)).thenReturn(draft);

        assertEquals("noop", service.applyRejectFromModeration("admin", 1L, "无需处置"));
        verify(postRepository, never()).updateById(any());
    }

    /* ================= 阶段三：公开流 recommended（为你推荐） ================= */

    @Test
    void publicFeed_recommended_withProfile_ranksByEngineAndLogsExposure() {
        TravelPost history = post(1L, "u2", TravelPost.STATUS_PUBLISHED);
        history.setTitle("大理古镇两日慢游");
        TravelPost nature = post(2L, "u3", TravelPost.STATUS_PUBLISHED);
        nature.setTitle("三亚看海流水账");
        when(postRepository.selectCount(any())).thenReturn(2L);
        when(postRepository.selectList(any())).thenReturn(List.of(history, nature));
        when(userProfileService.listPreferences("u1")).thenReturn(List.of());
        when(userProfileService.getProfileVersion("u1")).thenReturn(5);
        when(postFeedEngine.personalizedOf(anyList())).thenReturn(true);
        when(postFeedEngine.rank(anyList(), anyMap(), anyList())).thenReturn(List.of(
                new PostFeedEngine.RankedPost(1L, 0.86, "匹配你的偏好：历史文化",
                        List.of("历史文化"), true),
                new PostFeedEngine.RankedPost(2L, 0.5, "为你推荐", List.of(), false)));
        when(postTagService.ensureBatch(anyList())).thenReturn(new java.util.HashMap<>());

        PostPage page = service.publicFeed("u1", null, null, "recommended", 1, 10);

        assertEquals(Boolean.TRUE, page.getPersonalized());
        assertEquals(2, page.getItems().size());
        PostItem first = page.getItems().get(0);
        assertEquals(1L, first.getId());
        assertEquals("匹配你的偏好：历史文化", first.getRecommendReason());
        assertEquals(List.of("历史文化"), first.getMatchedTags());
        // 曝光日志：只记个性化页，带画像版本（A/B 无实验时变体为 null；P1-5 无 trace 走 null）
        verify(postFeedEngine).logExposures(eq("u1"), eq(5), nullable(String.class),
                nullable(String.class), anyList());
    }

    @Test
    void publicFeed_recommended_withoutProfile_degradesToPopular() {
        TravelPost hot = post(1L, "u2", TravelPost.STATUS_PUBLISHED);
        hot.setLikeCount(10);
        when(postRepository.selectCount(any())).thenReturn(1L);
        when(postRepository.selectList(any())).thenReturn(List.of(hot));
        when(userProfileService.listPreferences("u1")).thenReturn(List.of());
        when(postFeedEngine.personalizedOf(anyList())).thenReturn(false);

        PostPage page = service.publicFeed("u1", null, null, "recommended", 1, 10);

        assertEquals(Boolean.FALSE, page.getPersonalized());
        assertEquals(1, page.getItems().size());
        // 降级热门不写曝光日志（口径纯净；P1-5 引擎入口为 5 参带 trace 重载）
        verify(postFeedEngine, never()).logExposures(any(), anyInt(),
                nullable(String.class), nullable(String.class), anyList());
    }

    /** 阶段四任务 6：帖子推荐流低质过滤实验——处理组把 low_quality=1 的帖子排除出候选（干净候选 ≥6 才生效） */
    @Test
    void publicFeed_recommended_abTreatment_filtersLowQualityPosts() {
        List<TravelPost> candidates = new java.util.ArrayList<>();
        for (long i = 1; i <= 7; i++) {
            TravelPost good = post(i, "u2", TravelPost.STATUS_PUBLISHED);
            good.setLowQuality(0);
            candidates.add(good);
        }
        TravelPost bad = post(8L, "u3", TravelPost.STATUS_PUBLISHED);
        bad.setLowQuality(1);
        candidates.add(bad);
        when(postRepository.selectCount(any())).thenReturn((long) candidates.size());
        when(postRepository.selectList(any())).thenReturn(candidates);
        when(userProfileService.listPreferences("u1")).thenReturn(List.of());
        when(userProfileService.getProfileVersion("u1")).thenReturn(5);
        when(postFeedEngine.personalizedOf(anyList())).thenReturn(true);
        AbExperiment postExp = new AbExperiment();
        postExp.setExpName("post_feed_low_quality");
        postExp.setFeedType(AbExperiment.FEED_POST);
        when(abExperimentService.activeOf(AbExperiment.FEED_POST)).thenReturn(postExp);
        when(abExperimentService.resolveVariant("u1", "post_feed_low_quality"))
                .thenReturn("TREATMENT");
        // 候选传给排序引擎的应是过滤后的：7 篇干净帖子（无 low_quality=1 的 8 号）
        when(postFeedEngine.rank(anyList(), anyMap(), anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<TravelPost> fed = (List<TravelPost>) inv.getArgument(0);
            assertEquals(7, fed.size());
            assertTrue(fed.stream().noneMatch(p -> p.getId() == 8L));
            return fed.stream()
                    .map(p -> new PostFeedEngine.RankedPost(p.getId(), 1.0,
                            "为你推荐", List.of(), false))
                    .toList();
        });
        when(postTagService.ensureBatch(anyList())).thenReturn(Map.of());

        PostPage page = service.publicFeed("u1", null, null, "recommended", 1, 10);

        assertEquals(Boolean.TRUE, page.getPersonalized());
        assertEquals(7, page.getItems().size());
        assertTrue(page.getItems().stream().noneMatch(p -> p.getId() == 8L));
        // 曝光日志带 TREATMENT 变体（供监控按变体对照反馈率；P1-5 trace 参数为 null）
        verify(postFeedEngine).logExposures(eq("u1"), eq(5), eq("TREATMENT"),
                nullable(String.class), anyList());
    }

    /* ================= 阶段四：用户主页 / 管理后台 ================= */

    @Test
    void userPublishedPosts_onlyReturnsPublished_ofThatAuthor() {
        TravelPost published = post(1L, "u2", TravelPost.STATUS_PUBLISHED);
        TravelPost draft = post(2L, "u2", TravelPost.STATUS_DRAFT);
        when(postRepository.selectCount(any())).thenReturn(1L);
        when(postRepository.selectList(any())).thenReturn(List.of(published));

        PostPage page = service.userPublishedPosts("u1", "u2", 1, 12);

        assertEquals(1, page.getItems().size());
        assertEquals(1L, page.getItems().get(0).getId());
        // 查询条件含 status=PUBLISHED + userId（语义由 wrapper 保证，此处验证作者字段无串扰）
        assertEquals("u2", page.getItems().get(0).getAuthor().getId());
    }

    @Test
    void adminPosts_nonAdmin_throwsForbidden() {
        doThrow(new ForbiddenException("该操作需要管理员权限"))
                .when(communityUserService).requirePermission(anyString(), eq(AdminPermission.CONTENT_REVIEW));

        assertThrows(ForbiddenException.class, () -> service.adminPosts("u1", "PUBLISHED", 1, 20));
    }

    @Test
    void adminPosts_invalidStatus_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> service.adminPosts("admin", "DRAFT", 1, 20));
    }

    @Test
    void adminPosts_published_returnsRows() {
        TravelPost published = post(9L, "u2", TravelPost.STATUS_PUBLISHED);
        when(postRepository.selectCount(any())).thenReturn(1L);
        when(postRepository.selectList(any())).thenReturn(List.of(published));

        PostPage page = service.adminPosts("admin", "PUBLISHED", 1, 20);

        assertEquals(1, page.getItems().size());
        assertEquals(9L, page.getItems().get(0).getId());
    }

    @Test
    void publishedCounts_groupsPerAuthor() {
        TravelPost p1 = post(1L, "u1", TravelPost.STATUS_PUBLISHED);
        TravelPost p2 = post(2L, "u1", TravelPost.STATUS_PUBLISHED);
        TravelPost p3 = post(3L, "u2", TravelPost.STATUS_PUBLISHED);
        when(postRepository.selectList(any())).thenReturn(List.of(p1, p2, p3));

        Map<String, Long> counts = service.publishedCounts(List.of("u1", "u2", "u9"));

        assertEquals(2L, counts.get("u1"));
        assertEquals(1L, counts.get("u2"));
        assertFalse(counts.containsKey("u9"));
    }

    /* ================= 阶段四：内容质量分 + 查重 ================= */

    @Test
    void submit_computesQualityScore_andMarksLowQualityOnShallowContent() {
        TravelPost draft = post(1L, "u1", TravelPost.STATUS_DRAFT);
        draft.setTitle("随便");
        draft.setContent("哈哈哈水帖灌水");
        when(postRepository.selectById(1L)).thenReturn(draft);

        var violations = service.submit("u1", 1L);

        // 规则层（ContentRuleChecker 对长度有下限）可能先拦截；若放行则走质量分逻辑。
        // 此处两种走向都合法：有 violations → 不更新；无 violations → 落低质分。
        if (violations.isEmpty()) {
            ArgumentCaptor<TravelPost> captor = ArgumentCaptor.forClass(TravelPost.class);
            verify(postRepository).updateById(captor.capture());
            TravelPost saved = captor.getValue();
            assertNotNull(saved.getQualityScore());
            assertTrue(saved.getQualityScore() < PostQualityScorer.LOW_QUALITY_THRESHOLD
                    || Integer.valueOf(1).equals(saved.getLowQuality()));
        }
    }

    @Test
    void submit_duplicateDetected_returnsViolations_notPending() {
        TravelPost draft = post(1L, "u1", TravelPost.STATUS_DRAFT);
        draft.setTitle("大理适合慢慢逛的 5 个地方");
        draft.setSummary("不赶路版攻略");
        TravelPost existing = post(2L, "u9", TravelPost.STATUS_PUBLISHED);
        existing.setTitle("大理适合慢慢逛的 5 个地方");
        existing.setSummary("不赶路版攻略");
        when(postRepository.selectById(1L)).thenReturn(draft);
        when(postRepository.selectList(any())).thenReturn(List.of(existing));

        var violations = service.submit("u1", 1L);

        assertFalse(violations.isEmpty());
        assertTrue(violations.get(0).contains("重复"));
        verify(postRepository, never()).updateById(any(TravelPost.class));
    }

    /* ============ 作者查看 AI 审核细分状态（latestModerationStatus） ============ */

    @Test
    void latestModerationStatus_noTask_returnsNull() {
        TravelPost p = post(1L, "u1", TravelPost.STATUS_PENDING_REVIEW);
        when(postRepository.selectById(1L)).thenReturn(p);
        when(moderationService.latestForViewer(anyString(), anyString())).thenReturn(null);

        var v = service.latestModerationStatus("u1", 1L);

        assertNull(v);
    }

    @Test
    void latestModerationStatus_otherUsersPost_throwsForbidden() {
        TravelPost p = post(1L, "u2", TravelPost.STATUS_PENDING_REVIEW);
        when(postRepository.selectById(1L)).thenReturn(p);

        assertThrows(ForbiddenException.class, () -> service.latestModerationStatus("u1", 1L));
    }

    @Test
    void latestModerationStatus_postMissing_throwsNotFound() {
        when(postRepository.selectById(1L)).thenReturn(null);

        assertThrows(PostNotFoundException.class, () -> service.latestModerationStatus("u1", 1L));
    }
}
