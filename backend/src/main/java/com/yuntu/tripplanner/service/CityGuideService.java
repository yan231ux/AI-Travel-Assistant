package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.common.GuideMarkdownParser;
import com.yuntu.tripplanner.common.SpotNameUtil;
import com.yuntu.tripplanner.exception.PostNotFoundException;
import com.yuntu.tripplanner.model.AuditLog;
import com.yuntu.tripplanner.model.CityGuide;
import com.yuntu.tripplanner.model.CityGuideRevision;
import com.yuntu.tripplanner.model.ContentModerationTask;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 攻略内容服务（管理后台内容运营骨架：管理员后台与内容运营中心设计方案 §9）。
 *
 * <p>数据主权：数据库 city_guide 已发布版本 = 唯一线上主数据；RAG 分片/向量是派生数据；
 * classpath Markdown 仅作初始化导入/备份载体。发布采用<b>两段式激活</b>：审核通过即登记
 * rag_index_task（PENDING）并置 PUBLISHED，由 GuideIndexingService 异步执行——成功才把
 * published_revision_id 推进到新版本并原子切换检索源（READY），失败保持旧版本继续服务
 * （FAILED，可手动重试），杜绝「DB 显示已发布、RAG 仍是旧版」的不一致窗口，见 §14.5。
 *
 * <p>幂等：1) 导入按 source_file + content_hash 判重，同文件同内容跳过；文件内容变化时
 * append 新版本并置 PENDING_REVIEW（不静默覆盖线上）；2) 保存时正文哈希与当前版本一致即跳过
 * （无变化不产生版本）。
 *
 * <p>状态机：DRAFT → PENDING_REVIEW → PUBLISHED；REJECTED 可改后重提；已发布内容再次编辑
 * → 追加新版本并回到 PENDING_REVIEW（旧 published_revision 继续服务，审核通过才切换）。
 */
@Slf4j
@Service
public class CityGuideService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CityGuideRepository guideRepository;
    private final CityGuideRevisionRepository revisionRepository;
    private final CityGuideSpotRepository spotRepository;
    private final CityGuideTagRepository tagRepository;
    private final RagIndexTaskRepository taskRepository;
    private final CommunityUserService communityUserService;
    private final AuditService auditService;

    /** 可选注入：攻略下线/归档时同步 RAG 检索源（单测 new 时不注入，跳过该联动） */
    @Autowired(required = false)
    private RagService ragService;
    @Autowired(required = false)
    private GuideIndexingService indexingService;
    /** 可选注入：spot 主档（攻略景点名 → 主档匹配，设计 §4.4；单测不注入时匹配计数为 0） */
    @Autowired(required = false)
    private SpotRepository spotMainRepository;
    /** 可选注入：AI 内容审核（阶段三：提交审核后异步初筛建议；单测不注入时跳过） */
    @Autowired(required = false)
    private ContentModerationService moderationService;

    public CityGuideService(CityGuideRepository guideRepository,
                            CityGuideRevisionRepository revisionRepository,
                            CityGuideSpotRepository spotRepository,
                            CityGuideTagRepository tagRepository,
                            RagIndexTaskRepository taskRepository,
                            CommunityUserService communityUserService,
                            AuditService auditService) {
        this.guideRepository = guideRepository;
        this.revisionRepository = revisionRepository;
        this.spotRepository = spotRepository;
        this.tagRepository = tagRepository;
        this.taskRepository = taskRepository;
        this.communityUserService = communityUserService;
        this.auditService = auditService;
    }

    /* ================= 列表/详情（后台只读面） ================= */

    /** 攻略列表（后台）：按城市/状态/标题关键词过滤，新→旧；附覆盖景点数/标签数/版本数 */
    public Map<String, Object> page(String adminId, String city, String status, String keyword,
                                    int page, int pageSize) {
        communityUserService.requirePermission(adminId, AdminPermission.GUIDE_MANAGE);
        int size = Math.max(1, Math.min(pageSize <= 0 ? 20 : pageSize, 100));
        int pageNo = Math.max(1, page);
        LambdaQueryWrapper<CityGuide> w = new LambdaQueryWrapper<CityGuide>()
                .orderByDesc(CityGuide::getUpdatedAt);
        if (city != null && !city.isBlank()) {
            w.eq(CityGuide::getCity, city.trim());
        }
        if (status != null && !status.isBlank()) {
            w.eq(CityGuide::getStatus, status.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            w.like(CityGuide::getTitle, keyword.trim());
        }
        long total = guideRepository.selectCount(w);
        List<CityGuide> rows = guideRepository.selectList(w.last(
                "LIMIT " + size + " OFFSET " + (pageNo - 1) * size));
        // 每篇攻略的「解析景点数 / 已匹配 spot 主档数」（设计 §4.4 列表可读指标；批量查一次）
        Map<Long, List<CityGuideSpot>> spotsByGuide = new HashMap<>();
        Set<String> cities = new HashSet<>();
        if (!rows.isEmpty()) {
            List<Long> ids = rows.stream().map(CityGuide::getId).toList();
            List<CityGuideSpot> all = spotRepository.selectList(new LambdaQueryWrapper<CityGuideSpot>()
                    .in(CityGuideSpot::getGuideId, ids));
            for (CityGuideSpot s : all) {
                spotsByGuide.computeIfAbsent(s.getGuideId(), k -> new ArrayList<>()).add(s);
            }
            rows.forEach(g -> cities.add(g.getCity()));
        }
        Map<String, Map<String, Spot>> spotIndexByCity = cities.isEmpty() || spotMainRepository == null
                ? Map.of() : spotIndexByCity(cities);
        List<Map<String, Object>> items = new ArrayList<>();
        for (CityGuide g : rows) {
            Map<String, Object> brief = guideBrief(g);
            int spotCount = 0;
            int spotMatched = 0;
            List<CityGuideSpot> guideSpots = spotsByGuide.getOrDefault(g.getId(), List.of());
            Map<String, Spot> index = spotIndexByCity.getOrDefault(g.getCity(), Map.of());
            for (CityGuideSpot s : guideSpots) {
                if (g.getCurrentRevisionId() != null
                        && g.getCurrentRevisionId().equals(s.getRevisionId())) {
                    spotCount++;
                    if (index.containsKey(SpotNameUtil.normalize(s.getSpotName()))) {
                        spotMatched++;
                    }
                }
            }
            brief.put("spot_count", spotCount);
            brief.put("spot_matched", spotMatched);
            items.add(brief);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", total);
        body.put("page", pageNo);
        return body;
    }

    /** 攻略详情：主档 + 当前编辑版正文 + 结构化关联 + 版本历史 + 索引任务 */
    public Map<String, Object> detail(String adminId, Long guideId) {
        communityUserService.requirePermission(adminId, AdminPermission.GUIDE_MANAGE);
        CityGuide g = requireGuide(guideId);
        Map<String, Object> m = guideBrief(g);
        CityGuideRevision current = g.getCurrentRevisionId() == null ? null
                : revisionRepository.selectById(g.getCurrentRevisionId());
        m.put("content_markdown", current == null ? "" : current.getContentMarkdown());
        m.put("current_hash", current == null ? null : current.getContentHash());
        if (g.getPublishedRevisionId() != null) {
            CityGuideRevision pub = revisionRepository.selectById(g.getPublishedRevisionId());
            if (pub != null) {
                m.put("published_revision_no", pub.getRevisionNo());
                m.put("published_at_hash", shortHash(pub.getContentHash()));
            }
        }
        List<Map<String, Object>> spots = new ArrayList<>();
        List<CityGuideSpot> spotRows = current == null ? List.of() : listSpots(guideId, current.getId());
        Map<String, Spot> spotIndex = spotIndexFor(g.getCity());
        int matched = 0;
        for (CityGuideSpot s : spotRows) {
            Spot hit = spotIndex.get(SpotNameUtil.normalize(s.getSpotName()));
            if (hit != null) {
                s.setSpotId(hit.getSpotId());
                s.setPoiId(hit.getPoiId());
                matched++;
            }
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("name", s.getSpotName());
            sm.put("matched", hit != null);
            sm.put("spot_id", s.getSpotId());
            sm.put("poi_id", s.getPoiId());
            spots.add(sm);
        }
        m.put("spots", spots);
        m.put("spot_total", spotRows.size());
        m.put("spot_matched", matched);
        m.put("spot_unmatched", spotRows.size() - matched);
        List<Map<String, Object>> tags = new ArrayList<>();
        List<CityGuideTag> tagRows = current == null ? List.of() : listTags(guideId, current.getId());
        for (CityGuideTag t : tagRows) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("category", t.getCategory());
            tm.put("tag", t.getTag());
            tags.add(tm);
        }
        m.put("tags", tags);
        m.put("revisions", revisionList(guideId));
        m.put("rag_tasks", ragTasks(guideId));
        return m;
    }

    /* ================= 写操作（编辑/状态机） ================= */

    /** 新建攻略草稿（DRAFT + 首个版本 v1 + 结构化关联骨架） */
    @Transactional
    public CityGuide create(String adminId, GuideEdit req) {
        communityUserService.requirePermission(adminId, AdminPermission.GUIDE_MANAGE);
        validate(req);
        CityGuide g = new CityGuide();
        g.setCity(req.city().trim());
        g.setTitle(req.title().trim());
        g.setSummary(trimToNull(req.summary()));
        g.setCoverImage(trimToNull(req.coverImage()));
        g.setSourceType(req.sourceType() == null || req.sourceType().isBlank()
                ? CityGuide.SOURCE_CURATED : req.sourceType().trim());
        g.setSourceName(trimToNull(req.sourceName()));
        g.setAuthor(communityUserService.nicknameOf(adminId));
        g.setStatus(CityGuide.STATUS_DRAFT);
        g.setQualityScore(0);
        g.setVersion(0);
        g.setRagStatus(CityGuide.RAG_NOT_INDEXED);
        guideRepository.insert(g);
        appendRevision(g, adminId, req.contentMarkdown(), "新建攻略");
        guideRepository.updateById(g);
        auditService.record(adminId, AuditLog.CAT_ADMIN, "guide_created", "guide",
                String.valueOf(g.getId()), AuditService.detailOf("city", g.getCity(), "title", g.getTitle()));
        log.info("攻略草稿创建: guideId={} city={} by={}", g.getId(), g.getCity(), adminId);
        return g;
    }

    /**
     * 保存编辑：内容/元信息无变化 → 返回 null（无版本产生）；有变化 → append 新版本。
     * 已发布/已隐藏内容被编辑时回到 PENDING_REVIEW，旧 published_revision 继续服务。
     *
     * @return 新版本号（无变化为 null）
     */
    @Transactional
    public Integer update(String adminId, Long guideId, GuideEdit req) {
        communityUserService.requirePermission(adminId, AdminPermission.GUIDE_MANAGE);
        validate(req);
        CityGuide g = requireGuide(guideId);
        CityGuideRevision current = g.getCurrentRevisionId() == null ? null
                : revisionRepository.selectById(g.getCurrentRevisionId());
        String newContent = req.contentMarkdown();
        boolean contentSame = current != null
                && GuideMarkdownParser.sha256(newContent).equals(current.getContentHash());
        boolean metaSame = same(g.getCity(), req.city())
                && same(g.getTitle(), req.title())
                && same(g.getSummary(), req.summary())
                && same(g.getCoverImage(), req.coverImage());
        if (contentSame && metaSame) {
            log.debug("攻略保存无变化跳过: guideId={}", guideId);
            return null;
        }
        g.setCity(req.city().trim());
        g.setTitle(req.title().trim());
        g.setSummary(trimToNull(req.summary()));
        g.setCoverImage(trimToNull(req.coverImage()));
        String oldStatus = g.getStatus();
        // 已公开/已下线内容被编辑 → 先转待审再落新版本（新版本状态=PENDING_REVIEW，
        // 旧 published_revision 继续服务，审核通过才切换）
        if (CityGuide.STATUS_PUBLISHED.equals(oldStatus) || CityGuide.STATUS_HIDDEN.equals(oldStatus)) {
            g.setStatus(CityGuide.STATUS_PENDING_REVIEW);
            g.setSubmittedBy(adminId);
        }
        appendRevision(g, adminId, newContent, trimToNull(req.changeSummary()));
        guideRepository.updateById(g);
        auditService.record(adminId, AuditLog.CAT_ADMIN, "guide_updated", "guide",
                String.valueOf(guideId), AuditService.detailOf("title", g.getTitle(),
                        "from_status", oldStatus, "to_status", g.getStatus()));
        return g.getVersion();
    }

    /** 提交审核：DRAFT/REJECTED → PENDING_REVIEW */
    @Transactional
    public void submit(String adminId, Long guideId) {
        communityUserService.requirePermission(adminId, AdminPermission.GUIDE_MANAGE);
        CityGuide g = requireGuide(guideId);
        if (!CityGuide.STATUS_DRAFT.equals(g.getStatus())
                && !CityGuide.STATUS_REJECTED.equals(g.getStatus())) {
            throw new IllegalArgumentException("仅草稿或被拒的攻略可提交审核");
        }
        g.setStatus(CityGuide.STATUS_PENDING_REVIEW);
        g.setSubmittedBy(adminId);
        g.setRejectReason(null);
        guideRepository.updateById(g);
        markRevisionStatus(g, CityGuideRevision.STATUS_PENDING_REVIEW);
        auditService.record(adminId, AuditLog.CAT_ADMIN, "guide_submitted", "guide",
                String.valueOf(guideId), AuditService.detailOf("city", g.getCity()));
        // AI 审核初筛（阶段三，可选）：绑 revision_id 防旧 AI 结果覆盖新版本内容。
        // 异步执行；未注入（单测）或失败均不影响提交。
        if (moderationService != null && g.getCurrentRevisionId() != null) {
            CityGuideRevision rev = revisionRepository.selectById(g.getCurrentRevisionId());
            moderationService.submitAsync(ContentModerationTask.TARGET_GUIDE, String.valueOf(guideId),
                    g.getCurrentRevisionId(), g.getTitle(), g.getCity(),
                    rev == null ? null : rev.getContentMarkdown(), adminId);
        }
    }

    /**
     * 审核通过发布：PENDING_REVIEW → PUBLISHED 并登记索引任务。
     *
     * <p>两段式激活（P0-1 修复）：此处<b>不</b>把 published_revision_id 切到新版本——它保持指向
     * 当前线上真实生效的版本（RAG 仍在服务的旧版；首次发布为 null）。真正的「线上版本切换」
     * 由 GuideIndexingService 在 RAG 索引<b>成功</b>后原子完成（published_revision_id → 新版本 +
     * rag READY）；失败则保持旧版在线并置 rag_status=FAILED 供重试，避免「DB 显示已发布、
     * RAG 仍是旧版」的不一致窗口。发布期间 revision 状态由执行器在激活成功时置 PUBLISHED。
     */
    @Transactional
    public void publish(String adminId, Long guideId) {
        communityUserService.requirePermission(adminId, AdminPermission.GUIDE_MANAGE);
        CityGuide g = requireGuide(guideId);
        if (!CityGuide.STATUS_PENDING_REVIEW.equals(g.getStatus())) {
            throw new IllegalArgumentException("仅待审核攻略可执行发布");
        }
        if (g.getCurrentRevisionId() == null) {
            throw new IllegalArgumentException("攻略缺少内容版本，无法发布");
        }
        g.setStatus(CityGuide.STATUS_PUBLISHED);
        g.setReviewedBy(adminId);
        g.setPublishedAt(LocalDateTime.now());
        // rag_status 保持现状即为真实状态：首次发布 NOT_INDEXED；旧版在线 READY（新版本激活中）
        guideRepository.updateById(g);
        RagIndexTask task = registerRagTask(g, adminId);
        auditService.record(adminId, AuditLog.CAT_ADMIN, "guide_published", "guide",
                String.valueOf(guideId), AuditService.detailOf("city", g.getCity(),
                        "revision", g.getVersion(), "index_task", task.getId(),
                        "activated_revision", g.getPublishedRevisionId()));
        log.info("攻略发布（等待 RAG 索引成功后切换线上版本）: guideId={} revision={} by={}",
                guideId, g.getVersion(), adminId);
    }

    /** 审核拒绝：PENDING_REVIEW → REJECTED（原因随审计落库，作者可见） */
    @Transactional
    public void reject(String adminId, Long guideId, String reason) {
        communityUserService.requirePermission(adminId, AdminPermission.GUIDE_MANAGE);
        CityGuide g = requireGuide(guideId);
        if (!CityGuide.STATUS_PENDING_REVIEW.equals(g.getStatus())) {
            throw new IllegalArgumentException("仅待审核攻略可执行拒绝");
        }
        g.setStatus(CityGuide.STATUS_REJECTED);
        g.setRejectReason(reason == null || reason.isBlank() ? "内容不符合运营规范" : reason.trim());
        guideRepository.updateById(g);
        markRevisionStatus(g, CityGuideRevision.STATUS_REJECTED);
        auditService.record(adminId, AuditLog.CAT_ADMIN, "guide_rejected", "guide",
                String.valueOf(guideId), AuditService.detailOf("reason", g.getRejectReason()));
    }

    /**
     * 下线：PUBLISHED → HIDDEN（保留已发布版本，可恢复）。
     * 从 RAG 检索源移除并重置 rag 状态；排队的索引任务一并作废（SUPERSEDED），
     * 防止执行器在内容下线后仍把版本写回检索源。
     */
    @Transactional
    public void hide(String adminId, Long guideId) {
        communityUserService.requirePermission(adminId, AdminPermission.GUIDE_MANAGE);
        CityGuide g = requireGuide(guideId);
        if (!CityGuide.STATUS_PUBLISHED.equals(g.getStatus())) {
            throw new IllegalArgumentException("仅已发布攻略可下线");
        }
        g.setStatus(CityGuide.STATUS_HIDDEN);
        guideRepository.updateById(g);
        // 下线 = 不再公开消费：同步从 RAG 检索源移除（若已索引）并作废未执行任务
        if (ragService != null) {
            ragService.removeGuideSource(guideSourceName(g));
        }
        supersedePendingTasks(guideId, "攻略下线");
        clearRagState(g);
        auditService.record(adminId, AuditLog.CAT_ADMIN, "guide_hidden", "guide",
                String.valueOf(guideId), AuditService.detailOf("city", g.getCity()));
    }

    /**
     * 恢复公开：HIDDEN → PUBLISHED。
     *
     * <p>下线时已移除 RAG 检索源且旧任务全部作废，原任务可能已是 READY/SUPERSEDED——恢复必须
     * <b>重新登记</b> PENDING 任务（P1 修复）让执行器重建检索源，不能依赖既有 PENDING 任务。
     * 目标版本 = 当前最新版本（= 下线前最后一次审核通过的版本；已下线内容编辑会先转待审，故不在此路径）。
     */
    @Transactional
    public void restore(String adminId, Long guideId) {
        communityUserService.requirePermission(adminId, AdminPermission.GUIDE_MANAGE);
        CityGuide g = requireGuide(guideId);
        if (!CityGuide.STATUS_HIDDEN.equals(g.getStatus())) {
            throw new IllegalArgumentException("仅已下线攻略可恢复");
        }
        if (g.getCurrentRevisionId() == null) {
            throw new IllegalArgumentException("该攻略没有可恢复的内容版本");
        }
        g.setStatus(CityGuide.STATUS_PUBLISHED);
        guideRepository.updateById(g);
        auditService.record(adminId, AuditLog.CAT_ADMIN, "guide_restored", "guide",
                String.valueOf(guideId), AuditService.detailOf("city", g.getCity()));
        // 恢复公开：确保存在针对当前版本的 PENDING 索引任务；异步执行放事务提交后
        // （否则执行器在提交前查不到刚登记的任务，恢复会静默不生效——审查 P1 根因）
        if (indexingService != null) {
            ensurePendingTaskForCurrent(g, adminId);
            triggerIndexAfterCommit(guideId);
        }
    }

    /** 归档：PUBLISHED/HIDDEN → ARCHIVED（长期留存，停止公开消费并从 RAG 检索源移除） */
    @Transactional
    public void archive(String adminId, Long guideId) {
        communityUserService.requirePermission(adminId, AdminPermission.GUIDE_MANAGE);
        CityGuide g = requireGuide(guideId);
        if (!CityGuide.STATUS_PUBLISHED.equals(g.getStatus())
                && !CityGuide.STATUS_HIDDEN.equals(g.getStatus())) {
            throw new IllegalArgumentException("仅已发布/已下线攻略可归档");
        }
        g.setStatus(CityGuide.STATUS_ARCHIVED);
        guideRepository.updateById(g);
        if (ragService != null) {
            ragService.removeGuideSource(guideSourceName(g));
        }
        supersedePendingTasks(guideId, "攻略归档");
        clearRagState(g);
        auditService.record(adminId, AuditLog.CAT_ADMIN, "guide_archived", "guide",
                String.valueOf(guideId), AuditService.detailOf("city", g.getCity(),
                        "title", g.getTitle()));
        log.info("攻略归档: guideId={} by={}", guideId, adminId);
    }

    /** 取消归档：ARCHIVED → DRAFT（保留版本历史，可继续编辑/提交/发布） */
    @Transactional
    public void unarchive(String adminId, Long guideId) {
        communityUserService.requirePermission(adminId, AdminPermission.GUIDE_MANAGE);
        CityGuide g = requireGuide(guideId);
        if (!CityGuide.STATUS_ARCHIVED.equals(g.getStatus())) {
            throw new IllegalArgumentException("仅已归档攻略可取消归档");
        }
        g.setStatus(CityGuide.STATUS_DRAFT);
        g.setRejectReason(null);
        guideRepository.updateById(g);
        auditService.record(adminId, AuditLog.CAT_ADMIN, "guide_unarchived", "guide",
                String.valueOf(guideId), AuditService.detailOf("city", g.getCity()));
    }

    /**
     * 复制为新版本（fork）：以当前正文另起一条全新攻略（DRAFT + v1），不触碰原攻略。
     * 用于：想要基于线上内容开一条新的编辑/重排版线，或归档内容要重新打磨。
     *
     * @return 新攻略 id
     */
    @Transactional
    public Long copy(String adminId, Long guideId) {
        communityUserService.requirePermission(adminId, AdminPermission.GUIDE_MANAGE);
        CityGuide src = requireGuide(guideId);
        CityGuideRevision current = src.getCurrentRevisionId() == null ? null
                : revisionRepository.selectById(src.getCurrentRevisionId());
        String content = current == null ? null : current.getContentMarkdown();
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("源攻略没有可复制的内容版本");
        }
        CityGuide g = new CityGuide();
        g.setCity(src.getCity());
        g.setTitle(src.getTitle());
        g.setSummary(src.getSummary());
        g.setCoverImage(src.getCoverImage());
        // 副本走人工编辑线：保留来源类型标注，但 source_file 置空（UNIQUE 约束 + 不与溯源文件绑定）
        g.setSourceType(CityGuide.SOURCE_CURATED.equals(src.getSourceType())
                ? CityGuide.SOURCE_CURATED
                : src.getSourceType() == null ? CityGuide.SOURCE_CURATED : src.getSourceType());
        g.setSourceName("复制自攻略 #" + guideId + "（v" + (src.getVersion() == null ? 0 : src.getVersion()) + "）");
        g.setSourceFile(null);
        g.setAuthor(communityUserService.nicknameOf(adminId));
        g.setStatus(CityGuide.STATUS_DRAFT);
        g.setQualityScore(0);
        g.setVersion(0);
        g.setRagStatus(CityGuide.RAG_NOT_INDEXED);
        guideRepository.insert(g);
        appendRevision(g, adminId, content, "复制自攻略 #" + guideId + "，开启新编辑线");
        guideRepository.updateById(g);
        auditService.record(adminId, AuditLog.CAT_ADMIN, "guide_copied", "guide",
                String.valueOf(guideId), AuditService.detailOf("from_guide", guideId,
                        "to_guide", g.getId(), "city", g.getCity()));
        log.info("攻略复制为新版本: from={} to={} by={}", guideId, g.getId(), adminId);
        return g.getId();
    }

    /**
     * 回滚：撤销一次错误编辑/错误发布，把目标版本重新立为线上（两段式发布语义下分三种场景）。
     *
     * <ul>
     *   <li>待审核（对已发布内容的编辑）：放弃本次编辑 → 恢复 PUBLISHED + 已激活版本（取消待审，秒回线上）；</li>
     *   <li>已发布且线上版本尚未切换（current ≠ published，发布在等 RAG 激活/已失败）：
     *       取消这次未生效的发布 → 回到仍在线服务的已激活版本（已索引则无需重建）；
     *       首次发布尚未激活（published 为空）→ 退回 PENDING_REVIEW（取消发布，避免半公开）；</li>
     *   <li>已发布/已下线且新版本已激活（current == published）：错误发布已生效 → 换回更早一个
     *       已激活版本并重建 RAG 索引。</li>
     * </ul>
     * 历史版本 append-only 保留，回滚本身产生审计；过期 PENDING 任务按 revision 清理/作废，
     * 防止执行器把被放弃的版本写回线上。
     *
     * @return 回滚后的目标版本号（无上一版本可回滚的取消场景返回当前版本号）
     */
    @Transactional
    public int rollback(String adminId, Long guideId) {
        communityUserService.requirePermission(adminId, AdminPermission.GUIDE_MANAGE);
        CityGuide g = requireGuide(guideId);
        Long fromId = g.getCurrentRevisionId();
        if (CityGuide.STATUS_PENDING_REVIEW.equals(g.getStatus())) {
            // 场景 A：放弃待审编辑，回到已发布的版本（编辑时旧版本一直在线上服务）
            if (g.getPublishedRevisionId() == null) {
                throw new IllegalArgumentException("首次发布的待审攻略没有上一已发布版本，请直接在编辑页修改");
            }
            return rollbackTo(g, adminId, g.getPublishedRevisionId(),
                    CityGuide.STATUS_PUBLISHED, "放弃待审编辑，回到已发布版本", fromId, false, false);
        }
        if (!CityGuide.STATUS_PUBLISHED.equals(g.getStatus())
                && !CityGuide.STATUS_HIDDEN.equals(g.getStatus())) {
            throw new IllegalArgumentException("当前状态不支持回滚（仅待审核/已发布/已下线）");
        }
        if (g.getPublishedRevisionId() == null) {
            // 场景 B0：PUBLISHED 但从未激活（首次发布在等 RAG 或已失败）→ 取消发布退回待审，
            // 作废其 PENDING 任务并清空 RAG 状态，避免执行器迟到把版本写回检索源
            if (fromId == null) {
                throw new IllegalArgumentException("该攻略没有任何版本，无法回滚");
            }
            supersedePendingTasks(guideId, "回滚取消未激活的首次发布");
            clearRagState(g);
            g.setStatus(CityGuide.STATUS_PENDING_REVIEW);
            g.setRejectReason("回滚取消（该版本从未进入线上服务）");
            guideRepository.updateById(g);
            auditService.record(adminId, AuditLog.CAT_ADMIN, "guide_rolled_back", "guide",
                    String.valueOf(guideId), AuditService.detailOf("from_revision", fromId,
                            "to_status", "PENDING_REVIEW", "note", "取消未生效的首次发布"));
            log.info("攻略回滚（取消未生效的首次发布）: guideId={} by={}", guideId, adminId);
            CityGuideRevision cur = revisionRepository.selectById(fromId);
            return cur != null && cur.getRevisionNo() != null ? cur.getRevisionNo() : 0;
        }
        boolean lagging = !g.getPublishedRevisionId().equals(g.getCurrentRevisionId());
        if (lagging) {
            // 场景 B1：线上仍是旧版（发布在等 RAG 激活或已失败）→ 取消这次未生效的发布，
            // 回到仍在线服务的已激活版本；已索引则无需重建（RAG 从未切走）
            boolean needIndex = !isRevisionIndexed(g, g.getPublishedRevisionId());
            if (!needIndex) {
                supersedePendingTasks(guideId, "回滚取消未生效的发布");
            }
            return rollbackTo(g, adminId, g.getPublishedRevisionId(), CityGuide.STATUS_PUBLISHED,
                    "取消未生效的发布，回到已激活版本", fromId, needIndex, needIndex);
        }
        // 场景 B2：新版本已激活（current == published），错误发布已生效 → 找更早一个已激活版本
        List<CityGuideRevision> revs = revisionRepository.selectList(
                new LambdaQueryWrapper<CityGuideRevision>()
                        .eq(CityGuideRevision::getGuideId, guideId)
                        .orderByDesc(CityGuideRevision::getRevisionNo));
        Long targetId = null;
        for (CityGuideRevision r : revs) {
            if (CityGuideRevision.STATUS_PUBLISHED.equals(r.getStatus())
                    && !r.getId().equals(g.getPublishedRevisionId())) {
                targetId = r.getId();
                break;
            }
        }
        if (targetId == null) {
            throw new IllegalArgumentException("没有更早的已发布版本可回滚");
        }
        return rollbackTo(g, adminId, targetId, CityGuide.STATUS_PUBLISHED,
                "回滚到上一已发布版本", fromId, true, true);
    }

    /** 回滚统一落点：把 target 版本立为 current/published（可选重建索引）；返回目标版本号 */
    private int rollbackTo(CityGuide g, String adminId, Long targetId, String toStatus,
                           String note, Long fromId, boolean needIndex, boolean registerTask) {
        CityGuideRevision target = revisionRepository.selectById(targetId);
        if (target == null || target.getContentMarkdown() == null) {
            throw new IllegalStateException("回滚目标版本缺失正文");
        }
        g.setCurrentRevisionId(targetId);
        g.setPublishedRevisionId(targetId);
        g.setContentMarkdown(target.getContentMarkdown());
        g.setStatus(toStatus);
        if (needIndex) {
            // 被放弃/被换下的版本若有 PENDING 任务 → 清理/作废，避免执行器把旧内容写回线上
            supersedePendingTasks(g.getId(), "回滚换版");
            g.setRagStatus(CityGuide.RAG_NOT_INDEXED);
        }
        guideRepository.updateById(g);
        if (registerTask) {
            RagIndexTask task = registerRagTask(g, adminId);
            auditService.record(adminId, AuditLog.CAT_ADMIN, "guide_rolled_back", "guide",
                    String.valueOf(g.getId()), AuditService.detailOf("from_revision", fromId,
                            "to_revision", targetId, "note", note, "index_task", task.getId()));
            triggerIndexAfterCommit(g.getId());
        } else {
            auditService.record(adminId, AuditLog.CAT_ADMIN, "guide_rolled_back", "guide",
                    String.valueOf(g.getId()), AuditService.detailOf("from_revision", fromId,
                            "to_revision", targetId, "note", note));
        }
        log.info("攻略回滚: guideId={} {} revision={} by={}", g.getId(), note, targetId, adminId);
        return target.getRevisionNo() == null ? 0 : target.getRevisionNo();
    }

    /* ================= 幂等导入（Markdown → 数据库） ================= */

    /** 静态 Markdown 幂等导入：同一 source_file + content_hash 不重复建版；文件变化 append 待审新版本 */
    public Map<String, Object> importFromClasspath(String adminId) {
        communityUserService.requirePermission(adminId, AdminPermission.GUIDE_MANAGE);
        int imported = 0;
        int changedPending = 0;
        int unchanged = 0;
        List<String> files = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:guides/*.md");
            for (Resource r : resources) {
                String name = r.getFilename();
                if (name == null || name.startsWith("_")) {
                    continue;
                }
                String content = StreamUtils.copyToString(r.getInputStream(), StandardCharsets.UTF_8);
                GuideMarkdownParser.ParsedGuide parsed = GuideMarkdownParser.parse(content, name);
                String hash = GuideMarkdownParser.sha256(content);
                files.add(name);
                String result = importOne(adminId, name, parsed, content, hash);
                if ("imported".equals(result)) {
                    imported++;
                } else if ("pending".equals(result)) {
                    changedPending++;
                } else {
                    unchanged++;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("攻略 Markdown 导入失败: " + e.getMessage(), e);
        }
        auditService.record(adminId, AuditLog.CAT_ADMIN, "guide_imported", "guide", null,
                AuditService.detailOf("files", files.size(), "imported", imported,
                        "changed_pending", changedPending, "unchanged", unchanged));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("files", files);
        body.put("imported", imported);
        body.put("changed_pending", changedPending);
        body.put("unchanged", unchanged);
        return body;
    }

    /** @return imported=新建发布 / pending=文件变更入待审 / unchanged=内容未变跳过 */
    @Transactional
    protected String importOne(String adminId, String sourceFile,
                               GuideMarkdownParser.ParsedGuide parsed,
                               String content, String hash) {
        CityGuide existing = guideRepository.selectOne(new LambdaQueryWrapper<CityGuide>()
                .eq(CityGuide::getSourceFile, sourceFile).last("LIMIT 1"));
        if (existing == null) {
            CityGuide g = new CityGuide();
            g.setCity(parsed.city());
            g.setTitle(parsed.title());
            g.setSummary(trimToNull(parsed.summary()));
            g.setContentMarkdown(content);
            g.setSourceType(CityGuide.SOURCE_SYSTEM);
            g.setSourceName("系统初始化 Markdown");
            g.setSourceFile(sourceFile);
            g.setAuthor(communityUserService.nicknameOf(adminId));
            // 首轮受控导入：与线上 Markdown 同内容，直接进入 PUBLISHED（published_revision=current）
            g.setStatus(CityGuide.STATUS_PUBLISHED);
            g.setQualityScore(0);
            g.setVersion(0);
            g.setRagStatus(CityGuide.RAG_NOT_INDEXED);
            g.setReviewedBy(adminId);
            g.setPublishedAt(LocalDateTime.now());
            guideRepository.insert(g);
            appendRevision(g, adminId, content, "Markdown 初始化导入");
            g.setPublishedRevisionId(g.getCurrentRevisionId());
            guideRepository.updateById(g);
            RagIndexTask task = registerRagTask(g, adminId);
            auditService.record(adminId, AuditLog.CAT_ADMIN, "guide_imported", "guide",
                    String.valueOf(g.getId()), AuditService.detailOf("source_file", sourceFile,
                            "city", g.getCity(), "action", "imported", "index_task", task.getId()));
            return "imported";
        }
        CityGuideRevision current = existing.getCurrentRevisionId() == null ? null
                : revisionRepository.selectById(existing.getCurrentRevisionId());
        if (current != null && hash.equals(current.getContentHash())) {
            return "unchanged";
        }
        // 文件内容变化：append 新版本进入待审（不静默覆盖线上/草稿，管理员审核后发布）
        CityGuideRevision rev = appendRevision(existing, adminId, content, "Markdown 文件更新导入");
        if (!CityGuide.STATUS_PENDING_REVIEW.equals(existing.getStatus())
                && !CityGuide.STATUS_PUBLISHED.equals(existing.getStatus())
                && !CityGuide.STATUS_HIDDEN.equals(existing.getStatus())) {
            existing.setStatus(CityGuide.STATUS_PENDING_REVIEW);
            existing.setSubmittedBy(adminId);
        } else if (CityGuide.STATUS_PUBLISHED.equals(existing.getStatus())
                || CityGuide.STATUS_HIDDEN.equals(existing.getStatus())) {
            existing.setStatus(CityGuide.STATUS_PENDING_REVIEW);
            existing.setSubmittedBy(adminId);
        }
        guideRepository.updateById(existing);
        markRevisionStatus(existing, CityGuideRevision.STATUS_PENDING_REVIEW);
        auditService.record(adminId, AuditLog.CAT_ADMIN, "guide_imported", "guide",
                String.valueOf(existing.getId()), AuditService.detailOf("source_file", sourceFile,
                        "revision", rev.getRevisionNo(), "action", "file_changed_pending"));
        return "pending";
    }

    /* ================= 内部 ================= */

    /** append 一个不可变版本 + 结构化关联（spots/tags），并推进 guide.version / current_revision_id */
    private CityGuideRevision appendRevision(CityGuide g, String editorId, String content, String changeSummary) {
        int revisionNo = (g.getVersion() == null ? 0 : g.getVersion()) + 1;
        g.setVersion(revisionNo);
        CityGuideRevision rev = new CityGuideRevision();
        rev.setGuideId(g.getId());
        rev.setRevisionNo(revisionNo);
        rev.setContentHash(GuideMarkdownParser.sha256(content));
        rev.setContentMarkdown(content);
        rev.setChangeSummary(trimToNull(changeSummary));
        rev.setEditorId(editorId);
        rev.setStatus(g.getStatus());
        revisionRepository.insert(rev);
        g.setCurrentRevisionId(rev.getId());
        g.setContentMarkdown(content);
        g.setQualityScore(scoreQuality(g, content, revisionNo));
        // 结构化骨架：本版本景点/标签解析结果
        GuideMarkdownParser.ParsedGuide parsed = GuideMarkdownParser.parse(content, g.getSourceFile());
        replaceStructured(g.getId(), rev.getId(), parsed);
        return rev;
    }

    private void replaceStructured(Long guideId, Long revisionId, GuideMarkdownParser.ParsedGuide parsed) {
        spotRepository.delete(new LambdaUpdateWrapper<CityGuideSpot>()
                .eq(CityGuideSpot::getGuideId, guideId)
                .eq(CityGuideSpot::getRevisionId, revisionId));
        List<CityGuideSpot> spots = new ArrayList<>();
        int order = 0;
        for (String name : parsed.spotNames()) {
            CityGuideSpot s = new CityGuideSpot();
            s.setGuideId(guideId);
            s.setRevisionId(revisionId);
            s.setSpotName(name);
            s.setSortOrder(order++);
            spots.add(s);
        }
        for (CityGuideSpot s : spots) {
            spotRepository.insert(s);
        }
        tagRepository.delete(new LambdaUpdateWrapper<CityGuideTag>()
                .eq(CityGuideTag::getGuideId, guideId)
                .eq(CityGuideTag::getRevisionId, revisionId));
        List<CityGuideTag> tags = new ArrayList<>();
        for (String tag : parsed.tags()) {
            CityGuideTag t = new CityGuideTag();
            t.setGuideId(guideId);
            t.setRevisionId(revisionId);
            t.setCategory("travel_style");
            t.setTag(tag);
            t.setSource("CONTENT_PARSE");
            tags.add(t);
        }
        for (CityGuideTag t : tags) {
            tagRepository.insert(t);
        }
    }

    private void markRevisionStatus(CityGuide g, String status) {
        if (g.getCurrentRevisionId() == null) {
            return;
        }
        revisionRepository.update(null, new LambdaUpdateWrapper<CityGuideRevision>()
                .eq(CityGuideRevision::getId, g.getCurrentRevisionId())
                .set(CityGuideRevision::getStatus, status));
    }

    /**
     * 登记 RAG 索引任务（PENDING，交由执行器消费；按 revision 绑定，杜绝旧任务覆盖新版本）
     */
    private RagIndexTask registerRagTask(CityGuide g, String actor) {
        RagIndexTask task = new RagIndexTask();
        task.setGuideId(g.getId());
        task.setRevisionId(g.getCurrentRevisionId());
        task.setStatus(RagIndexTask.STATUS_PENDING);
        task.setTriggeredBy(actor);
        taskRepository.insert(task);
        return task;
    }

    /**
     * 事务提交后再触发异步索引（恢复/回滚换版登记任务后调用）：
     * 直接在本方法内调 @Async 会与事务并发——执行器可能在任务行提交前查询（SELECT 不到）
     * 而静默跳过，留下永远 PENDING 的任务。注册 afterCommit 回调保证执行器一定能看到任务；
     * 无活动事务（单元测试直调）时立即执行。
     */
    private void triggerIndexAfterCommit(Long guideId) {
        Runnable fire = () -> {
            if (indexingService != null) {
                indexingService.indexPendingAsync(guideId);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    fire.run();
                }
            });
        } else {
            fire.run();
        }
    }

    /**
     * 作废该攻略全部 PENDING 索引任务（下线/归档/回滚换版时调用）：
     * 这些任务登记的版本已不再是有效目标，若执行器恰好排队执行会写回过期内容。
     * 改为 SUPERSEDED 而非删除，保留可审计的任务轨迹。
     */
    private void supersedePendingTasks(Long guideId, String reason) {
        taskRepository.update(null, new LambdaUpdateWrapper<RagIndexTask>()
                .eq(RagIndexTask::getGuideId, guideId)
                .eq(RagIndexTask::getStatus, RagIndexTask.STATUS_PENDING)
                .set(RagIndexTask::getStatus, RagIndexTask.STATUS_SUPERSEDED)
                .set(RagIndexTask::getErrorMessage, reason)
                .set(RagIndexTask::getFinishedAt, LocalDateTime.now()));
    }

    /**
     * 确保存在针对当前版本的 PENDING 任务（恢复公开用，P1 修复）：
     * 下线/归档时旧任务已作废，原任务可能是 READY/SUPERSEDED/FAILED——若已有一个针对
     * 当前版本且仍未执行的 PENDING 任务则复用，否则新建，保证执行器「恢复即重建检索源」。
     */
    private void ensurePendingTaskForCurrent(CityGuide g, String actor) {
        RagIndexTask existing = taskRepository.selectOne(new LambdaQueryWrapper<RagIndexTask>()
                .eq(RagIndexTask::getGuideId, g.getId())
                .eq(RagIndexTask::getStatus, RagIndexTask.STATUS_PENDING)
                .eq(RagIndexTask::getRevisionId, g.getCurrentRevisionId())
                .last("LIMIT 1"));
        if (existing == null) {
            registerRagTask(g, actor);
        }
    }

    /** 攻略不再公开消费（下线/归档）时重置 RAG 派生状态：检索源已移除，不再宣称已索引 */
    private void clearRagState(CityGuide g) {
        guideRepository.update(null, new LambdaUpdateWrapper<CityGuide>()
                .eq(CityGuide::getId, g.getId())
                .set(CityGuide::getRagStatus, CityGuide.RAG_NOT_INDEXED)
                .set(CityGuide::getRagIndexedRevision, null));
    }

    private List<CityGuideSpot> listSpots(Long guideId, Long revisionId) {
        return spotRepository.selectList(new LambdaQueryWrapper<CityGuideSpot>()
                .eq(CityGuideSpot::getGuideId, guideId)
                .eq(CityGuideSpot::getRevisionId, revisionId)
                .orderByAsc(CityGuideSpot::getSortOrder));
    }

    private List<CityGuideTag> listTags(Long guideId, Long revisionId) {
        return tagRepository.selectList(new LambdaQueryWrapper<CityGuideTag>()
                .eq(CityGuideTag::getGuideId, guideId)
                .eq(CityGuideTag::getRevisionId, revisionId)
                .orderByAsc(CityGuideTag::getId));
    }

    private List<Map<String, Object>> revisionList(Long guideId) {
        List<CityGuideRevision> rows = revisionRepository.selectList(
                new LambdaQueryWrapper<CityGuideRevision>()
                        .eq(CityGuideRevision::getGuideId, guideId)
                        .orderByDesc(CityGuideRevision::getRevisionNo)
                        .last("LIMIT 30"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CityGuideRevision r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("revision_no", r.getRevisionNo());
            m.put("hash", shortHash(r.getContentHash()));
            m.put("status", r.getStatus());
            m.put("change_summary", r.getChangeSummary());
            m.put("editor_id", r.getEditorId());
            m.put("created_at", fmt(r.getCreatedAt()));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> ragTasks(Long guideId) {
        List<RagIndexTask> rows = taskRepository.selectList(
                new LambdaQueryWrapper<RagIndexTask>()
                        .eq(RagIndexTask::getGuideId, guideId)
                        .orderByDesc(RagIndexTask::getId)
                        .last("LIMIT 5"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (RagIndexTask t : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("revision_id", t.getRevisionId());
            m.put("status", t.getStatus());
            m.put("error_message", t.getErrorMessage());
            m.put("triggered_by", t.getTriggeredBy());
            m.put("created_at", fmt(t.getCreatedAt()));
            m.put("finished_at", fmt(t.getFinishedAt()));
            out.add(m);
        }
        return out;
    }

    /** 单个城市 spot 主档的「标准化名称 → Spot」索引（攻略名匹配用；未注入仓储时返回空） */
    private Map<String, Spot> spotIndexFor(String city) {
        if (spotMainRepository == null || city == null || city.isBlank()) {
            return Map.of();
        }
        List<Spot> spots = spotMainRepository.selectList(new LambdaQueryWrapper<Spot>()
                .eq(Spot::getCity, city));
        Map<String, Spot> index = new HashMap<>();
        for (Spot s : spots) {
            if (s.getNormalizedName() != null && !s.getNormalizedName().isBlank()) {
                index.putIfAbsent(s.getNormalizedName(), s);
            }
        }
        return index;
    }

    /** 多城市批量索引（攻略列表页一次查出全部城市再分组） */
    private Map<String, Map<String, Spot>> spotIndexByCity(Set<String> cities) {
        Map<String, Map<String, Spot>> out = new HashMap<>();
        if (cities.isEmpty()) {
            return out;
        }
        List<Spot> spots = spotMainRepository.selectList(new LambdaQueryWrapper<Spot>()
                .in(Spot::getCity, cities));
        for (Spot s : spots) {
            if (s.getNormalizedName() == null || s.getNormalizedName().isBlank()) {
                continue;
            }
            out.computeIfAbsent(s.getCity(), k -> new HashMap<>())
                    .putIfAbsent(s.getNormalizedName(), s);
        }
        return out;
    }

    /** 指定 revision 是否已成功索引且仍在索引（回滚时判断是否需要重建） */
    private boolean isRevisionIndexed(CityGuide g, Long revisionId) {
        return CityGuide.RAG_READY.equals(g.getRagStatus())
                && revisionId != null && revisionId.equals(g.getRagIndexedRevision());
    }

    private Map<String, Object> guideBrief(CityGuide g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getId());
        m.put("city", g.getCity());
        m.put("title", g.getTitle());
        m.put("summary", g.getSummary());
        m.put("cover_image", g.getCoverImage());
        m.put("source_type", g.getSourceType());
        m.put("source_name", g.getSourceName());
        m.put("source_file", g.getSourceFile());
        m.put("author", g.getAuthor());
        m.put("status", g.getStatus());
        m.put("quality_score", g.getQualityScore() == null ? 0 : g.getQualityScore());
        m.put("version", g.getVersion() == null ? 0 : g.getVersion());
        m.put("reject_reason", g.getRejectReason());
        m.put("rag_status", g.getRagStatus());
        m.put("rag_indexed_revision", g.getRagIndexedRevision());
        m.put("published_revision_id", g.getPublishedRevisionId());
        m.put("current_revision_id", g.getCurrentRevisionId());
        m.put("published_at", fmt(g.getPublishedAt()));
        m.put("created_at", fmt(g.getCreatedAt()));
        m.put("updated_at", fmt(g.getUpdatedAt()));
        return m;
    }

    private CityGuide requireGuide(Long guideId) {
        CityGuide g = guideId == null ? null : guideRepository.selectById(guideId);
        if (g == null) {
            throw new PostNotFoundException("攻略不存在: " + guideId);
        }
        return g;
    }

    private void validate(GuideEdit req) {
        if (req == null) {
            throw new IllegalArgumentException("缺少攻略内容");
        }
        if (req.city() == null || req.city().isBlank()) {
            throw new IllegalArgumentException("请填写攻略城市");
        }
        if (req.title() == null || req.title().isBlank()) {
            throw new IllegalArgumentException("请填写攻略标题");
        }
        if (req.contentMarkdown() == null || req.contentMarkdown().isBlank()) {
            throw new IllegalArgumentException("攻略正文不能为空");
        }
        if (req.title().trim().length() > 200) {
            throw new IllegalArgumentException("标题最长 200 字");
        }
    }

    /** 确定性质量分 0~100（骨架规则：标题/城市/摘要/正文结构/景点卡片） */
    private int scoreQuality(CityGuide g, String content, int revisionNo) {
        int score = 20; // 有标题与城市
        if (g.getSummary() != null && g.getSummary().length() >= 20) {
            score += 15;
        }
        int h2 = 0;
        int h3 = 0;
        for (String line : content.split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith("## ")) {
                h2++;
            } else if (t.startsWith("### ")) {
                h3++;
            }
        }
        score += Math.min(30, h2 * 10);
        score += Math.min(25, (h3 >= 3 ? 15 : 0) + (h3 >= 6 ? 10 : 0));
        if (content.length() >= 2000) {
            score += 10;
        }
        return Math.min(100, score);
    }

    /** 编辑请求载体（controller 直接构造） */
    public record GuideEdit(String city, String title, String summary, String coverImage,
                            String sourceType, String sourceName, String contentMarkdown,
                            String changeSummary) {
    }

    /** 攻略在 RAG 检索源中的命名：优先溯源文件名（与启动 classpath 同 key），无则 guide-{id}.md */
    private static String guideSourceName(CityGuide g) {
        return g.getSourceFile() != null && !g.getSourceFile().isBlank()
                ? g.getSourceFile() : "guide-" + g.getId() + ".md";
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String shortHash(String hash) {
        return hash == null || hash.length() <= 12 ? hash : hash.substring(0, 12);
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? null : t.format(TS);
    }
}
