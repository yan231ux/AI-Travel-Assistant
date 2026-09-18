package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.yuntu.tripplanner.common.AbBucket;
import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.common.ContentRuleChecker;
import com.yuntu.tripplanner.model.ContentModerationTask;
import com.yuntu.tripplanner.common.PostDuplicateDetector;
import com.yuntu.tripplanner.common.PostQualityScorer;
import com.yuntu.tripplanner.exception.ForbiddenException;
import com.yuntu.tripplanner.exception.PostNotFoundException;
import com.yuntu.tripplanner.model.AbExperiment;
import com.yuntu.tripplanner.model.AuditLog;
import com.yuntu.tripplanner.model.PostAuthor;
import com.yuntu.tripplanner.model.PostCreateRequest;
import com.yuntu.tripplanner.model.PostDetail;
import com.yuntu.tripplanner.model.PostInteraction;
import com.yuntu.tripplanner.model.PostItem;
import com.yuntu.tripplanner.model.PostPage;
import com.yuntu.tripplanner.model.PostSpot;
import com.yuntu.tripplanner.model.PostSpotRef;
import com.yuntu.tripplanner.model.PostTag;
import com.yuntu.tripplanner.model.PostUpdateRequest;
import com.yuntu.tripplanner.model.Spot;
import com.yuntu.tripplanner.model.TravelPost;
import com.yuntu.tripplanner.model.TravelPostRevision;
import com.yuntu.tripplanner.model.UserPreference;
import com.yuntu.tripplanner.repository.PostInteractionRepository;
import com.yuntu.tripplanner.repository.PostSpotRepository;
import com.yuntu.tripplanner.repository.SpotRepository;
import com.yuntu.tripplanner.repository.TravelPostRepository;
import com.yuntu.tripplanner.repository.TravelPostRevisionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 帖子服务（阶段二社区核心：创建/编辑/删除/提交审核/公开流/我的帖子/管理员审核）。
 *
 * <p>状态机（§7.3）：DRAFT → PENDING_REVIEW → PUBLISHED；REJECTED 可改后重提；
 * HIDDEN/DELETED 不进公开流。所有"作者本人/管理员"权限在此服务端校验（§10.3/§10.4），
 * 公开流只放行 {@link TravelPost#STATUS_PUBLISHED}（§11.3：未审核/隐藏/删除一律不出现）。
 * 点赞/收藏/浏览计数只做展示，幂等由 PostInteraction 唯一键保证（计数器更新在
 * {@link PostInteractionService}，不在本服务）。
 */
@Slf4j
@Service
public class PostService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** A/B 处理组过滤后候选下限：低于该数整体回退（低质实验不让社区流空场） */
    private static final int MIN_RECOMMEND_POOL = 6;

    private final TravelPostRepository postRepository;
    private final PostSpotRepository postSpotRepository;
    private final PostInteractionRepository interactionRepository;
    private final SpotRepository spotRepository;
    private final CommunityUserService communityUserService;
    private final ContentRuleChecker ruleChecker;
    /** 画像读取（阶段三：recommended 排序判定 + 曝光日志画像版本） */
    private final UserProfileService userProfileService;
    /** 帖子标签批量补齐（阶段三：recommended 排序的标签底座） */
    private final PostTagService postTagService;
    /** 帖子推荐引擎（阶段三：打分排序 + 曝光证据日志） */
    private final PostFeedEngine postFeedEngine;
    /** A/B 实验（阶段四任务 6：帖子推荐流低质过滤实验） */
    private final AbExperimentService abExperimentService;
    /** 城市名校验（2026-09-13：发帖/编辑城市字段归一化，杜绝"1"/"火星"等垃圾城市进库污染画像标签） */
    private final CityValidator cityValidator;
    /** 全链路审计（阶段四任务 10：内容发布/审核动作留痕） */
    private final AuditService auditService;
    /** 帖子编辑版本（审查报告 P1-1：公开版本 / 编辑版本分离） */
    private final TravelPostRevisionRepository revisionRepository;

    /** 关联景点快照的 JSON 编解码（PostSpotRef 仅字符串字段，无需额外模块） */
    private static final ObjectMapper SPOTS_MAPPER = new ObjectMapper();
    /**
     * AI 内容审核（阶段三：提交审核后异步初筛）。可选 setter 注入 —— 不能走构造器：
     * ContentModerationService 决策回调又依赖 PostService（setter），构造环会让
     * Spring 报 "currently in creation" 无法启动。
     */
    @Autowired(required = false)
    private ContentModerationService moderationService;

    public PostService(TravelPostRepository postRepository,
                       PostSpotRepository postSpotRepository,
                       PostInteractionRepository interactionRepository,
                       SpotRepository spotRepository,
                       CommunityUserService communityUserService,
                       ContentRuleChecker ruleChecker,
                       UserProfileService userProfileService,
                       PostTagService postTagService,
                       PostFeedEngine postFeedEngine,
                       AbExperimentService abExperimentService,
                       CityValidator cityValidator,
                       AuditService auditService,
                       TravelPostRevisionRepository revisionRepository) {
        this.postRepository = postRepository;
        this.postSpotRepository = postSpotRepository;
        this.interactionRepository = interactionRepository;
        this.spotRepository = spotRepository;
        this.communityUserService = communityUserService;
        this.ruleChecker = ruleChecker;
        this.userProfileService = userProfileService;
        this.postTagService = postTagService;
        this.postFeedEngine = postFeedEngine;
        this.abExperimentService = abExperimentService;
        this.cityValidator = cityValidator;
        this.auditService = auditService;
        this.revisionRepository = revisionRepository;
    }

    /* ================= 创建 / 编辑 / 删除（作者本人） ================= */

    /** 创建帖子 → DRAFT（草稿不公开；需再调 submit 提交审核） */
    @Transactional
    public Long create(String userId, PostCreateRequest req) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("缺少用户标识");
        }
        // 用户治理（§7）：暂停账号 / 限制发帖用户拒绝新建
        communityUserService.requireCanPublish(userId);
        if (req == null || req.getTitle() == null || req.getTitle().isBlank()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        TravelPost post = new TravelPost();
        post.setUserId(userId);
        applyFields(post, req.getTitle(), req.getSummary(), req.getContent(),
                req.getCoverImage(), req.getCity(), req.getTravelDays(), req.getBudget(),
                req.getPace(), req.getPostType());
        post.setStatus(TravelPost.STATUS_DRAFT);
        post.setLikeCount(0);
        post.setFavoriteCount(0);
        post.setViewCount(0);
        post.setCommentCount(0);
        postRepository.insert(post);
        saveSpots(post.getId(), req.getSpots());
        log.info("帖子草稿创建: postId={} userId={} title={}", post.getId(), userId, post.getTitle());
        auditService.record(userId, AuditLog.CAT_CONTENT, "post_created",
                "post", String.valueOf(post.getId()),
                AuditService.detailOf("title", post.getTitle()));
        return post.getId();
    }

    /**
     * 编辑帖子：仅作者本人。
     *
     * <p>状态语义（审查报告 P0-1 禁止"改完仍公开"绕过审核；P1-1 进一步做公开/编辑版本分离）：
     * <ul>
     *   <li>DRAFT：直接改（仍为草稿）；</li>
     *   <li>REJECTED：改后回 DRAFT，需重新提交审核；</li>
     *   <li>PENDING_REVIEW：直接改（本就在审核队列中，审核员看到的是最新内容）；</li>
     *   <li>PUBLISHED：发生<b>实质变更</b>（标题/摘要/正文/封面/城市/节奏/天数/预算/类型/关联景点
     *       任一变化）→ 生成一条待审<b>编辑版本</b>，主表保持 PUBLISHED，公开流继续展示旧版本；
     *       审核通过才把版本内容切换为线上版本。仅提交且无实质变更 → 不制造无谓的审核任务。</li>
     *   <li>HIDDEN：没有线上版本可保护 → 回到 PENDING_REVIEW 重新审核。</li>
     * </ul>
     * 依据 PostUpdateRequest 约定：字段为 null 表示不修改（与详情回填值一致的提交 = 无变更）。
     */
    @Transactional
    public void update(String userId, Long postId, PostUpdateRequest req) {
        TravelPost post = requireOwned(userId, postId);
        if (req == null) {
            return;
        }
        String originalStatus = post.getStatus();
        boolean changed = false;
        boolean publishedCleared = false;

        if (req.getTitle() != null) {
            String title = req.getTitle().trim();
            if (title.isBlank()) {
                throw new IllegalArgumentException("标题不能为空");
            }
            if (!title.equals(post.getTitle())) {
                post.setTitle(title);
                changed = true;
            }
        }
        if (req.getSummary() != null) {
            String summary = blankToNull(req.getSummary().trim());
            if (!Objects.equals(summary, post.getSummary())) {
                post.setSummary(summary);
                changed = true;
            }
        }
        if (req.getContent() != null && !Objects.equals(req.getContent(), post.getContent())) {
            post.setContent(req.getContent());
            changed = true;
        }
        if (req.getCoverImage() != null) {
            // 审查报告 P1-2：封面只收本地上传路径或合法 http(s) 链接，非法直接 400
            String cover = validateCoverImage(req.getCoverImage());
            if (!Objects.equals(cover, post.getCoverImage())) {
                post.setCoverImage(cover);
                changed = true;
            }
        }
        if (req.getCity() != null) {
            String city = normalizeCity(req.getCity());
            if (!Objects.equals(city, post.getCity())) {
                post.setCity(city);
                changed = true;
            }
        }
        if (req.getTravelDays() != null && !Objects.equals(req.getTravelDays(), post.getTravelDays())) {
            post.setTravelDays(req.getTravelDays());
            changed = true;
        }
        if (req.getBudget() != null && !Objects.equals(req.getBudget(), post.getBudget())) {
            post.setBudget(req.getBudget());
            changed = true;
        }
        if (req.getPace() != null) {
            String pace = blankToNull(req.getPace().trim());
            if (!Objects.equals(pace, post.getPace())) {
                post.setPace(pace);
                changed = true;
            }
        }
        if (req.getPostType() != null && !req.getPostType().isBlank()) {
            String type = normalizeType(req.getPostType());
            if (!type.equals(post.getPostType())) {
                post.setPostType(type);
                changed = true;
            }
        }
        boolean spotsChanged = req.getSpots() != null && !spotsEqual(postId, req.getSpots());
        changed = changed || spotsChanged;

        if (changed) {
            if (TravelPost.STATUS_REJECTED.equals(originalStatus)) {
                // 被拒后修改 → 回草稿，作者需主动重新提交
                post.setStatus(TravelPost.STATUS_DRAFT);
                post.setRejectReason(null);
            } else if (TravelPost.STATUS_PUBLISHED.equals(originalStatus)) {
                // P1-1 版本化（2026-09-18）：已发布内容被修改 → 修改稿落 travel_post_revision 待审，
                // 主表保持 PUBLISHED —— 线上继续服务旧版本、读者无感；审核通过后才原子切换。
                // 与旧行为的区别：不再"整篇下架 + published_at 清空"（那会让老帖凭空消失并变新帖）。
                Long revId = savePendingRevision(post, userId, req.getSpots(), spotsChanged);
                log.info("已发布帖子提交修改版本: postId={} userId={} revisionId={}", postId, userId, revId);
                auditService.record(userId, AuditLog.CAT_CONTENT, "post_revision_submitted",
                        "post", String.valueOf(postId),
                        AuditService.detailOf("revision_id", revId,
                                "reason", "已发布内容发生实质修改，生成待审编辑版本"));
                if (moderationService != null) {
                    moderationService.submitAsync(ContentModerationTask.TARGET_POST, String.valueOf(postId),
                            revId, post.getTitle(), post.getCity(), post.getContent(), userId);
                }
                return; // 线上版本原样保留：不写主表正文、不动 published_at、不替换关联景点
            } else if (TravelPost.STATUS_HIDDEN.equals(originalStatus)) {
                // 已下架内容没有线上版本可保护 → 沿用原语义：回到待审
                post.setStatus(TravelPost.STATUS_PENDING_REVIEW);
                post.setPublishedAt(null);
                post.setRejectReason(null);
                publishedCleared = true;
                log.info("已隐藏帖子被修改，转重新审核: postId={} userId={}", postId, userId);
                auditService.record(userId, AuditLog.CAT_CONTENT, "post_requeued",
                        "post", String.valueOf(postId),
                        AuditService.detailOf("old_status", originalStatus,
                                "reason", "已下架内容发生实质修改，需重新审核"));
            }
            // PENDING_REVIEW / DRAFT：状态不变（本就在队列中 / 仍是草稿）
        }
        postRepository.updateById(post);
        if (spotsChanged) {
            replaceSpots(postId, req.getSpots());
        }
        if (publishedCleared) {
            // updateById 默认跳过 null 字段 → 需显式把 published_at 落 NULL（P0-1 状态机不变式）
            clearPublishedAt(postId);
        }
    }

    /* ================= 编辑版本（P1-1 公开版本 / 编辑版本分离） ================= */

    /**
     * 把「编辑后的内容」存为待审版本，并把 travel_post.pending_revision_id 指向它。
     *
     * <p>传进来的 {@code post} 已被 update() 改写成"编辑后的值"，此处只读不写回主表。
     * 若同一帖子已有待审版本（作者连改两次），旧版本置 {@code SUPERSEDED} 保留历史。
     *
     * @return 新版本 id
     */
    private Long savePendingRevision(TravelPost post, String editorId,
                                     List<PostSpotRef> reqSpots, boolean spotsChanged) {
        List<PostSpotRef> spots;
        if (spotsChanged) {
            spots = reqSpots == null ? List.of() : reqSpots;
        } else {
            spots = loadSpots(post.getId()); // 未改关联景点 → 快照当前值，保证版本自洽
        }
        Long existingId = post.getPendingRevisionId();
        if (existingId != null) {
            TravelPostRevision old = revisionRepository.selectById(existingId);
            if (old != null && old.isPending()) {
                old.setStatus(TravelPostRevision.STATUS_SUPERSEDED);
                revisionRepository.updateById(old);
            }
        }
        long count = revisionRepository.selectCount(new LambdaQueryWrapper<TravelPostRevision>()
                .eq(TravelPostRevision::getPostId, post.getId()));

        TravelPostRevision rev = new TravelPostRevision();
        rev.setPostId(post.getId());
        rev.setRevisionNo((int) count + 1);
        rev.setTitle(post.getTitle());
        rev.setSummary(post.getSummary());
        rev.setContent(post.getContent());
        rev.setCoverImage(post.getCoverImage());
        rev.setCity(post.getCity());
        rev.setTravelDays(post.getTravelDays());
        rev.setBudget(post.getBudget());
        rev.setPace(post.getPace());
        rev.setPostType(post.getPostType());
        rev.setSpotsJson(writeSpots(spots));
        rev.setStatus(TravelPostRevision.STATUS_PENDING_REVIEW);
        rev.setEditorId(editorId);
        revisionRepository.insert(rev);

        postRepository.update(null, new LambdaUpdateWrapper<TravelPost>()
                .eq(TravelPost::getId, post.getId())
                .set(TravelPost::getPendingRevisionId, rev.getId()));
        return rev.getId();
    }

    /**
     * 审核通过：把版本内容原子写回主表（关联景点一并切换），并解除待审指针。
     * {@code published_at} 保持原值 —— 这是"修改"而不是"重新发布"，老帖不该被顶到时间线最前。
     */
    private void applyPendingRevision(TravelPost post, TravelPostRevision rev, String reviewer) {
        post.setTitle(rev.getTitle());
        post.setSummary(rev.getSummary());
        post.setContent(rev.getContent());
        post.setCoverImage(rev.getCoverImage());
        post.setCity(rev.getCity());
        post.setTravelDays(rev.getTravelDays());
        post.setBudget(rev.getBudget());
        post.setPace(rev.getPace());
        post.setPostType(rev.getPostType());
        post.setRejectReason(null);
        postRepository.updateById(post);
        // updateById 跳过 null 字段 → 显式解除待审指针
        postRepository.update(null, new LambdaUpdateWrapper<TravelPost>()
                .eq(TravelPost::getId, post.getId())
                .set(TravelPost::getPendingRevisionId, null));
        post.setPendingRevisionId(null);
        if (rev.getSpotsJson() != null) {
            replaceSpots(post.getId(), readSpots(rev.getSpotsJson()));
        }
        rev.setStatus(TravelPostRevision.STATUS_APPROVED);
        rev.setReviewedBy(reviewer);
        rev.setReviewedAt(LocalDateTime.now());
        rev.setRejectReason(null);
        revisionRepository.updateById(rev);
    }

    /** 审核拒绝修改稿：只处置版本（主表仍是原公开版本，作者可再次编辑）。 */
    private void rejectPendingRevision(TravelPost post, TravelPostRevision rev, String reviewer, String reason) {
        rev.setStatus(TravelPostRevision.STATUS_REJECTED);
        rev.setRejectReason(reason == null || reason.isBlank() ? "修改内容不符合社区规范" : reason.trim());
        rev.setReviewedBy(reviewer);
        rev.setReviewedAt(LocalDateTime.now());
        revisionRepository.updateById(rev);
        postRepository.update(null, new LambdaUpdateWrapper<TravelPost>()
                .eq(TravelPost::getId, post.getId())
                .set(TravelPost::getPendingRevisionId, null));
        post.setPendingRevisionId(null);
    }

    /** 取某帖当前待审版本（指针存在但记录已被清理 → null）。 */
    private TravelPostRevision pendingRevisionOf(TravelPost post) {
        if (post == null || post.getPendingRevisionId() == null) {
            return null;
        }
        TravelPostRevision rev = revisionRepository.selectById(post.getPendingRevisionId());
        return rev != null && rev.isPending() ? rev : null;
    }

    private static String writeSpots(List<PostSpotRef> spots) {
        try {
            return SPOTS_MAPPER.writeValueAsString(spots == null ? List.of() : spots);
        } catch (Exception e) {
            log.warn("关联景点快照序列化失败，按空处理: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 关联景点快照反序列化。包级可见：AdminEvidenceService 复用（同一 service 包）。
     */
    static List<PostSpotRef> readSpots(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return SPOTS_MAPPER.readValue(json, new TypeReference<List<PostSpotRef>>() { });
        } catch (Exception e) {
            log.warn("关联景点快照反序列化失败，按空处理: {}", e.getMessage());
            return List.of();
        }
    }

    /** 删除帖子：仅作者本人（软删，不进公开流） */
    public void delete(String userId, Long postId) {
        TravelPost post = requireOwned(userId, postId);
        post.setStatus(TravelPost.STATUS_DELETED);
        post.setPublishedAt(null);
        postRepository.updateById(post);
        clearPublishedAt(postId); // updateById 不写 null 字段，显式清空（数据卫生）
    }

    /**
     * 提交审核：DRAFT/REJECTED → PENDING_REVIEW。
     * 先过内容确定性规则（长度/广告/联系方式），命中返回违规原因（400），不进入人工审核。
     *
     * @return 空 = 提交成功
     */
    public List<String> submit(String userId, Long postId) {
        // 用户治理（§7）：暂停账号 / 限制发帖用户拒绝提交审核
        communityUserService.requireCanPublish(userId);
        TravelPost post = requireOwned(userId, postId);
        if (TravelPost.STATUS_PUBLISHED.equals(post.getStatus())
                || TravelPost.STATUS_PENDING_REVIEW.equals(post.getStatus())) {
            return List.of(); // 已公开/审核中无需重复提交
        }
        if (TravelPost.STATUS_HIDDEN.equals(post.getStatus())
                || TravelPost.STATUS_DELETED.equals(post.getStatus())) {
            throw new ForbiddenException("当前状态不可提交审核");
        }
        List<String> violations = ruleChecker.check(post.getTitle(), post.getSummary(), post.getContent());
        if (!violations.isEmpty()) {
            log.info("帖子提交被规则拦截: postId={} violations={}", postId, violations);
            return violations;
        }
        // 阶段四任务 8：去重检测（与已发布/待审内容高度相似 → 提示修改，不直接通过）
        List<String> duplicates = detectDuplicates(post);
        if (!duplicates.isEmpty()) {
            log.info("帖子提交疑似重复: postId={} hits={}", postId, duplicates.size());
            return duplicates;
        }
        // 阶段四任务 5：提交时计算内容质量分并落库（审核队列展示；低质仅标记，不代替管理员决策）
        try {
            int spotCount = (int) Math.min(countLinkedSpots(postId), Integer.MAX_VALUE);
            PostQualityScorer.Input in = new PostQualityScorer.Input(
                    post.getTitle(), post.getSummary(), post.getContent(), post.getCity(),
                    post.getTravelDays(), post.getBudget(), spotCount);
            PostQualityScorer.Result q = PostQualityScorer.score(in);
            post.setQualityScore(q.score());
            post.setLowQuality(q.lowQuality() ? 1 : 0);
        } catch (Exception e) {
            log.warn("内容质量分计算失败（不阻断提交）: postId={} - {}", postId, e.getMessage());
        }
        post.setStatus(TravelPost.STATUS_PENDING_REVIEW);
        post.setRejectReason(null);
        postRepository.updateById(post);
        log.info("帖子进入待审核: postId={} userId={}", postId, userId);
        auditService.record(userId, AuditLog.CAT_CONTENT, "post_submitted",
                "post", String.valueOf(postId),
                AuditService.detailOf("quality_score", post.getQualityScore()));
        // AI 审核初筛（阶段三）：规则已通过 → AI 结构化初筛 + 阈值聚合，辅助管理员人工复核。
        // 异步执行 + 快照载荷（异步线程不回读源表，规避事务可见性坑）；失败不影响提交。
        if (moderationService != null) {
            moderationService.submitAsync(ContentModerationTask.TARGET_POST, String.valueOf(postId),
                    null, post.getTitle(), post.getCity(), post.getContent(), userId);
        }
        return List.of();
    }

    /* ================= 公开流 / 我的 / 详情 ================= */

    /** 公开流：只含 PUBLISHED；支持 city/postType 过滤 + recommended/popular/latest 排序 + 分页 */
    public PostPage publicFeed(String viewerId, String city, String postType, String sort,
                               int page, int pageSize) {
        return publicFeed(viewerId, city, postType, sort, page, pageSize, null);
    }

    /** 公开帖子流（P1-5 审查报告：feedTraceId 为前端页面会话幂等键，仅个性化曝光写入用） */
    public PostPage publicFeed(String viewerId, String city, String postType, String sort,
                               int page, int pageSize, String feedTraceId) {
        int size = Math.max(1, Math.min(pageSize <= 0 ? 12 : pageSize, 50));
        int pageNo = Math.max(1, page);
        LambdaQueryWrapper<TravelPost> w = basePublishedWrapper(city, postType);
        long total = postRepository.selectCount(w);
        boolean recommended = "recommended".equalsIgnoreCase(sort)
                || "personalized".equalsIgnoreCase(sort);
        if (recommended) {
            return recommendedFeed(viewerId, w, total, pageNo, size, feedTraceId);
        }
        List<TravelPost> rows;
        if ("popular".equalsIgnoreCase(sort)) {
            rows = popularRows(w, size, (pageNo - 1) * size);
        } else {
            rows = postRepository.selectList(basePublishedWrapper(city, postType)
                    .orderByDesc(TravelPost::getPublishedAt)
                    .last("LIMIT " + size + " OFFSET " + (pageNo - 1) * size));
        }
        return pageOf(viewerId, rows, total, pageNo, size, true);
    }

    /**
     * "为你推荐"排序（阶段三）：登录且有正偏好画像 → 帖子引擎多域打分 + 曝光日志；
     * 匿名/无画像 → 热门降级（新用户也能看到内容，不返回空流），与景点流语义一致。
     */
    private PostPage recommendedFeed(String viewerId, LambdaQueryWrapper<TravelPost> base,
                                     long total, int pageNo, int size, String feedTraceId) {
        if (viewerId == null || viewerId.isBlank()) {
            PostPage degraded = pageOf(null, popularRows(base, size, (pageNo - 1) * size),
                    total, pageNo, size, true);
            degraded.setPersonalized(false);
            return degraded;
        }
        List<UserPreference> prefs;
        try {
            prefs = userProfileService.listPreferences(viewerId);
        } catch (Exception e) {
            prefs = List.of();
        }
        if (!postFeedEngine.personalizedOf(prefs)) {
            PostPage degraded = pageOf(viewerId, popularRows(base, size, (pageNo - 1) * size),
                    total, pageNo, size, true);
            degraded.setPersonalized(false);
            return degraded;
        }
        List<TravelPost> all = postRepository.selectList(base);
        if (all.isEmpty()) {
            PostPage empty = pageOf(viewerId, List.of(), total, pageNo, size, true);
            empty.setPersonalized(true);
            return empty;
        }

        // A/B 实验（阶段四任务 6，低质过滤）：命中处理组 → 把提交时质量分判定的
        // 低质帖子（low_quality=1）排除出"为你推荐"候选；过滤后不足下限则整体回退
        // （帖子少时不空流）。无实验/匿名 → null（基线，低质帖子仍按规则参与但排后）。
        // 实验名取自「当前 ACTIVE 的帖子流实验」而非写死常量——管理面可自建任意名，写死会与库内 exp_name 对不上。
        AbExperiment postExp = abExperimentService.activeOf(AbExperiment.FEED_POST);
        String abVariant = postExp == null
                ? null : abExperimentService.resolveVariant(viewerId, postExp.getExpName());
        if (AbBucket.VARIANT_TREATMENT.equals(abVariant)) {
            List<TravelPost> clean = all.stream()
                    .filter(p -> p.getLowQuality() == null || p.getLowQuality() == 0)
                    .collect(Collectors.toList());
            if (clean.size() >= MIN_RECOMMEND_POOL) {
                log.info("A/B[{}] 处理组生效: user={} 候选 {} → {}",
                        postExp.getExpName(), viewerId, all.size(), clean.size());
                all = clean;
            }
        }

        Map<Long, List<PostTag>> tagByPost = postTagService.ensureBatch(all);
        List<PostFeedEngine.RankedPost> ranked = postFeedEngine.rank(all, tagByPost, prefs);
        int from = Math.min((pageNo - 1) * size, ranked.size());
        int to = Math.min(from + size, ranked.size());
        List<PostFeedEngine.RankedPost> shown = ranked.subList(from, to);

        // 曝光日志（仅个性化分支；热门/最新不写，保证效果统计口径纯净）；记录 A/B 变体供对照
        try {
            postFeedEngine.logExposures(viewerId, userProfileService.getProfileVersion(viewerId),
                    abVariant, feedTraceId, shown);
        } catch (Exception e) {
            log.warn("帖子曝光日志失败（不影响返回）: {}", e.getMessage());
        }

        Map<Long, TravelPost> byId = new HashMap<>();
        for (TravelPost p : all) {
            byId.put(p.getId(), p);
        }
        Map<Long, RankMeta> meta = new HashMap<>();
        List<TravelPost> rows = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) {
            PostFeedEngine.RankedPost rp = shown.get(i);
            TravelPost p = byId.get(rp.postId());
            if (p == null) {
                continue;
            }
            rows.add(p);
            meta.put(p.getId(), new RankMeta(rp.reason(), rp.matchedTags(), rp.score()));
        }
        PostPage result = pageOf(viewerId, rows, total, pageNo, size, true, meta);
        result.setPersonalized(true);
        return result;
    }

    /** 热门排序行：确定性互动分（点赞 + 2×收藏 + 评论）倒序，同分按发布时间新在前（§11.2 简化版） */
    private List<TravelPost> popularRows(LambdaQueryWrapper<TravelPost> base, int size, int offset) {
        return postRepository.selectList(base.last(
                " ORDER BY (like_count + 2 * favorite_count + comment_count) DESC, published_at DESC"
                        + " LIMIT " + size + " OFFSET " + offset));
    }

    /** 我的帖子：作者看自己全部状态（不含已删除），供编辑/删除/提交/查看拒绝原因 */
    public PostPage mine(String userId, int page, int pageSize) {
        int size = Math.max(1, Math.min(pageSize <= 0 ? 12 : pageSize, 50));
        int pageNo = Math.max(1, page);
        LambdaQueryWrapper<TravelPost> w = new LambdaQueryWrapper<TravelPost>()
                .eq(TravelPost::getUserId, userId)
                .ne(TravelPost::getStatus, TravelPost.STATUS_DELETED)
                .orderByDesc(TravelPost::getCreatedAt);
        long total = postRepository.selectCount(w);
        List<TravelPost> rows = postRepository.selectList(w.last(
                "LIMIT " + size + " OFFSET " + (pageNo - 1) * size));
        return pageOf(userId, rows, total, pageNo, size, true);
    }

    /** 指定作者的已发布帖子（用户旅行主页；PUBLISHED 过滤与公开流同源） */
    public PostPage userPublishedPosts(String viewerId, String authorId, int page, int pageSize) {
        int size = Math.max(1, Math.min(pageSize <= 0 ? 12 : pageSize, 50));
        int pageNo = Math.max(1, page);
        LambdaQueryWrapper<TravelPost> w = new LambdaQueryWrapper<TravelPost>()
                .eq(TravelPost::getUserId, authorId)
                .eq(TravelPost::getStatus, TravelPost.STATUS_PUBLISHED)
                .orderByDesc(TravelPost::getPublishedAt);
        long total = postRepository.selectCount(w);
        List<TravelPost> rows = postRepository.selectList(w.last(
                "LIMIT " + size + " OFFSET " + (pageNo - 1) * size));
        return pageOf(viewerId, rows, total, pageNo, size, true);
    }

    /** 审核队列（管理员）：待审核，旧→新。P1-1 起同时包含"已发布帖的待审修改版本"。 */
    public PostPage moderationQueue(String adminId, int page, int pageSize) {
        communityUserService.requirePermission(adminId, AdminPermission.CONTENT_REVIEW);
        int size = Math.max(1, Math.min(pageSize <= 0 ? 12 : pageSize, 50));
        int pageNo = Math.max(1, page);
        LambdaQueryWrapper<TravelPost> w = new LambdaQueryWrapper<TravelPost>()
                .and(q -> q.eq(TravelPost::getStatus, TravelPost.STATUS_PENDING_REVIEW)
                        .or().isNotNull(TravelPost::getPendingRevisionId))
                .orderByAsc(TravelPost::getCreatedAt);
        long total = postRepository.selectCount(w);
        List<TravelPost> rows = postRepository.selectList(w.last(
                "LIMIT " + size + " OFFSET " + (pageNo - 1) * size));
        return pageOf(adminId, rows, total, pageNo, size, true);
    }

    /**
     * 管理端帖子全量队列（阶段四任务 4：管理后台完善 —— 已发布内容治理/隐藏恢复）。
     * 管理员可查看 PUBLISHED/HIDDEN 任一状态（新→旧），供下架违规帖/恢复误隐藏帖。
     */
    public PostPage adminPosts(String adminId, String status, int page, int pageSize) {
        communityUserService.requirePermission(adminId, AdminPermission.CONTENT_REVIEW);
        int size = Math.max(1, Math.min(pageSize <= 0 ? 20 : pageSize, 100));
        int pageNo = Math.max(1, page);
        String st = status == null || status.isBlank() ? TravelPost.STATUS_PUBLISHED : status.trim();
        if (!List.of(TravelPost.STATUS_PUBLISHED, TravelPost.STATUS_HIDDEN)
                .contains(st)) {
            throw new IllegalArgumentException("管理端帖子队列仅支持 PUBLISHED/HIDDEN 状态");
        }
        LambdaQueryWrapper<TravelPost> w = new LambdaQueryWrapper<TravelPost>()
                .eq(TravelPost::getStatus, st)
                .orderByDesc(TravelPost::getCreatedAt);
        long total = postRepository.selectCount(w);
        List<TravelPost> rows = postRepository.selectList(w.last(
                "LIMIT " + size + " OFFSET " + (pageNo - 1) * size));
        return pageOf(adminId, rows, total, pageNo, size, true);
    }

    /** 帖子详情（公开 PUBLISHED 任何人可见；作者可见自己任意非删除状态；管理员可见待审/隐藏） */
    public PostDetail detail(String viewerId, Long postId) {
        TravelPost post = requireVisible(viewerId, postId);
        PostDetail d = new PostDetail();
        d.setId(post.getId());
        d.setTitle(post.getTitle());
        d.setSummary(post.getSummary());
        d.setContent(post.getContent());
        d.setCoverImage(post.getCoverImage());
        d.setCity(post.getCity());
        d.setTravelDays(post.getTravelDays());
        d.setBudget(post.getBudget());
        d.setPace(post.getPace());
        d.setPostType(post.getPostType());
        d.setStatus(post.getStatus());
        d.setAuthor(new PostAuthor(post.getUserId(), communityUserService.nicknameOf(post.getUserId())));
        d.setLikeCount(post.getLikeCount());
        d.setFavoriteCount(post.getFavoriteCount());
        d.setCommentCount(post.getCommentCount());
        d.setViewCount(post.getViewCount());
        d.setRejectReason(post.getRejectReason());
        d.setPublishedAt(fmt(post.getPublishedAt()));
        d.setCreatedAt(fmt(post.getCreatedAt()));
        d.setSpots(loadSpots(postId));
        boolean mine = post.getUserId().equals(viewerId);
        d.setMine(mine);
        if (viewerId != null && !viewerId.isBlank()) {
            Map<Long, Set<String>> mineMap = interactionSets(viewerId, List.of(postId));
            Set<String> actions = mineMap.getOrDefault(postId, Set.of());
            d.setLiked(actions.contains(PostInteraction.ACTION_LIKE));
            d.setFavorited(actions.contains(PostInteraction.ACTION_FAVORITE));
            d.setDisliked(actions.contains(PostInteraction.ACTION_DISLIKE));
        } else {
            d.setLiked(false);
            d.setFavorited(false);
            d.setDisliked(false);
        }
        // 浏览计数：仅公开内容且非本人时 +1（展示用，不精确到并发）
        if (post.isPubliclyVisible() && viewerId != null && !mine) {
            incrementView(postId);
            d.setViewCount((post.getViewCount() == null ? 0 : post.getViewCount()) + 1);
        }
        // P1-1：待审修改版本 —— 作者看到「你的修改审核中」，审核员看到待切换的内容快照
        TravelPostRevision pendingRev = pendingRevisionOf(post);
        d.setHasPendingRevision(pendingRev != null);
        d.setPendingRevisionNo(pendingRev == null ? null : pendingRev.getRevisionNo());
        d.setPendingRevision(pendingRev == null ? null : revisionView(pendingRev));
        return d;
    }

    /* ================= 管理员审核动作 ================= */

    /** 通过：PENDING_REVIEW → PUBLISHED（published_at 落当前时间）；PUBLISHED 帖的待审修改版本 → 只切换内容 */
    @Transactional
    public void approve(String adminId, Long postId) {
        communityUserService.requirePermission(adminId, AdminPermission.CONTENT_REVIEW);
        TravelPost post = requirePost(postId);

        // P1-1：通过的是"已发布帖的修改版本" → 只把版本内容切为线上版本；
        // 帖子状态保持 PUBLISHED、published_at 不变（这是修改不是重新发布）。
        TravelPostRevision rev = pendingRevisionOf(post);
        if (rev != null) {
            applyPendingRevision(post, rev, adminId);
            log.info("帖子修改版本审核通过，线上内容已切换: postId={} revisionId={} by={}",
                    postId, rev.getId(), adminId);
            auditService.record(adminId, AuditLog.CAT_ADMIN, "post_revision_approved",
                    "post", String.valueOf(postId),
                    AuditService.detailOf("revision_id", rev.getId(), "owner_id", post.getUserId()));
            return;
        }

        if (TravelPost.STATUS_HIDDEN.equals(post.getStatus())) {
            post.setStatus(TravelPost.STATUS_PUBLISHED); // 隐藏后重新上架
        } else if (!TravelPost.STATUS_PENDING_REVIEW.equals(post.getStatus())) {
            throw new IllegalArgumentException("仅待审核或隐藏状态的帖子可执行通过操作");
        } else {
            post.setStatus(TravelPost.STATUS_PUBLISHED);
        }
        if (post.getPublishedAt() == null) {
            post.setPublishedAt(LocalDateTime.now());
        }
        post.setRejectReason(null);
        postRepository.updateById(post);
        log.info("帖子审核通过: postId={} by={}", postId, adminId);
        auditService.record(adminId, AuditLog.CAT_ADMIN, "post_approved",
                "post", String.valueOf(postId),
                AuditService.detailOf("owner_id", post.getUserId()));
    }

    /** 拒绝：PENDING_REVIEW → REJECTED（记录原因，作者可改后重提）；修改版本 → 只驳回版本 */
    @Transactional
    public void reject(String adminId, Long postId, String reason) {
        communityUserService.requirePermission(adminId, AdminPermission.CONTENT_REVIEW);
        TravelPost post = requirePost(postId);

        // P1-1：驳回的是"已发布帖的修改版本" → 线上继续服务原内容，作者可再次编辑提交
        TravelPostRevision rev = pendingRevisionOf(post);
        if (rev != null) {
            rejectPendingRevision(post, rev, adminId, reason);
            log.info("帖子修改版本审核拒绝（线上保持原版本）: postId={} revisionId={} by={} reason={}",
                    postId, rev.getId(), adminId, rev.getRejectReason());
            auditService.record(adminId, AuditLog.CAT_ADMIN, "post_revision_rejected",
                    "post", String.valueOf(postId),
                    AuditService.detailOf("revision_id", rev.getId(), "reason", rev.getRejectReason()));
            return;
        }

        if (!TravelPost.STATUS_PENDING_REVIEW.equals(post.getStatus())) {
            throw new IllegalArgumentException("仅待审核帖子可执行拒绝操作");
        }
        post.setStatus(TravelPost.STATUS_REJECTED);
        post.setRejectReason(reason == null || reason.isBlank() ? "内容不符合社区规范" : reason.trim());
        post.setPublishedAt(null); // 不变式：published_at 仅 PUBLISHED 时有值
        postRepository.updateById(post);
        clearPublishedAt(postId);
        log.info("帖子审核拒绝: postId={} by={} reason={}", postId, adminId, post.getRejectReason());
        auditService.record(adminId, AuditLog.CAT_ADMIN, "post_rejected",
                "post", String.valueOf(postId),
                AuditService.detailOf("reason", post.getRejectReason()));
    }

    /** 隐藏（已发布违规下架）：PUBLISHED → HIDDEN（不进公开流，可再上架） */
    public void hide(String adminId, Long postId) {
        communityUserService.requirePermission(adminId, AdminPermission.CONTENT_REVIEW);
        TravelPost post = requirePost(postId);
        if (!post.isPubliclyVisible()) {
            throw new IllegalArgumentException("仅已发布帖子可执行隐藏操作");
        }
        post.setStatus(TravelPost.STATUS_HIDDEN);
        post.setPublishedAt(null);
        postRepository.updateById(post);
        clearPublishedAt(postId);
        log.info("帖子隐藏下架: postId={} by={}", postId, adminId);
        auditService.record(adminId, AuditLog.CAT_ADMIN, "post_hidden",
                "post", String.valueOf(postId),
                AuditService.detailOf("owner_id", post.getUserId()));
    }

    /* ================= 审核联动（AI 自动发布 / 改判落地） ================= */

    /**
     * AI 低风险自动发布（<b>系统主体</b>）。
     *
     * <p>为什么不复用 {@link #approve}：approve 是"管理员动作"，入口第一行就做
     * CONTENT_REVIEW 权限校验。自动发布的主体是系统（system:ai），它<b>没有也不该有</b>
     * 一个管理员账号——直接复用 approve 必然权限失败，而"给系统发管理员权限"是更糟的设计。
     * 所以本方法只做状态机校验（仅 PENDING_REVIEW → PUBLISHED），
     * "有没有资格调用"由调用方（审核流水线内部）这一事实保证，并单独写审计动作以便回溯。
     */
    @Transactional
    public void autoPublish(String actor, Long postId) {
        autoPublish(actor, postId, null);
    }

    /**
     * AI 自动放行（带版本号）。
     *
     * <p>P1-1：若帖子存在待审<b>修改版本</b>，此处只切换版本内容（线上原内容被替换），
     * 帖子状态与 published_at 不变。{@code revisionId} 是审核任务创建时绑定的版本，
     * 与当前待审版本不一致说明作者已改新稿 —— 旧任务的结论不能套用到新版本上。
     */
    @Transactional
    public void autoPublish(String actor, Long postId, Long revisionId) {
        TravelPost post = requirePost(postId);
        TravelPostRevision rev = pendingRevisionOf(post);
        if (rev != null) {
            if (revisionId != null && !revisionId.equals(rev.getId())) {
                log.info("AI 放行任务绑定的是旧版本，已忽略: postId={} taskRev={} currentRev={}",
                        postId, revisionId, rev.getId());
                throw new IllegalStateException("审核任务对应的编辑版本已过期");
            }
            applyPendingRevision(post, rev, actor);
            log.info("帖子修改版本 AI 自动放行，线上内容已切换: postId={} revisionId={} actor={}",
                    postId, rev.getId(), actor);
            auditService.record(actor, AuditLog.CAT_CONTENT, "post_revision_auto_approved",
                    "post", String.valueOf(postId),
                    AuditService.detailOf("actor", actor, "revision_id", rev.getId(),
                            "owner_id", post.getUserId()));
            return;
        }
        if (!TravelPost.STATUS_PENDING_REVIEW.equals(post.getStatus())) {
            throw new IllegalArgumentException("仅待审核帖子可自动发布");
        }
        post.setStatus(TravelPost.STATUS_PUBLISHED);
        if (post.getPublishedAt() == null) {
            post.setPublishedAt(LocalDateTime.now());
        }
        post.setRejectReason(null);
        postRepository.updateById(post);
        log.info("帖子自动发布（AI 低风险放行）: postId={} actor={}", postId, actor);
        auditService.record(actor, AuditLog.CAT_CONTENT, "post_auto_published",
                "post", String.valueOf(postId),
                AuditService.detailOf("actor", actor, "owner_id", post.getUserId()));
    }

    /**
     * 审核否决落地（按内容<b>当前状态</b>选动作）。
     *
     * <p>修正前的缺陷：审核侧无条件调 {@link #reject}，但被"AI 自动放行"的帖子已经是
     * PUBLISHED，状态机自校验会抛错 → 异常被吞 → 帖子仍然在线，"改判拒绝"实际不生效。
     * 因此按状态分派：已发布 → 下架（HIDDEN，可再上架）；待审 → 拒绝（REJECTED，作者可改后重提）。
     *
     * @return 实际执行的动作名（hide / reject / noop），便于调用方审计与断言
     */
    public String applyRejectFromModeration(String adminId, Long postId, String reason) {
        communityUserService.requirePermission(adminId, AdminPermission.CONTENT_REVIEW);
        TravelPost post = requirePost(postId);
        // P1-1：被改判的是"待审修改版本" → 只驳回版本，线上原版本继续服务（不能整篇下架）
        TravelPostRevision rev = pendingRevisionOf(post);
        if (rev != null) {
            rejectPendingRevision(post, rev, adminId, reason);
            log.info("审核改判拒绝 → 已驳回修改版本（线上保持原内容）: postId={} revisionId={}",
                    postId, rev.getId());
            return "revision_rejected";
        }
        if (post.isPubliclyVisible()) {
            hide(adminId, postId);
            log.info("审核改判拒绝 → 已发布内容下架: postId={} reason={}", postId, reason);
            return "hide";
        }
        if (TravelPost.STATUS_PENDING_REVIEW.equals(post.getStatus())) {
            reject(adminId, postId, reason);
            return "reject";
        }
        // DRAFT / REJECTED / DELETED：没有公开内容需要处置，决策留痕即可
        log.info("审核改判拒绝：内容当前状态无需处置 postId={} status={}", postId, post.getStatus());
        return "noop";
    }

    /* ================= 内部工具 ================= */

    /**
     * 显式把 travel_post.published_at 置 NULL。
     * MyBatis-Plus 的 updateById 默认按 NOT_NULL 策略跳过 null 字段，实体上 setPublishedAt(null)
     * 并不会真正落库（P0-1 实机验收发现：状态已转 PENDING_REVIEW 但旧 published_at 残留），
     * 因此在"清空发布时间"的状态迁移（改后重审/拒绝/隐藏/删除）后必须走 wrapper.set 补一次。
     */
    private void clearPublishedAt(Long postId) {
        postRepository.update(null, new LambdaUpdateWrapper<TravelPost>()
                .eq(TravelPost::getId, postId)
                .set(TravelPost::getPublishedAt, null));
    }

    /** 个性化排序附带的展示元信息（推荐理由/命中标签/得分，阶段三） */
    private record RankMeta(String reason, List<String> matchedTags, Double score) {
    }

    private PostPage pageOf(String viewerId, List<TravelPost> rows, long total,
                            int page, int size, boolean attachStates) {
        return pageOf(viewerId, rows, total, page, size, attachStates, Map.of());
    }

    private PostPage pageOf(String viewerId, List<TravelPost> rows, long total,
                            int page, int size, boolean attachStates,
                            Map<Long, RankMeta> rankMeta) {
        List<PostItem> items = new ArrayList<>();
        if (!rows.isEmpty()) {
            Map<String, String> nicknames = communityUserService.nicknamesOf(
                    rows.stream().map(TravelPost::getUserId).collect(Collectors.toSet()));
            Map<Long, Set<String>> interaction = attachStates && viewerId != null && !viewerId.isBlank()
                    ? interactionSets(viewerId, rows.stream().map(TravelPost::getId).collect(Collectors.toList()))
                    : Map.of();
            // P1-1：一次批量取本页"待审修改版本"，避免逐行查（N+1）
            Map<Long, TravelPostRevision> pendingRevs = pendingRevisionsOf(rows);
            for (TravelPost p : rows) {
                PostItem item = toItem(p, nicknames, viewerId,
                        interaction.getOrDefault(p.getId(), Set.of()),
                        pendingRevs.get(p.getId()));
                RankMeta meta = rankMeta.get(p.getId());
                if (meta != null) {
                    item.setRecommendReason(meta.reason());
                    item.setMatchedTags(meta.matchedTags());
                    item.setScore(meta.score());
                }
                items.add(item);
            }
        }
        PostPage result = new PostPage();
        result.setItems(items);
        result.setTotal(total);
        result.setPage(page);
        result.setPageSize(size);
        return result;
    }

    private PostItem toItem(TravelPost p, Map<String, String> nicknames, String viewerId,
                            Set<String> actions, TravelPostRevision pendingRev) {
        PostItem it = new PostItem();
        it.setId(p.getId());
        it.setTitle(p.getTitle());
        it.setSummary(p.getSummary());
        it.setCoverImage(p.getCoverImage());
        it.setCity(p.getCity());
        it.setPostType(p.getPostType());
        it.setStatus(p.getStatus());
        it.setAuthor(new PostAuthor(p.getUserId(),
                nicknames.getOrDefault(p.getUserId(), p.getUserId())));
        it.setLikeCount(nvl(p.getLikeCount()));
        it.setFavoriteCount(nvl(p.getFavoriteCount()));
        it.setCommentCount(nvl(p.getCommentCount()));
        it.setViewCount(nvl(p.getViewCount()));
        it.setPublishedAt(fmt(p.getPublishedAt()));
        it.setCreatedAt(fmt(p.getCreatedAt()));
        it.setLiked(actions.contains(PostInteraction.ACTION_LIKE));
        it.setFavorited(actions.contains(PostInteraction.ACTION_FAVORITE));
        it.setDisliked(actions.contains(PostInteraction.ACTION_DISLIKE));
        it.setRejectReason(p.getRejectReason());
        it.setMine(viewerId != null && p.getUserId().equals(viewerId));
        // P1-1：待审修改版本标记（列表卡片显示「修改审核中」）
        it.setHasPendingRevision(pendingRev != null);
        it.setPendingRevisionNo(pendingRev == null ? null : pendingRev.getRevisionNo());
        // 阶段四任务 5：内容质量（管理队列展示）
        it.setQualityScore(p.getQualityScore());
        it.setLowQuality(p.getLowQuality() != null && p.getLowQuality() == 1);
        return it;
    }

    /**
     * 批量取本页帖子中"待审修改版本"（postId → 版本），仅对 pending_revision_id 非空的行查一次。
     * 指针存在但版本记录已非 PENDING（被清理/串改）→ 视为无待审版本，不误报。
     */
    private Map<Long, TravelPostRevision> pendingRevisionsOf(List<TravelPost> rows) {
        List<Long> revIds = rows.stream()
                .map(TravelPost::getPendingRevisionId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (revIds.isEmpty()) {
            return Map.of();
        }
        List<TravelPostRevision> revs = revisionRepository.selectBatchIds(revIds);
        Map<Long, TravelPostRevision> byPost = new HashMap<>();
        for (TravelPostRevision r : revs) {
            if (r != null && r.isPending()) {
                byPost.put(r.getPostId(), r);
            }
        }
        return byPost;
    }

    /** 待审版本内容快照（供详情/审核页展示；字段名与前端契约一致） */
    private Map<String, Object> revisionView(TravelPostRevision rev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rev.getId());
        m.put("revision_no", rev.getRevisionNo());
        m.put("status", rev.getStatus());
        m.put("title", rev.getTitle());
        m.put("summary", rev.getSummary());
        m.put("content", rev.getContent());
        m.put("cover_image", rev.getCoverImage());
        m.put("city", rev.getCity());
        m.put("travel_days", rev.getTravelDays());
        m.put("budget", rev.getBudget());
        m.put("pace", rev.getPace());
        m.put("post_type", rev.getPostType());
        m.put("spots", readSpots(rev.getSpotsJson()));
        m.put("reject_reason", rev.getRejectReason());
        m.put("edited_at", fmt(rev.getUpdatedAt() == null ? rev.getCreatedAt() : rev.getUpdatedAt()));
        m.put("editor", rev.getEditorId());
        return m;
    }

    /** 查重：与已发布/待审帖子标题摘要高度相似时返回提示（排除自己同帖） */
    private List<String> detectDuplicates(TravelPost post) {
        List<String> hits = new ArrayList<>();
        try {
            List<TravelPost> candidates = postRepository.selectList(
                    new LambdaQueryWrapper<TravelPost>()
                            .in(TravelPost::getStatus,
                                    TravelPost.STATUS_PUBLISHED, TravelPost.STATUS_PENDING_REVIEW)
                            .ne(TravelPost::getId, post.getId())
                            .last("LIMIT 200"));
            if (candidates.isEmpty()) {
                return hits;
            }
            List<String> others = candidates.stream()
                    .map(p -> (p.getTitle() == null ? "" : p.getTitle())
                            + " " + (p.getSummary() == null ? "" : p.getSummary()))
                    .collect(Collectors.toList());
            String self = (post.getTitle() == null ? "" : post.getTitle())
                    + " " + (post.getSummary() == null ? "" : post.getSummary());
            for (String hit : PostDuplicateDetector.findDuplicates(self, others)) {
                hits.add("疑似与已有内容重复：「" + truncate(hit, 40) + "」，请修改标题或内容后再提交");
            }
        } catch (Exception e) {
            log.debug("提交查重失败（不阻断）: {}", e.getMessage());
        }
        return hits;
    }

    /**
     * 关联景点数：查询失败必须向上抛——外层（提交时算质量分）会捕获并跳过打分。
     * 若在这里吞成 0，帖子会因"关联景点=0"被错误打成低质，污染审核队列（P0-1 同模式）。
     */
    private long countLinkedSpots(Long postId) {
        return postSpotRepository.selectCount(
                new LambdaQueryWrapper<PostSpot>().eq(PostSpot::getPostId, postId));
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max) + "…";
    }

    /** 帖子关联景点（详情页展示，图片从 spot 表补全；spot 缺失时名称兜底） */
    private List<PostSpotRef> loadSpots(Long postId) {
        List<PostSpot> refs = postSpotRepository.selectList(
                new LambdaQueryWrapper<PostSpot>()
                        .eq(PostSpot::getPostId, postId)
                        .orderByAsc(PostSpot::getSortOrder));
        if (refs.isEmpty()) {
            return List.of();
        }
        List<String> spotIds = refs.stream()
                .map(PostSpot::getSpotId)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toList());
        Map<String, String> imageBySpotId = new HashMap<>();
        if (!spotIds.isEmpty()) {
            try {
                spotRepository.selectList(new LambdaQueryWrapper<Spot>()
                                .in(Spot::getSpotId, spotIds))
                        .forEach(s -> imageBySpotId.put(s.getSpotId(), s.getImageUrl()));
            } catch (Exception e) {
                log.debug("关联景点图片补全失败: {}", e.getMessage());
            }
        }
        List<PostSpotRef> result = new ArrayList<>();
        for (PostSpot r : refs) {
            PostSpotRef ref = new PostSpotRef();
            ref.setSpotId(r.getSpotId());
            ref.setPoiId(r.getPoiId());
            ref.setSpotName(r.getSpotName());
            ref.setImageUrl(imageBySpotId.get(r.getSpotId()));
            result.add(ref);
        }
        return result;
    }

    private void saveSpots(Long postId, List<PostSpotRef> spots) {
        if (spots == null || spots.isEmpty()) {
            return;
        }
        int order = 0;
        for (PostSpotRef s : spots) {
            if (s.getSpotName() == null || s.getSpotName().isBlank()) {
                continue;
            }
            PostSpot ps = new PostSpot();
            ps.setPostId(postId);
            ps.setSpotId(blankToNull(s.getSpotId()));
            ps.setPoiId(blankToNull(s.getPoiId()));
            ps.setSpotName(s.getSpotName().trim());
            ps.setSortOrder(order++);
            try {
                postSpotRepository.insert(ps);
            } catch (DuplicateKeyException e) {
                log.debug("帖子重复关联同一景点已跳过: postId={} spotId={}", postId, s.getSpotId());
            }
        }
    }

    private void replaceSpots(Long postId, List<PostSpotRef> spots) {
        postSpotRepository.delete(new LambdaQueryWrapper<PostSpot>().eq(PostSpot::getPostId, postId));
        saveSpots(postId, spots);
    }

    /** 请求里的关联景点与库中现有是否一致（顺序敏感；用于"编辑是否实质修改"判定）。 */
    private boolean spotsEqual(Long postId, List<PostSpotRef> spots) {
        List<PostSpotRef> current;
        try {
            current = loadSpots(postId);
        } catch (Exception e) {
            log.debug("读取现有关联景点失败（视为有变更）: postId={} - {}", postId, e.getMessage());
            return false;
        }
        if (current.size() != spots.size()) {
            return false;
        }
        for (int i = 0; i < current.size(); i++) {
            PostSpotRef a = current.get(i);
            PostSpotRef b = spots.get(i);
            if (b == null) {
                return false;
            }
            if (!Objects.equals(blankToNull(a.getSpotId()), blankToNull(b.getSpotId()))) {
                return false;
            }
            if (!Objects.equals(blankToNull(a.getPoiId()), blankToNull(b.getPoiId()))) {
                return false;
            }
            if (!Objects.equals(normName(a.getSpotName()), normName(b.getSpotName()))) {
                return false;
            }
        }
        return true;
    }

    private static String normName(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void incrementView(Long postId) {
        postRepository.update(null, new UpdateWrapper<TravelPost>()
                .eq("id", postId)
                .setSql("view_count = IFNULL(view_count, 0) + 1"));
    }

    /** 我的互动状态（LIKE/FAVORITE/DISLIKE 集合），按帖子分组，避免 N+1 */
    private Map<Long, Set<String>> interactionSets(String userId, List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        try {
            List<PostInteraction> list = interactionRepository.selectList(
                    new LambdaQueryWrapper<PostInteraction>()
                            .eq(PostInteraction::getUserId, userId)
                            .in(PostInteraction::getPostId, postIds)
                            .in(PostInteraction::getActionType,
                                    List.of(PostInteraction.ACTION_LIKE,
                                            PostInteraction.ACTION_FAVORITE,
                                            PostInteraction.ACTION_DISLIKE)));
            Map<Long, Set<String>> map = new HashMap<>();
            for (PostInteraction pi : list) {
                map.computeIfAbsent(pi.getPostId(), k -> new HashSet<>()).add(pi.getActionType());
            }
            return map;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private LambdaQueryWrapper<TravelPost> basePublishedWrapper(String city, String postType) {
        LambdaQueryWrapper<TravelPost> w = new LambdaQueryWrapper<TravelPost>()
                .eq(TravelPost::getStatus, TravelPost.STATUS_PUBLISHED);
        if (city != null && !city.isBlank()) {
            w.eq(TravelPost::getCity, city.trim());
        }
        if (postType != null && !postType.isBlank()) {
            w.eq(TravelPost::getPostType, postType.trim());
        }
        return w;
    }

    private TravelPost requireOwned(String userId, Long postId) {
        TravelPost post = requirePost(postId);
        if (!post.getUserId().equals(userId)) {
            throw new ForbiddenException("只能操作自己的帖子");
        }
        return post;
    }

    /** 可见性：PUBLISHED 公开；作者本人任何非删除状态；管理员可看待审/隐藏/拒绝 */
    private TravelPost requireVisible(String viewerId, Long postId) {
        TravelPost post = requirePost(postId);
        if (post.isPubliclyVisible()) {
            return post;
        }
        boolean mine = viewerId != null && post.getUserId().equals(viewerId);
        if (mine && !TravelPost.STATUS_DELETED.equals(post.getStatus())) {
            return post;
        }
        if (viewerId != null && communityUserService.isAdmin(viewerId)
                && !TravelPost.STATUS_DELETED.equals(post.getStatus())) {
            return post;
        }
        throw new PostNotFoundException("帖子不存在或未公开");
    }

    private TravelPost requirePost(Long postId) {
        TravelPost post = postId == null ? null : postRepository.selectById(postId);
        if (post == null || TravelPost.STATUS_DELETED.equals(post.getStatus())) {
            throw new PostNotFoundException("帖子不存在");
        }
        return post;
    }

    private void applyFields(TravelPost post, String title, String summary, String content,
                             String coverImage, String city, Integer days, Double budget,
                             String pace, String type) {
        post.setTitle(title.trim());
        post.setSummary(blankToNull(summary == null ? null : summary.trim()));
        post.setContent(content);
        post.setCoverImage(validateCoverImage(coverImage)); // 审查报告 P1-2
        post.setCity(normalizeCity(city));
        post.setTravelDays(days);
        post.setBudget(budget);
        post.setPace(blankToNull(pace == null ? null : pace.trim()));
        post.setPostType(normalizeType(type));
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return TravelPost.TYPE_NOTE;
        }
        String t = type.trim().toUpperCase();
        switch (t) {
            case TravelPost.TYPE_GUIDE:
            case TravelPost.TYPE_SPOT_RECOMMENDATION:
            case TravelPost.TYPE_ITINERARY:
            case TravelPost.TYPE_NOTE:
                return t;
            default:
                return TravelPost.TYPE_NOTE;
        }
    }

    private static int nvl(Integer v) {
        return v == null ? 0 : v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * 城市名归一化（2026-09-13）：空白/非法城市（"1"、"火星"）→ null；合法城市归一到规范名
     * （"北京市"→"北京"、"魔都"→"上海"）。帖子 city 属可选字段，非法值静默置空而非报错，
     * 避免用户随手填数字导致垃圾城市入库、进而污染画像标签（"匹配你的偏好：1"）。
     */
    private String normalizeCity(String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        String canonical = cityValidator.canonicalCity(city.trim());
        if (canonical == null) {
            log.debug("帖子城市非法，置空：{}", city.trim());
            return null;
        }
        return canonical;
    }

    /**
     * 封面图 URL 校验（审查报告 P1-2）：帖子封面不能是任意外部字符串。
     * 只接受两种形态：本系统上传路径（/uploads/&lt;32位hex&gt;.白名单扩展）或合法 http(s) 图片链接。
     * http(s) 做格式卫生：长度 ≤2000、必须有主机、禁止内嵌账号密码、禁止 data:/javascript:/file:
     * 等非图片 scheme（防止占位脚本/超长串/脏数据入库）。封面属浏览器展示资源、服务端不回拉，
     * 故不做域名白名单；如需运营级收紧（仅允许自建上传或指定图床）再在此扩展。
     */
    private static String validateCoverImage(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return null;
        }
        if (v.length() > 2000) {
            throw new IllegalArgumentException("封面图片链接过长（超过 2000 字符）");
        }
        if (v.startsWith("/uploads/")) {
            if (!v.matches("/uploads/[0-9a-fA-F]{32}\\.(jpg|jpeg|png|webp|gif)")) {
                throw new IllegalArgumentException("封面仅支持本系统上传的图片路径（/uploads/&lt;文件名&gt;）");
            }
            return v;
        }
        try {
            URI uri = new URI(v);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("封面链接必须是合法的 http(s) 图片地址或本地上传路径");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("封面链接格式不合法");
        }
        return v;
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? null : t.format(TS);
    }

    public Map<String, Object> statsOf(String userId) {
        // 阶段三扩展位：帖子维度统计（暂空实现，避免误用）
        return new LinkedHashMap<>();
    }

    /** 指定作者集合的已发布帖子数（管理后台用户列表；避免逐人 count 的 N+1） */
    public Map<String, Long> publishedCounts(Collection<String> userIds) {
        Map<String, Long> result = new HashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return result;
        }
        List<String> ids = userIds.stream()
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return result;
        }
        List<TravelPost> rows = postRepository.selectList(new LambdaQueryWrapper<TravelPost>()
                .eq(TravelPost::getStatus, TravelPost.STATUS_PUBLISHED)
                .in(TravelPost::getUserId, ids));
        for (TravelPost p : rows) {
            result.merge(p.getUserId(), 1L, Long::sum);
        }
        return result;
    }

    /**
     * 作者视角：取自己某条帖子的最新一次 AI 审核任务（精简视图）。
     *
     * <p>仅作者本人可查（无授权：他人不允许查询任意帖子的审核细节），
     * 帖子不存在或已被删除 → 404。无任务（提交后异步建任务的几百毫秒窗口）→ 204。
     */
    public Map<String, Object> latestModerationStatus(String userId, Long postId) {
        TravelPost post = postRepository.selectById(postId);
        if (post == null || TravelPost.STATUS_DELETED.equals(post.getStatus())) {
            throw new PostNotFoundException("帖子不存在");
        }
        if (post.getUserId() == null || !post.getUserId().equals(userId)) {
            throw new ForbiddenException("只能查看自己帖子的审核状态");
        }
        if (moderationService == null) {
            return null;
        }
        return moderationService.latestForViewer(ContentModerationTask.TARGET_POST, String.valueOf(postId));
    }
}
