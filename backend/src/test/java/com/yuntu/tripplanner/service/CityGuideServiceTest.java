package com.yuntu.tripplanner.service;

import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.common.GuideMarkdownParser;
import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.model.CityGuide;
import com.yuntu.tripplanner.model.CityGuideRevision;
import com.yuntu.tripplanner.model.CityGuideSpot;
import com.yuntu.tripplanner.model.CityGuideTag;
import com.yuntu.tripplanner.model.RagIndexTask;
import com.yuntu.tripplanner.model.Spot;
import com.yuntu.tripplanner.repository.CityGuideRepository;
import com.yuntu.tripplanner.repository.CityGuideRevisionRepository;
import com.yuntu.tripplanner.repository.CityGuideSpotRepository;
import com.yuntu.tripplanner.repository.CityGuideTagRepository;
import com.yuntu.tripplanner.repository.RagIndexTaskRepository;
import com.yuntu.tripplanner.repository.SpotRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 攻略服务单测（内容运营骨架 + 两段式发布一致性 P0-1/P1）：
 * 草稿创建/提交、发布只置 PUBLISHED 并登记任务（published_revision_id 待 RAG 索引成功才切）、
 * 已发布内容编辑回到待审（旧版本继续服务）、无变化保存跳过、幂等导入判重、
 * 下线/归档作废任务、恢复重建 PENDING 任务、回滚三类场景（放弃待审/取消未生效发布/换回更早版本）。
 */
@ExtendWith(MockitoExtension.class)
class CityGuideServiceTest {

    @Mock
    private CityGuideRepository guideRepository;
    @Mock
    private CityGuideRevisionRepository revisionRepository;
    @Mock
    private CityGuideSpotRepository spotRepository;
    @Mock
    private CityGuideTagRepository tagRepository;
    @Mock
    private RagIndexTaskRepository taskRepository;
    @Mock
    private CommunityUserService communityUserService;
    @Mock
    private AuditService auditService;
    @Mock
    private RagService ragService;
    @Mock
    private GuideIndexingService indexingService;
    @Mock
    private SpotRepository spotMainRepository;

    private CityGuideService service;

    @BeforeEach
    void setUp() {
        service = new CityGuideService(guideRepository, revisionRepository, spotRepository,
                tagRepository, taskRepository, communityUserService, auditService);
        lenient().when(communityUserService.nicknameOf("admin")).thenReturn("管理员");
        // LambdaQueryWrapper/LambdaUpdateWrapper 构造需 TableInfo（生产由 Spring 初始化，单测手动注册）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CityGuide.class);
        TableInfoHelper.initTableInfo(assistant, CityGuideRevision.class);
        TableInfoHelper.initTableInfo(assistant, CityGuideSpot.class);
        TableInfoHelper.initTableInfo(assistant, CityGuideTag.class);
        TableInfoHelper.initTableInfo(assistant, RagIndexTask.class);
        TableInfoHelper.initTableInfo(assistant, Spot.class);
        // mock 的 insert 不自动回填主键：按版本号回填（create/appendRevision 依赖插入后 getId）
        lenient().when(revisionRepository.insert(any(CityGuideRevision.class))).thenAnswer(inv -> {
            CityGuideRevision rev = inv.getArgument(0);
            rev.setId(rev.getRevisionNo() == null ? 1L : rev.getRevisionNo().longValue() + 1000L);
            return 1;
        });
    }

    private CityGuideService.GuideEdit edit(String title, String content) {
        return new CityGuideService.GuideEdit("北京", title, "摘要内容足够长一些。", null,
                CityGuide.SOURCE_CURATED, null, content, null);
    }

    /* ---------- 权限：攻略运营按域校验 GUIDE_MANAGE（C线角色细化） ---------- */

    @Test
    void guideActions_requireGuideManagePermission() {
        doThrow(new ForbiddenException("该操作需要管理员权限"))
                .when(communityUserService).requirePermission(any(), eq(AdminPermission.GUIDE_MANAGE));

        assertThrows(ForbiddenException.class, () -> service.detail("admin", 1L));
        assertThrows(ForbiddenException.class, () -> service.publish("admin", 1L));
        assertThrows(ForbiddenException.class, () -> service.create("admin", edit("新攻略", "正文内容足够长。")));
        // 权限校验必须在触库之前失败
        verify(guideRepository, never()).selectById(any());
    }

    private static final String MD = """
            # 北京旅行攻略

            北京，中国首都。

            ## 2. 核心景点

            ### 2.1 故宫博物院
            - **位置**：东城区
            - **门票**：60元
            - **简介**：明清皇家宫殿。
            """;

    @Test
    void create_writesDraftWithFirstRevisionAndAudit() {
        when(guideRepository.insert(any(CityGuide.class))).thenAnswer(inv -> {
            CityGuide g = inv.getArgument(0);
            g.setId(1L);
            return 1;
        });

        CityGuide g = service.create("admin", edit("北京旅行攻略", MD));

        assertEquals(CityGuide.STATUS_DRAFT, g.getStatus());
        assertEquals(1, g.getVersion());
        ArgumentCaptor<CityGuideRevision> rev = ArgumentCaptor.forClass(CityGuideRevision.class);
        verify(revisionRepository).insert(rev.capture());
        assertEquals(1, rev.getValue().getRevisionNo());
        assertEquals(CityGuide.STATUS_DRAFT, rev.getValue().getStatus());
        assertFalse(rev.getValue().getContentHash().isBlank());
        verify(auditService).record(eq("admin"), any(), eq("guide_created"), any(), any(), any());
    }

    @Test
    void submit_draftGoesPendingReview() {
        CityGuide g = guide(CityGuide.STATUS_DRAFT, 1, 11L);
        when(guideRepository.selectById(1L)).thenReturn(g);

        service.submit("admin", 1L);

        ArgumentCaptor<CityGuide> cap = ArgumentCaptor.forClass(CityGuide.class);
        verify(guideRepository).updateById(cap.capture());
        assertEquals(CityGuide.STATUS_PENDING_REVIEW, cap.getValue().getStatus());
        assertEquals("admin", cap.getValue().getSubmittedBy());
    }

    @Test
    void publish_goesPublishedButDefersVersionSwitchUntilRagIndexed() {
        // P0-1：发布只置 PUBLISHED + 登记任务；published_revision_id 不立即切换（首次发布为 null），
        // 由执行器 RAG 索引成功后才推进 —— 避免「DB 显示已发布、RAG 仍是旧版」的不一致窗口
        CityGuide g = guide(CityGuide.STATUS_PENDING_REVIEW, 2, 22L);
        when(guideRepository.selectById(1L)).thenReturn(g);

        service.publish("admin", 1L);

        ArgumentCaptor<CityGuide> cap = ArgumentCaptor.forClass(CityGuide.class);
        verify(guideRepository).updateById(cap.capture());
        assertEquals(CityGuide.STATUS_PUBLISHED, cap.getValue().getStatus());
        assertNull(cap.getValue().getPublishedRevisionId(),
                "发布不得立即切换线上版本（等 RAG 索引成功后由执行器激活）");
        assertEquals(CityGuide.RAG_NOT_INDEXED, cap.getValue().getRagStatus(),
                "发布只登记索引任务，真实索引由执行器异步完成后置 READY/FAILED");

        ArgumentCaptor<RagIndexTask> task = ArgumentCaptor.forClass(RagIndexTask.class);
        verify(taskRepository).insert(task.capture());
        assertEquals(RagIndexTask.STATUS_PENDING, task.getValue().getStatus(),
                "任务登记为 PENDING，交执行器消费");
        assertEquals(22L, task.getValue().getRevisionId(), "索引任务按 revision 绑定");
        verify(taskRepository, never()).updateById(any(RagIndexTask.class));
        verify(revisionRepository, never()).update(any(), any());
        verify(auditService).record(eq("admin"), any(), eq("guide_published"), any(), any(), any());
    }

    @Test
    void publish_previousLiveVersion_keepsServingUntilActivation() {
        // 对已发布内容再编辑后重新发布：旧版本继续在线（published_revision_id 不变），新版本排队激活
        CityGuide g = guide(CityGuide.STATUS_PENDING_REVIEW, 3, 33L);
        g.setPublishedRevisionId(22L);
        g.setRagStatus(CityGuide.RAG_READY);
        g.setRagIndexedRevision(22L);
        when(guideRepository.selectById(1L)).thenReturn(g);

        service.publish("admin", 1L);

        ArgumentCaptor<CityGuide> cap = ArgumentCaptor.forClass(CityGuide.class);
        verify(guideRepository).updateById(cap.capture());
        assertEquals(22L, cap.getValue().getPublishedRevisionId(),
                "旧线上版本继续服务，直到新版本 RAG 索引成功才切换");
        assertEquals(CityGuide.RAG_READY, cap.getValue().getRagStatus(),
                "旧版本仍在检索源中服务，不因新版本排队而抹掉");
        ArgumentCaptor<RagIndexTask> task = ArgumentCaptor.forClass(RagIndexTask.class);
        verify(taskRepository).insert(task.capture());
        assertEquals(33L, task.getValue().getRevisionId());
    }

    @Test
    void updatePublishedGuide_appendsRevisionAndGoesPendingReview_oldPublishedKept() {
        CityGuide g = guide(CityGuide.STATUS_PUBLISHED, 2, 22L);
        g.setPublishedRevisionId(11L);
        when(guideRepository.selectById(1L)).thenReturn(g);
        CityGuideRevision current = new CityGuideRevision();
        current.setId(22L);
        current.setContentHash(GuideMarkdownParser.sha256("old content"));
        when(revisionRepository.selectById(22L)).thenReturn(current);

        Integer version = service.update("admin", 1L, edit("北京旅行攻略新版", "new content"));

        assertEquals(3, version);
        ArgumentCaptor<CityGuide> cap = ArgumentCaptor.forClass(CityGuide.class);
        verify(guideRepository).updateById(cap.capture());
        assertEquals(CityGuide.STATUS_PENDING_REVIEW, cap.getValue().getStatus());
        assertEquals(11L, cap.getValue().getPublishedRevisionId(), "旧线上版本应继续保留");
        verify(revisionRepository).insert(any(CityGuideRevision.class));
    }

    @Test
    void update_sameContent_skipsNoVersion() {
        CityGuide g = guide(CityGuide.STATUS_DRAFT, 1, 11L);
        g.setTitle("北京旅行攻略");
        g.setSummary("摘要内容足够长一些。");
        when(guideRepository.selectById(1L)).thenReturn(g);
        CityGuideRevision current = new CityGuideRevision();
        current.setId(11L);
        current.setContentHash(GuideMarkdownParser.sha256(MD));
        when(revisionRepository.selectById(11L)).thenReturn(current);

        Integer version = service.update("admin", 1L, edit("北京旅行攻略", MD));

        assertNull(version);
        verify(revisionRepository, never()).insert(any());
        verify(guideRepository, never()).updateById(any());
    }

    @Test
    void importNewFile_createsPublishedGuideOnce() {
        when(guideRepository.selectOne(any())).thenReturn(null);
        when(guideRepository.insert(any(CityGuide.class))).thenAnswer(inv -> {
            CityGuide g = inv.getArgument(0);
            g.setId(1L);
            return 1;
        });

        String result = service.importOne("admin", "beijing_guide.md",
                GuideMarkdownParser.parse(MD, "beijing_guide.md"), MD,
                GuideMarkdownParser.sha256(MD));

        assertEquals("imported", result);
        ArgumentCaptor<CityGuide> cap = ArgumentCaptor.forClass(CityGuide.class);
        verify(guideRepository).updateById(cap.capture());
        assertEquals(CityGuide.STATUS_PUBLISHED, cap.getValue().getStatus());
        assertNotNull(cap.getValue().getPublishedRevisionId());
        verify(taskRepository).insert(any(RagIndexTask.class));
    }

    @Test
    void importSameFileSameHash_unchanged() {
        CityGuide g = guide(CityGuide.STATUS_PUBLISHED, 1, 11L);
        g.setSourceFile("beijing_guide.md");
        when(guideRepository.selectOne(any())).thenReturn(g);
        CityGuideRevision current = new CityGuideRevision();
        current.setId(11L);
        current.setContentHash(GuideMarkdownParser.sha256(MD));
        when(revisionRepository.selectById(11L)).thenReturn(current);

        String result = service.importOne("admin", "beijing_guide.md",
                GuideMarkdownParser.parse(MD, "beijing_guide.md"), MD,
                GuideMarkdownParser.sha256(MD));

        assertEquals("unchanged", result);
        verify(revisionRepository, never()).insert(any());
    }

    @Test
    void archive_publishedGuide_goesArchivedAndRemovesRagSource() {
        ReflectionTestUtils.setField(service, "ragService", ragService);
        CityGuide g = guide(CityGuide.STATUS_PUBLISHED, 2, 22L);
        g.setPublishedRevisionId(22L);
        g.setSourceFile("beijing_guide.md");
        when(guideRepository.selectById(1L)).thenReturn(g);

        service.archive("admin", 1L);

        ArgumentCaptor<CityGuide> cap = ArgumentCaptor.forClass(CityGuide.class);
        verify(guideRepository).updateById(cap.capture());
        assertEquals(CityGuide.STATUS_ARCHIVED, cap.getValue().getStatus());
        verify(ragService).removeGuideSource("beijing_guide.md");
        verify(auditService).record(eq("admin"), any(), eq("guide_archived"), any(), any(), any());
    }

    @Test
    void archive_fromDraft_rejected() {
        CityGuide g = guide(CityGuide.STATUS_DRAFT, 1, 11L);
        when(guideRepository.selectById(1L)).thenReturn(g);

        assertThrows(IllegalArgumentException.class, () -> service.archive("admin", 1L));
    }

    @Test
    void copy_publishedGuide_forksNewDraftV1WithSameContent() {
        CityGuide g = guide(CityGuide.STATUS_PUBLISHED, 3, 22L);
        g.setPublishedRevisionId(22L);
        g.setSummary("摘要内容足够长一些。");
        when(guideRepository.selectById(1L)).thenReturn(g);
        when(revisionRepository.selectById(22L)).thenReturn(revision(2, MD));
        when(guideRepository.insert(any(CityGuide.class))).thenAnswer(inv -> {
            CityGuide ng = inv.getArgument(0);
            ng.setId(9L);
            return 1;
        });

        Long newId = service.copy("admin", 1L);

        assertEquals(9L, newId);
        ArgumentCaptor<CityGuide> cap = ArgumentCaptor.forClass(CityGuide.class);
        verify(guideRepository).insert(cap.capture());
        assertEquals(CityGuide.STATUS_DRAFT, cap.getValue().getStatus());
        assertEquals(1, cap.getValue().getVersion());
        assertNull(cap.getValue().getSourceFile(), "副本不应绑定溯源文件（UNIQUE 约束）");
        assertEquals("北京", cap.getValue().getCity());
        verify(auditService).record(eq("admin"), any(), eq("guide_copied"), any(), any(), any());
    }

    @Test
    void rollback_pendingEdit_cancelsBackToPublishedRevision() {
        CityGuide g = guide(CityGuide.STATUS_PENDING_REVIEW, 3, 33L);
        g.setPublishedRevisionId(11L);
        g.setRagStatus(CityGuide.RAG_READY);
        g.setRagIndexedRevision(11L);
        when(guideRepository.selectById(1L)).thenReturn(g);
        when(revisionRepository.selectById(11L)).thenReturn(revision(1, MD));

        int revNo = service.rollback("admin", 1L);

        assertEquals(1, revNo);
        ArgumentCaptor<CityGuide> cap = ArgumentCaptor.forClass(CityGuide.class);
        verify(guideRepository).updateById(cap.capture());
        assertEquals(CityGuide.STATUS_PUBLISHED, cap.getValue().getStatus());
        assertEquals(11L, cap.getValue().getCurrentRevisionId());
        assertEquals(11L, cap.getValue().getPublishedRevisionId());
        assertEquals(MD, cap.getValue().getContentMarkdown());
        verify(taskRepository, never()).insert(any(RagIndexTask.class));
        verify(auditService).record(eq("admin"), any(), eq("guide_rolled_back"), any(), any(), any());
    }

    @Test
    void rollback_badPublish_swapsToOlderPublishedRevisionAndReindexes() {
        ReflectionTestUtils.setField(service, "indexingService", indexingService);
        CityGuide g = guide(CityGuide.STATUS_PUBLISHED, 2, 22L);
        g.setPublishedRevisionId(22L);
        when(guideRepository.selectById(1L)).thenReturn(g);
        CityGuideRevision v1 = revision(1, MD);
        CityGuideRevision v2 = revision(2, MD + "\n## 新增错误段落");
        v1.setId(11L);
        v1.setStatus(CityGuideRevision.STATUS_PUBLISHED);
        v2.setId(22L);
        v2.setStatus(CityGuideRevision.STATUS_PUBLISHED);
        when(revisionRepository.selectList(any())).thenReturn(List.of(v2, v1));
        when(revisionRepository.selectById(11L)).thenReturn(v1);

        int revNo = service.rollback("admin", 1L);

        assertEquals(1, revNo);
        ArgumentCaptor<CityGuide> cap = ArgumentCaptor.forClass(CityGuide.class);
        verify(guideRepository).updateById(cap.capture());
        assertEquals(11L, cap.getValue().getPublishedRevisionId(), "上一已发布版本重新立为线上");
        assertEquals(11L, cap.getValue().getCurrentRevisionId());
        assertEquals(CityGuide.RAG_NOT_INDEXED, cap.getValue().getRagStatus());
        ArgumentCaptor<RagIndexTask> task = ArgumentCaptor.forClass(RagIndexTask.class);
        verify(taskRepository).insert(task.capture());
        assertEquals(11L, task.getValue().getRevisionId(), "重建任务绑定回滚目标版本");
        verify(taskRepository).update(isNull(), any());
        verify(indexingService).indexPendingAsync(1L);
    }

    @Test
    void rollback_cancelUnactivatedPublish_backToServingVersionWithoutReindex() {
        // B1：新版本发布后尚未被 RAG 激活（current=22 ≠ published=11）→ 取消这次发布，
        // 回到仍在线服务的已激活版本（READY+indexed=11），无需重建索引
        CityGuide g = guide(CityGuide.STATUS_PUBLISHED, 2, 22L);
        g.setPublishedRevisionId(11L);
        g.setRagStatus(CityGuide.RAG_READY);
        g.setRagIndexedRevision(11L);
        when(guideRepository.selectById(1L)).thenReturn(g);
        when(revisionRepository.selectById(11L)).thenReturn(revision(1, MD));

        int revNo = service.rollback("admin", 1L);

        assertEquals(1, revNo);
        ArgumentCaptor<CityGuide> cap = ArgumentCaptor.forClass(CityGuide.class);
        verify(guideRepository).updateById(cap.capture());
        assertEquals(11L, cap.getValue().getCurrentRevisionId(), "回到已激活版本");
        assertEquals(11L, cap.getValue().getPublishedRevisionId());
        assertEquals(CityGuide.RAG_READY, cap.getValue().getRagStatus(), "已激活版本无需重建索引");
        verify(taskRepository, never()).insert(any(RagIndexTask.class));
        verify(taskRepository).update(isNull(), any());
        verify(auditService).record(eq("admin"), any(), eq("guide_rolled_back"), any(), any(), any());
    }

    @Test
    void rollback_firstPublishNeverActivated_revertsToPendingReview() {
        // B0：首次发布从未被激活（published=null）→ 取消发布退回待审，防止半公开内容流落到 RAG
        CityGuide g = guide(CityGuide.STATUS_PUBLISHED, 1, 11L);
        when(guideRepository.selectById(1L)).thenReturn(g);
        when(revisionRepository.selectById(11L)).thenReturn(revision(1, MD));

        int revNo = service.rollback("admin", 1L);

        assertEquals(1, revNo);
        ArgumentCaptor<CityGuide> cap = ArgumentCaptor.forClass(CityGuide.class);
        verify(guideRepository).updateById(cap.capture());
        assertEquals(CityGuide.STATUS_PENDING_REVIEW, cap.getValue().getStatus(),
                "未激活的首次发布回滚 = 取消发布退回待审");
        verify(taskRepository, never()).insert(any(RagIndexTask.class));
        verify(taskRepository).update(isNull(), any());
        verify(guideRepository).update(isNull(), any());
    }

    @Test
    void hide_publishedGuide_supersedesQueuedTasksAndClearsRagState() {
        ReflectionTestUtils.setField(service, "ragService", ragService);
        CityGuide g = guide(CityGuide.STATUS_PUBLISHED, 2, 22L);
        g.setPublishedRevisionId(22L);
        g.setRagStatus(CityGuide.RAG_READY);
        g.setRagIndexedRevision(22L);
        g.setSourceFile("beijing_guide.md");
        when(guideRepository.selectById(1L)).thenReturn(g);

        service.hide("admin", 1L);

        ArgumentCaptor<CityGuide> cap = ArgumentCaptor.forClass(CityGuide.class);
        verify(guideRepository).updateById(cap.capture());
        assertEquals(CityGuide.STATUS_HIDDEN, cap.getValue().getStatus());
        verify(ragService).removeGuideSource("beijing_guide.md");
        verify(taskRepository).update(isNull(), any());
        verify(guideRepository).update(isNull(), any());
    }

    @Test
    void restore_hiddenGuide_registersPendingTaskAndTriggersAsync() {
        // P1：下线时任务已作废（原任务可能是 READY/SUPERSEDED），恢复必须重新登记 PENDING 任务
        ReflectionTestUtils.setField(service, "indexingService", indexingService);
        CityGuide g = guide(CityGuide.STATUS_HIDDEN, 2, 22L);
        g.setPublishedRevisionId(22L);
        when(guideRepository.selectById(1L)).thenReturn(g);
        when(taskRepository.selectOne(any())).thenReturn(null); // 无 PENDING → 需重建

        service.restore("admin", 1L);

        ArgumentCaptor<CityGuide> cap = ArgumentCaptor.forClass(CityGuide.class);
        verify(guideRepository).updateById(cap.capture());
        assertEquals(CityGuide.STATUS_PUBLISHED, cap.getValue().getStatus());
        ArgumentCaptor<RagIndexTask> task = ArgumentCaptor.forClass(RagIndexTask.class);
        verify(taskRepository).insert(task.capture());
        assertEquals(RagIndexTask.STATUS_PENDING, task.getValue().getStatus(),
                "恢复必须重建 PENDING 索引任务（旧任务已作废，不会自动重新生效）");
        assertEquals(22L, task.getValue().getRevisionId());
        verify(indexingService).indexPendingAsync(1L);
        verify(auditService).record(eq("admin"), any(), eq("guide_restored"), any(), any(), any());
    }

    @Test
    void restore_hiddenGuide_reusesExistingPendingTaskForCurrent() {
        ReflectionTestUtils.setField(service, "indexingService", indexingService);
        CityGuide g = guide(CityGuide.STATUS_HIDDEN, 2, 22L);
        g.setPublishedRevisionId(22L);
        when(guideRepository.selectById(1L)).thenReturn(g);
        RagIndexTask existing = new RagIndexTask();
        existing.setId(9L);
        existing.setGuideId(1L);
        existing.setRevisionId(22L);
        existing.setStatus(RagIndexTask.STATUS_PENDING);
        when(taskRepository.selectOne(any())).thenReturn(existing);

        service.restore("admin", 1L);

        verify(taskRepository, never()).insert(any(RagIndexTask.class));
        verify(indexingService).indexPendingAsync(1L);
    }

    @Test
    void rollback_noOlderPublished_throws() {
        CityGuide g = guide(CityGuide.STATUS_PUBLISHED, 1, 11L);
        g.setPublishedRevisionId(11L);
        when(guideRepository.selectById(1L)).thenReturn(g);
        CityGuideRevision v1 = revision(1, MD);
        v1.setId(11L);
        v1.setStatus(CityGuideRevision.STATUS_PUBLISHED);
        when(revisionRepository.selectList(any())).thenReturn(List.of(v1));

        assertThrows(IllegalArgumentException.class, () -> service.rollback("admin", 1L));
    }

    @Test
    void detail_enrichesSpotMatchesAgainstSpotMainTable() {
        ReflectionTestUtils.setField(service, "spotMainRepository", spotMainRepository);
        CityGuide g = guide(CityGuide.STATUS_PUBLISHED, 2, 22L);
        g.setPublishedRevisionId(22L);
        g.setSourceFile("beijing_guide.md");
        when(guideRepository.selectById(1L)).thenReturn(g);
        CityGuideRevision v2 = revision(2, MD);
        v2.setId(22L);
        when(revisionRepository.selectById(22L)).thenReturn(v2);
        when(revisionRepository.selectList(any())).thenReturn(List.of());
        when(taskRepository.selectList(any())).thenReturn(List.of());
        CityGuideSpot s = new CityGuideSpot();
        s.setGuideId(1L);
        s.setRevisionId(22L);
        s.setSpotName("故宫博物院");
        when(spotRepository.selectList(any())).thenReturn(List.of(s));
        Spot main = new Spot();
        main.setSpotId("spot_北京_xxx");
        main.setPoiId("xxx");
        main.setName("故宫博物院");
        main.setNormalizedName("故宫博物院");
        main.setCity("北京");
        when(spotMainRepository.selectList(any())).thenReturn(List.of(main));

        Map<String, Object> d = service.detail("admin", 1L);

        assertEquals(1, d.get("spot_total"));
        assertEquals(1, d.get("spot_matched"));
        assertEquals(0, d.get("spot_unmatched"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> spots = (List<Map<String, Object>>) d.get("spots");
        assertEquals(Boolean.TRUE, spots.get(0).get("matched"));
        assertEquals("spot_北京_xxx", spots.get(0).get("spot_id"));
    }

    private CityGuideRevision revision(int revisionNo, String content) {
        CityGuideRevision r = new CityGuideRevision();
        r.setId(revisionNo * 11L);
        r.setRevisionNo(revisionNo);
        r.setContentMarkdown(content);
        r.setContentHash(GuideMarkdownParser.sha256(content));
        return r;
    }

    private CityGuide guide(String status, int version, long currentRevisionId) {
        CityGuide g = new CityGuide();
        g.setId(1L);
        g.setCity("北京");
        g.setTitle("北京旅行攻略");
        g.setStatus(status);
        g.setVersion(version);
        g.setCurrentRevisionId(currentRevisionId);
        g.setRagStatus(CityGuide.RAG_NOT_INDEXED);
        return g;
    }
}
