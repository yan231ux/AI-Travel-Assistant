package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yuntu.tripplanner.model.AuditLog;
import com.yuntu.tripplanner.model.CityGuide;
import com.yuntu.tripplanner.model.CityGuideRevision;
import com.yuntu.tripplanner.model.RagIndexTask;
import com.yuntu.tripplanner.repository.CityGuideRepository;
import com.yuntu.tripplanner.repository.CityGuideRevisionRepository;
import com.yuntu.tripplanner.repository.RagIndexTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 攻略索引执行器（管理员后台与内容运营中心设计方案 §9.2「单向同步链路」执行端）。
 *
 * <p>把「发布即登记任务」升级为真实闭环：消费 rag_index_task（PENDING）→ 读取该 revision
 * 的已发布正文 → 切分并向量化 → 成功后整体替换 RagService 检索源并<b>推进 published_revision_id</b>
 * （两段式发布的激活端：发布先置 PUBLISHED，索引成功线上版本才切换）；失败时旧版本继续服务
 * （applyGuideVersion 保证原子性），任务标记 FAILED 并保留失败原因，支持手动重试。
 *
 * <p>幂等与并发安全：同 (guide, revision) 只执行一次（任务状态机）；旧任务不能覆盖新版本——
 * 执行前校验任务 revision 仍为该攻略的 current（已被更新发布/回滚/下线取代则标记 SUPERSEDED
 * 跳过，绝不把过期内容写回检索源）。
 */
@Slf4j
@Service
public class GuideIndexingService {

    private final CityGuideRepository guideRepository;
    private final CityGuideRevisionRepository revisionRepository;
    private final RagIndexTaskRepository taskRepository;
    private final RagService ragService;
    private final AuditService auditService;

    public GuideIndexingService(CityGuideRepository guideRepository,
                                CityGuideRevisionRepository revisionRepository,
                                RagIndexTaskRepository taskRepository,
                                RagService ragService,
                                AuditService auditService) {
        this.guideRepository = guideRepository;
        this.revisionRepository = revisionRepository;
        this.taskRepository = taskRepository;
        this.ragService = ragService;
        this.auditService = auditService;
    }

    /**
     * 启动完成后恢复：把 DB 中已 PUBLISHED 且 rag_status=READY 的攻略版本重放为检索源
     * （内容与 classpath 种子一致的片段零 embedding 成本，直接命中持久化向量缓存）。
     * 保证"数据库已发布版本 = 线上唯一主数据"在重启后依然成立。
     *
     * <p>同时迁移历史口径：A 组上线前遗留的 PUBLISHED + rag_status=DEFERRED（无真实索引）
     * 若其内容可成功应用（持久化向量缓存命中/可向量化），升级为 READY + rag_indexed_revision，
     * 让运营后台不再出现已废弃的 DEFERRED 状态。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void restoreReadyGuidesOnStartup() {
        try {
            List<CityGuide> ready = guideRepository.selectList(new LambdaQueryWrapper<CityGuide>()
                    .eq(CityGuide::getStatus, CityGuide.STATUS_PUBLISHED)
                    .eq(CityGuide::getRagStatus, CityGuide.RAG_READY));
            int ok = 0;
            for (CityGuide g : ready) {
                if (g.getPublishedRevisionId() == null) {
                    continue;
                }
                if (applyRevision(g, g.getPublishedRevisionId(), true)) {
                    ok++;
                }
            }
            log.info("启动恢复 DB 已发布攻略版本：共 {} 篇成功应用为 RAG 检索源", ok);
            migrateLegacyDeferred();
        } catch (Exception e) {
            log.warn("启动恢复 DB 攻略版本失败（RAG 仍以 classpath 种子提供检索）: {}", e.getMessage());
        }
    }

    /** 存量 DEFERRED（A 组前的过渡期口径）→ 尝试升级为 READY；失败保持原样等待手动重试 */
    private void migrateLegacyDeferred() {
        int ok = 0;
        int failed = 0;
        try {
            // 1) 攻略主档：PUBLISHED + rag_status=DEFERRED → 成功应用后升级 READY
            List<CityGuide> legacy = guideRepository.selectList(new LambdaQueryWrapper<CityGuide>()
                    .eq(CityGuide::getStatus, CityGuide.STATUS_PUBLISHED)
                    .eq(CityGuide::getRagStatus, CityGuide.RAG_DEFERRED));
            for (CityGuide g : legacy) {
                Long revId = g.getPublishedRevisionId() != null ? g.getPublishedRevisionId()
                        : g.getCurrentRevisionId();
                if (revId == null) {
                    continue;
                }
                if (applyRevision(g, revId, false)) {
                    CityGuide upd = new CityGuide();
                    upd.setId(g.getId());
                    upd.setRagStatus(CityGuide.RAG_READY);
                    upd.setRagIndexedRevision(revId);
                    guideRepository.updateById(upd);
                    ok++;
                } else {
                    failed++;
                }
            }
            // 2) 任务表：DEFERRED 是"登记即占位、从未执行"的历史行（无 finished_at、无真实结果），
            //    真实执行后由 PENDING→READY/FAILED 取代，直接清理避免运营后台出现废弃状态
            int cleaned = taskRepository.delete(new LambdaUpdateWrapper<RagIndexTask>()
                    .eq(RagIndexTask::getStatus, RagIndexTask.STATUS_DEFERRED));
            if (ok > 0 || failed > 0 || cleaned > 0) {
                log.info("存量 DEFERRED 迁移：{} 篇攻略升级 READY，{} 篇待手动重试，清理占位任务 {} 条",
                        ok, failed, cleaned);
            }
        } catch (Exception e) {
            log.warn("存量 DEFERRED 迁移异常（不影响检索源恢复）: {}", e.getMessage());
        }
    }

    /**
     * 发布/导入后的异步索引入口：把该攻略最新一条 PENDING 任务真正执行。
     */
    @Async
    public void indexPendingAsync(Long guideId) {
        try {
            indexPending(guideId);
        } catch (Exception e) {
            log.error("异步索引执行异常: guideId={} {}", guideId, e.getMessage(), e);
        }
    }

    /** 批量导入后：执行当前全部 PENDING 索引任务（每篇独立成败，互不阻塞） */
    @Async
    public void indexAllPendingAsync() {
        try {
            List<RagIndexTask> pending = taskRepository.selectList(new LambdaQueryWrapper<RagIndexTask>()
                    .eq(RagIndexTask::getStatus, RagIndexTask.STATUS_PENDING)
                    .orderByAsc(RagIndexTask::getId));
            for (RagIndexTask task : pending) {
                try {
                    execute(task);
                } catch (Exception e) {
                    log.error("批量索引单篇异常 guideId={}: {}", task.getGuideId(), e.getMessage(), e);
                }
            }
            log.info("批量索引任务处理完成，共 {} 篇", pending.size());
        } catch (Exception e) {
            log.error("批量索引任务加载失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 执行该攻略最早的 PENDING 索引任务（同步；供发布后/重试调用）。
     *
     * <p>任务可能因版本过期被标记 SUPERSEDED（如连续发布后旧任务先被取到）——
     * 此时循环取下一条 PENDING 继续，直到没有任务或出现真实执行（READY/FAILED）。
     *
     * @return 最后一次任务结果（status/finished）
     */
    public Map<String, Object> indexPending(Long guideId) {
        if (guideId == null) {
            return result("NO_TASK", "攻略不存在");
        }
        Map<String, Object> last = null;
        while (true) {
            RagIndexTask task = taskRepository.selectOne(new LambdaQueryWrapper<RagIndexTask>()
                    .eq(RagIndexTask::getGuideId, guideId)
                    .eq(RagIndexTask::getStatus, RagIndexTask.STATUS_PENDING)
                    .orderByAsc(RagIndexTask::getId)
                    .last("LIMIT 1"));
            if (task == null) {
                break;
            }
            last = execute(task);
            // 过期任务已被跳过 → 继续处理下一条排队任务；真实执行（READY/FAILED）后收敛
            if (!RagIndexTask.STATUS_SUPERSEDED.equals(last.get("status"))) {
                break;
            }
        }
        if (last == null) {
            return result("NO_TASK", "没有待执行的索引任务");
        }
        return last;
    }

    /**
     * 手动重建/重试索引：以攻略<b>当前版本</b>（两段式发布下 = 待激活/已激活的线上目标）创建
     * （或复用）PENDING 任务并立即执行。用于：索引失败后重试、索引成功后管理员强制重建
     * （如更换 embedding 模型）。激活窗口内 published_revision_id 仍是旧版，重试必须指向
     * current（失败的是它），否则永远重试旧版永远无法激活新版本。
     */
    public Map<String, Object> reindex(Long guideId, String actor) {
        CityGuide g = guideId == null ? null : guideRepository.selectById(guideId);
        if (g == null) {
            throw new IllegalArgumentException("攻略不存在: " + guideId);
        }
        if (!CityGuide.STATUS_PUBLISHED.equals(g.getStatus())) {
            throw new IllegalArgumentException("仅已发布攻略可重建索引（草稿/待审核内容尚未生效）");
        }
        Long revisionId = g.getCurrentRevisionId();
        if (revisionId == null) {
            throw new IllegalArgumentException("该攻略尚无内容版本，无法索引");
        }
        // 同 revision 已有任务（READY/FAILED/SUPERSEDED）→ 复用重置为 PENDING 重跑；否则新建
        RagIndexTask task = taskRepository.selectOne(new LambdaQueryWrapper<RagIndexTask>()
                .eq(RagIndexTask::getGuideId, guideId)
                .eq(RagIndexTask::getRevisionId, revisionId)
                .orderByDesc(RagIndexTask::getId)
                .last("LIMIT 1"));
        if (task == null || !RagIndexTask.STATUS_PENDING.equals(task.getStatus())) {
            if (task == null) {
                task = new RagIndexTask();
                task.setGuideId(guideId);
                task.setRevisionId(revisionId);
                task.setStatus(RagIndexTask.STATUS_PENDING);
                task.setTriggeredBy(actor);
                taskRepository.insert(task);
            } else {
                task.setStatus(RagIndexTask.STATUS_PENDING);
                task.setErrorMessage(null);
                task.setFinishedAt(null);
                taskRepository.updateById(task);
            }
        }
        auditService.record(actor == null ? "system" : actor, AuditLog.CAT_ADMIN,
                "guide_reindex", "guide", String.valueOf(guideId),
                AuditService.detailOf("revision", revisionId, "manual", true));
        return execute(task);
    }

    /**
     * 执行单条任务：切分+向量化+替换检索源。
     *
     * <p>执行前版本校验（P0-2）：仅当攻略仍 PUBLISHED 且任务 revision == 攻略 current
     * （当前唯一有效目标）才执行——否则该任务已被更新版本取代/攻略已下线归档，
     * 标记 SUPERSEDED 跳过，杜绝旧任务晚完成覆盖新版本或复活已下线内容。
     * 成功后推进 published_revision_id（P0-1 激活）与 revision 状态；失败保持旧版本服务（FAILED）。
     */
    private Map<String, Object> execute(RagIndexTask task) {
        CityGuide g = guideRepository.selectById(task.getGuideId());
        if (g == null) {
            return finish(task, RagIndexTask.STATUS_FAILED, "攻略不存在（可能已删除）");
        }
        if (!CityGuide.STATUS_PUBLISHED.equals(g.getStatus())
                || task.getRevisionId() == null
                || !task.getRevisionId().equals(g.getCurrentRevisionId())) {
            String reason = !CityGuide.STATUS_PUBLISHED.equals(g.getStatus())
                    ? "攻略已下线/归档，索引任务作废"
                    : "该版本已被更新的发布取代（当前版本 " + g.getCurrentRevisionId() + "），索引任务作废";
            auditService.record(task.getTriggeredBy() == null ? "system" : task.getTriggeredBy(),
                    AuditLog.CAT_ADMIN, "guide_index_superseded", "guide", String.valueOf(g.getId()),
                    AuditService.detailOf("city", g.getCity(), "revision", task.getRevisionId(),
                            "reason", reason, "rag", RagIndexTask.STATUS_SUPERSEDED));
            log.info("索引任务作废（版本已过期）: guideId={} revision={} reason={}",
                    g.getId(), task.getRevisionId(), reason);
            return finish(task, RagIndexTask.STATUS_SUPERSEDED, reason);
        }
        CityGuideRevision rev = revisionRepository.selectById(task.getRevisionId());
        if (rev == null || rev.getContentMarkdown() == null || rev.getContentMarkdown().isBlank()) {
            return finish(task, RagIndexTask.STATUS_FAILED, "索引版本缺少正文内容");
        }
        // source 命名：优先保留溯源文件名（与 classpath 种子/持久化向量同 key，零成本复用）；
        // 无 source_file（管理员新建）→ 用 guide-{id}.md 隔离命名。
        String sourceName = g.getSourceFile() != null && !g.getSourceFile().isBlank()
                ? g.getSourceFile()
                : "guide-" + g.getId() + ".md";
        boolean ok = ragService.applyGuideVersion(sourceName, g.getCity(), rev.getContentMarkdown());
        if (ok) {
            // 两段式激活成功：原子切换 —— published_revision_id 推进到本版本（P0-1 核心：
            // 「DB 声明已发布」与「RAG 实际服务」在同一时刻对齐），rag READY + 版本状态 PUBLISHED
            CityGuide upd = new CityGuide();
            upd.setId(g.getId());
            upd.setRagStatus(CityGuide.RAG_READY);
            upd.setRagIndexedRevision(task.getRevisionId());
            upd.setPublishedRevisionId(task.getRevisionId());
            guideRepository.updateById(upd);
            revisionRepository.update(null, new LambdaUpdateWrapper<CityGuideRevision>()
                    .eq(CityGuideRevision::getId, task.getRevisionId())
                    .set(CityGuideRevision::getStatus, CityGuideRevision.STATUS_PUBLISHED));
            auditService.record(task.getTriggeredBy() == null ? "system" : task.getTriggeredBy(),
                    AuditLog.CAT_ADMIN, "guide_indexed", "guide", String.valueOf(g.getId()),
                    AuditService.detailOf("city", g.getCity(), "revision", task.getRevisionId(),
                            "activated", true, "rag", "READY"));
            log.info("攻略索引成功并切换线上版本: guideId={} revision={} city={}",
                    g.getId(), task.getRevisionId(), g.getCity());
            return finish(task, RagIndexTask.STATUS_READY, null);
        }
        // 失败：仅标记 FAILED，published_revision_id 保持旧值 —— 旧版本真实继续服务，可手动重试
        CityGuide upd = new CityGuide();
        upd.setId(g.getId());
        upd.setRagStatus(CityGuide.RAG_FAILED);
        guideRepository.updateById(upd);
        auditService.record(task.getTriggeredBy() == null ? "system" : task.getTriggeredBy(),
                AuditLog.CAT_ADMIN, "guide_index_failed", "guide", String.valueOf(g.getId()),
                AuditService.detailOf("city", g.getCity(), "revision", task.getRevisionId(),
                        "rag", "FAILED"));
        log.warn("攻略索引失败（旧版继续服务）: guideId={} revision={}", g.getId(), task.getRevisionId());
        return finish(task, RagIndexTask.STATUS_FAILED, "embedding 向量化失败：请检查 LLM_API_KEY 与网络后重试");
    }

    /** 应用单个已发布版本（启动恢复用）：成功返回 true */
    private boolean applyRevision(CityGuide g, Long revisionId, boolean logFail) {
        try {
            CityGuideRevision rev = revisionRepository.selectById(revisionId);
            if (rev == null || rev.getContentMarkdown() == null) {
                return false;
            }
            String sourceName = g.getSourceFile() != null && !g.getSourceFile().isBlank()
                    ? g.getSourceFile() : "guide-" + g.getId() + ".md";
            return ragService.applyGuideVersion(sourceName, g.getCity(), rev.getContentMarkdown());
        } catch (Exception e) {
            if (logFail) {
                log.warn("恢复攻略版本失败 guideId={}: {}", g.getId(), e.getMessage());
            }
            return false;
        }
    }

    private Map<String, Object> finish(RagIndexTask task, String status, String error) {
        task.setStatus(status);
        task.setErrorMessage(error);
        task.setFinishedAt(LocalDateTime.now());
        taskRepository.updateById(task);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task_id", task.getId());
        m.put("guide_id", task.getGuideId());
        m.put("revision_id", task.getRevisionId());
        m.put("status", status);
        m.put("error_message", error);
        m.put("finished_at", task.getFinishedAt() == null ? null
                : task.getFinishedAt().toString().replace("T", " ").substring(0, 19));
        return m;
    }

    private static Map<String, Object> result(String status, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("error_message", msg);
        return m;
    }
}
