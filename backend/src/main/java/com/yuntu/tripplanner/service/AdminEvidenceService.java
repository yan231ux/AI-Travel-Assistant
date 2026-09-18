package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.exception.PostNotFoundException;
import com.yuntu.tripplanner.model.AuditLog;
import com.yuntu.tripplanner.model.ContentReport;
import com.yuntu.tripplanner.model.PostComment;
import com.yuntu.tripplanner.model.TravelPost;
import com.yuntu.tripplanner.model.TravelPostRevision;
import com.yuntu.tripplanner.repository.AuditLogRepository;
import com.yuntu.tripplanner.repository.ContentReportRepository;
import com.yuntu.tripplanner.repository.PostCommentRepository;
import com.yuntu.tripplanner.repository.TravelPostRepository;
import com.yuntu.tripplanner.repository.TravelPostRevisionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理员审核证据聚合（管理员后台与内容运营中心设计方案 §3.2/§3.4、阶段二验收：
 * "审核员能看到正文、作者、规则命中、质量分和历史；举报详情能看到完整被举报内容与上下文"）。
 *
 * <p>只读聚合，动作仍走既有 PostService/PostReportService（各自校验权限 + 审计），
 * 本服务仅把"审核/举报所需证据"拼成完整上下文，供独立管理页消费。
 * 访问控制要求 {@code CONTENT_REVIEW} 权限（内容审核员/超管可看，城市编辑与推荐运营不可见）。
 */
@Slf4j
@Service
public class AdminEvidenceService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TravelPostRepository postRepository;
    private final PostCommentRepository commentRepository;
    private final ContentReportRepository reportRepository;
    private final AuditLogRepository auditLogRepository;
    private final CommunityUserService communityUserService;
    private final TravelPostRevisionRepository revisionRepository;

    public AdminEvidenceService(TravelPostRepository postRepository,
                                PostCommentRepository commentRepository,
                                ContentReportRepository reportRepository,
                                AuditLogRepository auditLogRepository,
                                CommunityUserService communityUserService,
                                TravelPostRevisionRepository revisionRepository) {
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.reportRepository = reportRepository;
        this.auditLogRepository = auditLogRepository;
        this.communityUserService = communityUserService;
        this.revisionRepository = revisionRepository;
    }

    /* ================= 帖子审核详情（P0-2：审核独立于普通用户详情） ================= */

    /** 帖子审核证据：全字段 + 自动规则信号 + 作者 + 相关举报 + 状态操作审计 */
    public Map<String, Object> postReview(String adminId, Long postId) {
        communityUserService.requirePermission(adminId, AdminPermission.CONTENT_REVIEW);
        TravelPost post = postId == null ? null : postRepository.selectById(postId);
        if (post == null || TravelPost.STATUS_DELETED.equals(post.getStatus())) {
            throw new PostNotFoundException("帖子不存在或已删除: " + postId);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", post.getId());
        m.put("title", post.getTitle());
        m.put("summary", post.getSummary());
        m.put("content", post.getContent());
        m.put("cover_image", post.getCoverImage());
        m.put("city", post.getCity());
        m.put("travel_days", post.getTravelDays());
        m.put("budget", post.getBudget());
        m.put("pace", post.getPace());
        m.put("post_type", post.getPostType());
        m.put("status", post.getStatus());
        m.put("quality_score", post.getQualityScore() == null ? 0 : post.getQualityScore());
        m.put("low_quality", post.getLowQuality() != null && post.getLowQuality() == 1);
        m.put("reject_reason", post.getRejectReason());
        m.put("like_count", nz(post.getLikeCount()));
        m.put("favorite_count", nz(post.getFavoriteCount()));
        m.put("comment_count", nz(post.getCommentCount()));
        m.put("view_count", nz(post.getViewCount()));
        m.put("published_at", fmt(post.getPublishedAt()));
        m.put("created_at", fmt(post.getCreatedAt()));
        m.put("updated_at", fmt(post.getUpdatedAt()));
        m.put("author", authorOf(post.getUserId()));
        m.put("reports", reportsOnTarget(ContentReport.TARGET_POST, post.getId()));
        m.put("audit", auditsOn("post", String.valueOf(post.getId())));
        // P1-1 版本化：已发布帖的待审修改版本 —— 审核页需要看到"将切换成什么"，避免盲审
        TravelPostRevision pending = pendingRevisionOf(post);
        m.put("has_pending_revision", pending != null);
        m.put("pending_revision", pending == null ? null : revisionView(pending));
        return m;
    }

    /** 取帖子的待审修改版本（指针为空或版本已非待审 → null） */
    private TravelPostRevision pendingRevisionOf(TravelPost post) {
        if (post == null || post.getPendingRevisionId() == null) {
            return null;
        }
        TravelPostRevision rev = revisionRepository.selectById(post.getPendingRevisionId());
        return rev != null && rev.isPending() ? rev : null;
    }

    /** 待审版本内容快照（与 PostService.revisionView 同契约，spot 明细复用其反序列化） */
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
        m.put("spots", PostService.readSpots(rev.getSpotsJson()));
        m.put("reject_reason", rev.getRejectReason());
        m.put("edited_at", fmt(rev.getUpdatedAt() == null ? rev.getCreatedAt() : rev.getUpdatedAt()));
        m.put("editor", rev.getEditorId());
        return m;
    }

    /* ================= 举报证据详情（P0-3：快照 → 完整上下文） ================= */

    /**
     * 举报证据：举报单本身 + 被举报内容完整正文/上下文 + 同对象其他举报 + 作者信息 + 处理历史。
     * 评论举报额外带所属帖子上下文（评论 → 帖子正文标题作者状态）。
     */
    public Map<String, Object> reportEvidence(String adminId, Long reportId) {
        communityUserService.requirePermission(adminId, AdminPermission.CONTENT_REVIEW);
        ContentReport report = reportId == null ? null : reportRepository.selectById(reportId);
        if (report == null) {
            throw new PostNotFoundException("举报记录不存在: " + reportId);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", report.getId());
        r.put("target_type", report.getTargetType());
        r.put("target_id", report.getTargetId());
        r.put("reason", report.getReason());
        r.put("detail", report.getDetail());
        r.put("status", report.getStatus());
        r.put("reporter_id", report.getReporterId());
        r.put("reporter_name", communityUserService.nicknameOf(report.getReporterId()));
        r.put("handled_by", report.getHandledBy() == null ? null
                : communityUserService.nicknameOf(report.getHandledBy()));
        r.put("handled_at", fmt(report.getHandledAt()));
        r.put("handle_note", report.getHandleNote());
        r.put("created_at", fmt(report.getCreatedAt()));
        m.put("report", r);
        m.put("audit", auditsOn("report", String.valueOf(report.getId())));

        if (ContentReport.TARGET_POST.equals(report.getTargetType())) {
            TravelPost post = postRepository.selectById(report.getTargetId());
            m.put("target_post", post == null || TravelPost.STATUS_DELETED.equals(post.getStatus())
                    ? null : postView(post));
        } else {
            PostComment comment = commentRepository.selectById(report.getTargetId());
            if (comment == null || PostComment.STATUS_DELETED.equals(comment.getStatus())) {
                m.put("target_comment", null);
                m.put("target_post", null);
            } else {
                Map<String, Object> cv = new LinkedHashMap<>();
                cv.put("id", comment.getId());
                cv.put("post_id", comment.getPostId());
                cv.put("parent_id", comment.getParentId());
                cv.put("content", comment.getContent());
                cv.put("status", comment.getStatus());
                cv.put("author", authorOf(comment.getUserId()));
                cv.put("created_at", fmt(comment.getCreatedAt()));
                m.put("target_comment", cv);
                TravelPost post = postRepository.selectById(comment.getPostId());
                m.put("target_post", post == null || TravelPost.STATUS_DELETED.equals(post.getStatus())
                        ? null : postView(post));
            }
        }
        return m;
    }

    /* ================= 内部拼装 ================= */

    private Map<String, Object> postView(TravelPost post) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", post.getId());
        p.put("title", post.getTitle());
        p.put("summary", post.getSummary());
        p.put("content", post.getContent());
        p.put("cover_image", post.getCoverImage());
        p.put("city", post.getCity());
        p.put("post_type", post.getPostType());
        p.put("status", post.getStatus());
        p.put("quality_score", post.getQualityScore() == null ? 0 : post.getQualityScore());
        p.put("low_quality", post.getLowQuality() != null && post.getLowQuality() == 1);
        p.put("reject_reason", post.getRejectReason());
        p.put("like_count", nz(post.getLikeCount()));
        p.put("favorite_count", nz(post.getFavoriteCount()));
        p.put("comment_count", nz(post.getCommentCount()));
        p.put("view_count", nz(post.getViewCount()));
        p.put("published_at", fmt(post.getPublishedAt()));
        p.put("created_at", fmt(post.getCreatedAt()));
        p.put("author", authorOf(post.getUserId()));
        p.put("reports", reportsOnTarget(ContentReport.TARGET_POST, post.getId()));
        return p;
    }

    /** 作者画像（治理信息）：昵称/角色/注册时间/累计发帖/历史被举报成立数（被举报对象为该作者的帖子） */
    private Map<String, Object> authorOf(String userId) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", userId);
        a.put("nickname", communityUserService.nicknameOf(userId));
        a.put("role", communityUserService.roleOf(userId));
        a.put("registered_at", communityUserService.createdAtOf(userId));
        Long published = postRepository.selectCount(new LambdaQueryWrapper<TravelPost>()
                .eq(TravelPost::getUserId, userId)
                .eq(TravelPost::getStatus, TravelPost.STATUS_PUBLISHED));
        a.put("published_posts", published);
        // 该作者帖子被举报且成立的历史条数
        List<Long> authorPostIds = postRepository.selectList(new LambdaQueryWrapper<TravelPost>()
                        .select(TravelPost::getId)
                        .eq(TravelPost::getUserId, userId))
                .stream().map(TravelPost::getId).collect(Collectors.toList());
        long resolved = 0;
        if (!authorPostIds.isEmpty()) {
            resolved = reportRepository.selectCount(new LambdaQueryWrapper<ContentReport>()
                    .eq(ContentReport::getTargetType, ContentReport.TARGET_POST)
                    .eq(ContentReport::getStatus, ContentReport.STATUS_RESOLVED)
                    .in(ContentReport::getTargetId, authorPostIds));
        }
        a.put("resolved_reports", resolved);
        return a;
    }

    /** 针对某对象的全部举报（含已处理），供重复举报/历史参考 */
    private List<Map<String, Object>> reportsOnTarget(String targetType, Long targetId) {
        List<ContentReport> rows = reportRepository.selectList(new LambdaQueryWrapper<ContentReport>()
                .eq(ContentReport::getTargetType, targetType)
                .eq(ContentReport::getTargetId, targetId)
                .orderByAsc(ContentReport::getCreatedAt));
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> actorIds = rows.stream().map(ContentReport::getReporterId).collect(Collectors.toSet());
        Map<String, String> names = communityUserService.nicknamesOf(actorIds);
        for (ContentReport c : rows) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", c.getId());
            x.put("reason", c.getReason());
            x.put("detail", c.getDetail());
            x.put("status", c.getStatus());
            x.put("reporter_name", names.getOrDefault(c.getReporterId(), c.getReporterId()));
            x.put("created_at", fmt(c.getCreatedAt()));
            out.add(x);
        }
        return out;
    }

    /** 某对象（post/report/comment/guide）最近操作审计（谁在何时对什么做了什么） */
    private List<Map<String, Object>> auditsOn(String targetType, String targetId) {
        List<AuditLog> rows = auditLogRepository.selectList(new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getTargetType, targetType)
                .eq(AuditLog::getTargetId, targetId)
                .orderByDesc(AuditLog::getId)
                .last("LIMIT 12"));
        Set<String> actorIds = rows.stream()
                .map(AuditLog::getActorId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        Map<String, String> names = actorIds.isEmpty() ? new HashMap<>()
                : communityUserService.nicknamesOf(actorIds);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AuditLog a : rows) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("action", a.getAction());
            x.put("category", a.getCategory());
            x.put("actor", a.getActorId() == null ? "系统/匿名"
                    : names.getOrDefault(a.getActorId(), a.getActorId()));
            x.put("detail", a.getDetail());
            x.put("created_at", fmt(a.getCreatedAt()));
            out.add(x);
        }
        return out;
    }

    private static long nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? null : t.format(TS);
    }
}
