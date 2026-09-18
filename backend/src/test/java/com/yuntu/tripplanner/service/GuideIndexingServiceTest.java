package com.yuntu.tripplanner.service;

import java.util.Map;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yuntu.tripplanner.model.CityGuide;
import com.yuntu.tripplanner.model.CityGuideRevision;
import com.yuntu.tripplanner.model.RagIndexTask;
import com.yuntu.tripplanner.repository.CityGuideRepository;
import com.yuntu.tripplanner.repository.CityGuideRevisionRepository;
import com.yuntu.tripplanner.repository.RagIndexTaskRepository;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * RAG 索引执行器单测（A 组真实闭环 + 版本一致性 P0-1/P0-2）：
 * PENDING → 校验任务版本仍为攻略当前版本 → applyGuideVersion 成功 → READY 且
 * published_revision_id 推进（激活，P0-1）；失败 → FAILED 且 published_revision_id 不动
 * （旧版由 RagService 保持）；任务版本已被更新发布/攻略下线 → SUPERSEDED 跳过（P0-2）；
 * 连续发布遗留的过期任务在 indexPending 中自动跳过并收敛到最新版本。
 */
@ExtendWith(MockitoExtension.class)
class GuideIndexingServiceTest {

    @Mock
    private CityGuideRepository guideRepository;
    @Mock
    private CityGuideRevisionRepository revisionRepository;
    @Mock
    private RagIndexTaskRepository taskRepository;
    @Mock
    private RagService ragService;
    @Mock
    private AuditService auditService;

    private GuideIndexingService service;

    @BeforeEach
    void setUp() {
        service = new GuideIndexingService(guideRepository, revisionRepository, taskRepository,
                ragService, auditService);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CityGuide.class);
        TableInfoHelper.initTableInfo(assistant, CityGuideRevision.class);
        TableInfoHelper.initTableInfo(assistant, RagIndexTask.class);
    }

    /** 稳态已发布攻略：current == published（上一版本已激活），等待/重跑当前版本索引 */
    private CityGuide publishedGuide() {
        CityGuide g = new CityGuide();
        g.setId(1L);
        g.setCity("杭州");
        g.setTitle("杭州旅行攻略");
        g.setStatus(CityGuide.STATUS_PUBLISHED);
        g.setSourceFile("hangzhou_guide.md");
        g.setCurrentRevisionId(22L);
        g.setPublishedRevisionId(22L);
        g.setRagStatus(CityGuide.RAG_NOT_INDEXED);
        return g;
    }

    private RagIndexTask pendingTask() {
        RagIndexTask task = new RagIndexTask();
        task.setId(100L);
        task.setGuideId(1L);
        task.setRevisionId(22L);
        task.setStatus(RagIndexTask.STATUS_PENDING);
        task.setTriggeredBy("admin");
        return task;
    }

    private CityGuideRevision revision(long id, String content) {
        CityGuideRevision rev = new CityGuideRevision();
        rev.setId(id);
        rev.setContentMarkdown(content);
        return rev;
    }

    private static final String MD = "# 杭州旅行攻略\n\n## 2. 核心景点\n\n### 2.1 西湖\n- **位置**：西湖区";

    @Test
    void execute_success_marksReadyAndActivatesRevision() {
        when(guideRepository.selectById(1L)).thenReturn(publishedGuide());
        when(taskRepository.selectOne(any())).thenReturn(pendingTask());
        when(revisionRepository.selectById(22L)).thenReturn(revision(22L, MD));
        when(ragService.applyGuideVersion("hangzhou_guide.md", "杭州", MD)).thenReturn(true);
        when(taskRepository.updateById(any(RagIndexTask.class))).thenReturn(1);
        when(revisionRepository.update(isNull(), any())).thenReturn(1);

        Map<String, Object> result = service.indexPending(1L);

        assertEquals(RagIndexTask.STATUS_READY, result.get("status"));
        ArgumentCaptor<CityGuide> cap = ArgumentCaptor.forClass(CityGuide.class);
        verify(guideRepository).updateById(cap.capture());
        assertEquals(CityGuide.RAG_READY, cap.getValue().getRagStatus());
        assertEquals(22L, cap.getValue().getRagIndexedRevision());
        // P0-1：索引成功才推进 published_revision_id（激活线上版本），并同步 revision 状态为 PUBLISHED
        assertEquals(22L, cap.getValue().getPublishedRevisionId(),
                "RAG 索引成功后线上版本才切换（两段式发布激活端）");
        verify(revisionRepository).update(isNull(), any());
        verify(auditService).record(eq("admin"), any(), eq("guide_indexed"), any(), any(), any());
    }

    @Test
    void execute_failure_marksFailedAndKeepsOldRevisionServing() {
        when(guideRepository.selectById(1L)).thenReturn(publishedGuide());
        when(taskRepository.selectOne(any())).thenReturn(pendingTask());
        when(revisionRepository.selectById(22L)).thenReturn(revision(22L, MD));
        when(ragService.applyGuideVersion(eq("hangzhou_guide.md"), eq("杭州"), anyString()))
                .thenReturn(false); // embedding 失败 → RagService 内部保留旧 chunks
        when(taskRepository.updateById(any(RagIndexTask.class))).thenReturn(1);

        Map<String, Object> result = service.indexPending(1L);

        assertEquals(RagIndexTask.STATUS_FAILED, result.get("status"));
        assertNotNull(result.get("error_message"));
        ArgumentCaptor<CityGuide> cap = ArgumentCaptor.forClass(CityGuide.class);
        verify(guideRepository).updateById(cap.capture());
        assertEquals(CityGuide.RAG_FAILED, cap.getValue().getRagStatus());
        assertNull(cap.getValue().getPublishedRevisionId(),
                "索引失败不得推进 published_revision_id —— 旧版本继续在线服务");
        verify(revisionRepository, never()).update(any(), any());
        verify(auditService).record(eq("admin"), any(), eq("guide_index_failed"), any(), any(), any());
    }

    @Test
    void execute_taskRevisionOutdatedByNewerPublish_supersededWithoutApplying() {
        // 攻略已被更新版本发布（current=33 ≠ 任务 revision 22）→ 旧任务不得覆盖新版本（P0-2）
        CityGuide g = publishedGuide();
        g.setCurrentRevisionId(33L);
        g.setPublishedRevisionId(33L);
        when(guideRepository.selectById(1L)).thenReturn(g);
        when(taskRepository.selectOne(any())).thenReturn(pendingTask(), null); // 被作废后循环取不到任务即收敛
        when(taskRepository.updateById(any(RagIndexTask.class))).thenReturn(1);

        Map<String, Object> result = service.indexPending(1L);

        assertEquals(RagIndexTask.STATUS_SUPERSEDED, result.get("status"));
        verify(ragService, never()).applyGuideVersion(anyString(), anyString(), anyString());
        verify(guideRepository, never()).updateById(any());
        verify(auditService).record(eq("admin"), any(), eq("guide_index_superseded"), any(), any(), any());
    }

    @Test
    void execute_guideHiddenAfterTaskQueued_supersededWithoutApplying() {
        // 任务排队期间攻略已下线 → 执行器不得把内容重新写回检索源
        CityGuide g = publishedGuide();
        g.setStatus(CityGuide.STATUS_HIDDEN);
        when(guideRepository.selectById(1L)).thenReturn(g);
        when(taskRepository.selectOne(any())).thenReturn(pendingTask(), null); // 作废后循环取不到任务即收敛
        when(taskRepository.updateById(any(RagIndexTask.class))).thenReturn(1);

        Map<String, Object> result = service.indexPending(1L);

        assertEquals(RagIndexTask.STATUS_SUPERSEDED, result.get("status"));
        verify(ragService, never()).applyGuideVersion(anyString(), anyString(), anyString());
    }

    @Test
    void indexPending_consecutivePublishes_skipsSupersededAndConvergesToNewest() {
        // 连续发布留两个 PENDING：T1(rev22 旧) T2(rev33 新)；执行 T1 被 SUPERSEDED 后循环收敛到 T2
        CityGuide g = publishedGuide();
        g.setCurrentRevisionId(33L);
        g.setPublishedRevisionId(33L);
        when(guideRepository.selectById(1L)).thenReturn(g);
        RagIndexTask t1 = pendingTask(); // rev 22（旧任务）
        RagIndexTask t2 = new RagIndexTask();
        t2.setId(101L);
        t2.setGuideId(1L);
        t2.setRevisionId(33L);
        t2.setStatus(RagIndexTask.STATUS_PENDING);
        t2.setTriggeredBy("admin");
        when(taskRepository.selectOne(any())).thenReturn(t1, t2, null); // 循环取最早 PENDING
        when(revisionRepository.selectById(33L)).thenReturn(revision(33L, MD + "\n## 新增段落"));
        when(ragService.applyGuideVersion("hangzhou_guide.md", "杭州", MD + "\n## 新增段落"))
                .thenReturn(true);
        when(taskRepository.updateById(any(RagIndexTask.class))).thenReturn(1);
        when(revisionRepository.update(isNull(), any())).thenReturn(1);

        Map<String, Object> result = service.indexPending(1L);

        assertEquals(RagIndexTask.STATUS_READY, result.get("status"),
                "过期任务被跳过后应继续执行最新版本任务");
        verify(ragService, times(1)).applyGuideVersion(anyString(), anyString(), anyString());
        ArgumentCaptor<CityGuide> cap = ArgumentCaptor.forClass(CityGuide.class);
        verify(guideRepository).updateById(cap.capture());
        assertEquals(33L, cap.getValue().getPublishedRevisionId(), "线上版本只推进到最新版本");
        ArgumentCaptor<RagIndexTask> taskCap = ArgumentCaptor.forClass(RagIndexTask.class);
        verify(taskRepository, atLeastOnce()).updateById(taskCap.capture());
        assertTrue(taskCap.getAllValues().stream()
                        .anyMatch(t -> RagIndexTask.STATUS_SUPERSEDED.equals(t.getStatus())),
                "旧任务应标记 SUPERSEDED 而非执行");
    }

    @Test
    void reindex_reusesFailedTaskForSameRevision() {
        when(guideRepository.selectById(1L)).thenReturn(publishedGuide());
        RagIndexTask failed = pendingTask();
        failed.setStatus(RagIndexTask.STATUS_FAILED);
        failed.setErrorMessage("embedding 向量化失败");
        when(taskRepository.selectOne(any())).thenReturn(failed);
        when(taskRepository.updateById(any(RagIndexTask.class))).thenReturn(1);
        when(revisionRepository.selectById(22L)).thenReturn(revision(22L, MD));
        when(ragService.applyGuideVersion(eq("hangzhou_guide.md"), eq("杭州"), anyString()))
                .thenReturn(true);
        when(revisionRepository.update(isNull(), any())).thenReturn(1);

        Map<String, Object> result = service.reindex(1L, "admin");

        assertEquals(RagIndexTask.STATUS_READY, result.get("status"));
        ArgumentCaptor<RagIndexTask> taskCap = ArgumentCaptor.forClass(RagIndexTask.class);
        verify(taskRepository, atLeastOnce()).updateById(taskCap.capture());
        assertTrue(taskCap.getAllValues().stream().anyMatch(t -> RagIndexTask.STATUS_READY.equals(t.getStatus())),
                "重试后任务应终结为 READY");
    }

    @Test
    void noPendingTask_returnsNoTask() {
        when(taskRepository.selectOne(any())).thenReturn(null);

        Map<String, Object> result = service.indexPending(1L);

        assertEquals("NO_TASK", result.get("status"));
        verify(ragService, never()).applyGuideVersion(anyString(), anyString(), anyString());
    }
}
