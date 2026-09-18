package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.exception.PostNotFoundException;
import com.yuntu.tripplanner.model.AuditLog;
import com.yuntu.tripplanner.model.ContentReport;
import com.yuntu.tripplanner.model.PostComment;
import com.yuntu.tripplanner.model.TravelPost;
import com.yuntu.tripplanner.repository.ContentReportRepository;
import com.yuntu.tripplanner.repository.PostCommentRepository;
import com.yuntu.tripplanner.repository.TravelPostRepository;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * 举报服务（阶段二 §9.7/§10）：用户举报 + 管理员处理。
 *
 * <p>同一用户对同一对象仅一条举报（唯一键，重复举报幂等返回 existed，不刷屏队列）；
 * 举报必须指向真实存在的内容（防恶意空举报）。管理员处理需服务端 ADMIN 校验。
 */
@Slf4j
@Service
public class PostReportService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Set<String> ALLOWED_REASONS = Set.of(
            "广告", "虚假信息", "辱骂", "侵权", "其他");

    private final ContentReportRepository reportRepository;
    private final TravelPostRepository postRepository;
    private final PostCommentRepository commentRepository;
    private final CommunityUserService communityUserService;
    private final AuditService auditService;

    public PostReportService(ContentReportRepository reportRepository,
                             TravelPostRepository postRepository,
                             PostCommentRepository commentRepository,
                             CommunityUserService communityUserService,
                             AuditService auditService) {
        this.reportRepository = reportRepository;
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.communityUserService = communityUserService;
        this.auditService = auditService;
    }

    /** 举报队列项（含被举报内容快照，供审核页直接看） */
    @Data
    public static class ReportItem {
        @JsonProperty("id")
        private Long id;
        @JsonProperty("target_type")
        private String targetType;
        @JsonProperty("target_id")
        private Long targetId;
        @JsonProperty("target_snapshot")
        private String targetSnapshot;
        @JsonProperty("reason")
        private String reason;
        @JsonProperty("detail")
        private String detail;
        @JsonProperty("reporter_id")
        private String reporterId;
        @JsonProperty("reporter_name")
        private String reporterName;
        @JsonProperty("created_at")
        private String createdAt;
        /** PENDING/RESOLVED/DISMISSED（历史队列展示用） */
        @JsonProperty("status")
        private String status;
        @JsonProperty("handled_by")
        private String handledBy;
        @JsonProperty("handled_at")
        private String handledAt;
        /** 处理备注（管理员处理时填写；历史队列展示用） */
        @JsonProperty("handle_note")
        private String handleNote;
    }

    @Data
    public static class ReportPage {
        @JsonProperty("items")
        private List<ReportItem> items;
        @JsonProperty("total")
        private long total;
    }

    /**
     * 提交举报：返回是否首次举报（existed=true 表示此前已举报过，幂等）。
     */
    @Transactional
    public boolean create(String reporterId, String targetType, Long targetId,
                          String reason, String detail) {
        if (targetType == null || targetId == null) {
            throw new IllegalArgumentException("缺少举报对象");
        }
        String type = normalizeTargetType(targetType);
        validateTargetExists(type, targetId);
        guardReportable(type, targetId, reporterId);
        if (reason == null || !ALLOWED_REASONS.contains(reason.trim())) {
            throw new IllegalArgumentException("举报原因需从：广告/虚假信息/辱骂/侵权/其他 中选择");
        }
        ContentReport report = new ContentReport();
        report.setReporterId(reporterId);
        report.setTargetType(type);
        report.setTargetId(targetId);
        report.setReason(reason.trim());
        report.setDetail(detail == null || detail.isBlank() ? null : detail.trim());
        report.setStatus(ContentReport.STATUS_PENDING);
        try {
            reportRepository.insert(report);
            log.info("收到举报: {}#{}({}) by={}", type, targetId, report.getReason(), reporterId);
            auditService.record(reporterId, AuditLog.CAT_CONTENT, "report_created",
                    "report", String.valueOf(report.getId()),
                    AuditService.detailOf("target_type", type, "target_id", targetId,
                            "reason", report.getReason()));
            return false;
        } catch (DuplicateKeyException e) {
            log.debug("重复举报幂等返回: reporter={} target={}#{}", reporterId, type, targetId);
            return true;
        }
    }

    /** 待处理举报队列（管理员） */
    public ReportPage pending(String adminId, int page, int pageSize) {
        communityUserService.requirePermission(adminId, AdminPermission.CONTENT_REVIEW);
        int size = Math.max(1, Math.min(pageSize <= 0 ? 20 : pageSize, 100));
        int pageNo = Math.max(1, page);
        LambdaQueryWrapper<ContentReport> w = new LambdaQueryWrapper<ContentReport>()
                .eq(ContentReport::getStatus, ContentReport.STATUS_PENDING)
                .orderByAsc(ContentReport::getCreatedAt);
        long total = reportRepository.selectCount(w);
        List<ContentReport> rows = reportRepository.selectList(w.last(
                "LIMIT " + size + " OFFSET " + (pageNo - 1) * size));
        List<ReportItem> items = buildItems(rows);
        ReportPage result = new ReportPage();
        result.setItems(items);
        result.setTotal(total);
        return result;
    }

    /** 已处理举报历史（管理员；RESOLVED/DISMISSED，新→旧，含处理人与时间） */
    public ReportPage history(String adminId, int page, int pageSize) {
        communityUserService.requirePermission(adminId, AdminPermission.CONTENT_REVIEW);
        int size = Math.max(1, Math.min(pageSize <= 0 ? 20 : pageSize, 100));
        int pageNo = Math.max(1, page);
        LambdaQueryWrapper<ContentReport> w = new LambdaQueryWrapper<ContentReport>()
                .in(ContentReport::getStatus,
                        ContentReport.STATUS_RESOLVED, ContentReport.STATUS_DISMISSED)
                .orderByDesc(ContentReport::getHandledAt);
        long total = reportRepository.selectCount(w);
        List<ContentReport> rows = reportRepository.selectList(w.last(
                "LIMIT " + size + " OFFSET " + (pageNo - 1) * size));
        List<ReportItem> items = buildItems(rows);
        ReportPage result = new ReportPage();
        result.setItems(items);
        result.setTotal(total);
        return result;
    }

    /**
     * 处理举报：RESOLVE（确认违规 → 隐藏帖子/软删评论）/ DISMISS（证据不足驳回）。
     *
     * @param note 管理员处理备注（可空；随审计与历史记录落库）
     */
    @Transactional
    public void handle(String adminId, Long reportId, String action, String note) {
        communityUserService.requirePermission(adminId, AdminPermission.CONTENT_REVIEW);
        ContentReport report = reportId == null ? null : reportRepository.selectById(reportId);
        if (report == null) {
            throw new PostNotFoundException("举报记录不存在");
        }
        if (!ContentReport.STATUS_PENDING.equals(report.getStatus())) {
            throw new IllegalArgumentException("该举报已处理过");
        }
        String act = action == null ? "" : action.trim().toUpperCase();
        if (act.equals("RESOLVE")) {
            resolveTarget(report);
            report.setStatus(ContentReport.STATUS_RESOLVED);
        } else if (act.equals("DISMISS")) {
            report.setStatus(ContentReport.STATUS_DISMISSED);
        } else {
            throw new IllegalArgumentException("处理动作需为 RESOLVE 或 DISMISS");
        }
        report.setHandledBy(adminId);
        report.setHandledAt(LocalDateTime.now());
        report.setHandleNote(note == null || note.isBlank() ? null : note.trim());
        reportRepository.updateById(report);
        log.info("举报处理完成: reportId={} action={} by={} note={}", reportId, act, adminId,
                report.getHandleNote());
        auditService.record(adminId, AuditLog.CAT_ADMIN,
                "RESOLVE".equals(act) ? "report_resolved" : "report_dismissed",
                "report", String.valueOf(reportId),
                AuditService.detailOf("target_type", report.getTargetType(),
                        "target_id", report.getTargetId(), "reason", report.getReason(),
                        "note", report.getHandleNote()));
    }

    /* ================= 内部 ================= */

    private void resolveTarget(ContentReport report) {
        if (ContentReport.TARGET_POST.equals(report.getTargetType())) {
            TravelPost post = postRepository.selectById(report.getTargetId());
            if (post != null && post.isPubliclyVisible()) {
                post.setStatus(TravelPost.STATUS_HIDDEN);
                post.setPublishedAt(null);
                postRepository.updateById(post);
                log.info("举报成立，帖子已隐藏: postId={}", post.getId());
                // 用户治理（§7）：违规次数 +1（原子自增）
                communityUserService.bumpViolation(post.getUserId());
            }
        } else if (ContentReport.TARGET_COMMENT.equals(report.getTargetType())) {
            PostComment comment = commentRepository.selectById(report.getTargetId());
            if (comment != null && !PostComment.STATUS_DELETED.equals(comment.getStatus())) {
                comment.setStatus(PostComment.STATUS_DELETED);
                commentRepository.updateById(comment);
                log.info("举报成立，评论已删除: commentId={}", comment.getId());
                communityUserService.bumpViolation(comment.getUserId());
            }
        }
    }

    private List<ReportItem> buildItems(List<ContentReport> rows) {
        List<ReportItem> items = new ArrayList<>();
        if (rows.isEmpty()) {
            return items;
        }
        List<Long> postIds = new ArrayList<>();
        List<Long> commentIds = new ArrayList<>();
        for (ContentReport r : rows) {
            if (ContentReport.TARGET_POST.equals(r.getTargetType())) {
                postIds.add(r.getTargetId());
            } else {
                commentIds.add(r.getTargetId());
            }
        }
        Map<Long, String> postTitle = new HashMap<>();
        if (!postIds.isEmpty()) {
            postRepository.selectBatchIds(postIds)
                    .forEach(p -> postTitle.put(p.getId(), p.getTitle()));
        }
        Map<Long, String> commentContent = new HashMap<>();
        if (!commentIds.isEmpty()) {
            commentRepository.selectBatchIds(commentIds)
                    .forEach(c -> commentContent.put(c.getId(), c.getContent()));
        }
        Map<String, String> nicknames = communityUserService.nicknamesOf(
                rows.stream().map(ContentReport::getReporterId).collect(Collectors.toSet()));
        Map<String, String> handlerNames = communityUserService.nicknamesOf(
                rows.stream().map(ContentReport::getHandledBy)
                        .filter(h -> h != null && !h.isBlank())
                        .collect(Collectors.toSet()));
        for (ContentReport r : rows) {
            ReportItem it = new ReportItem();
            it.setId(r.getId());
            it.setTargetType(r.getTargetType());
            it.setTargetId(r.getTargetId());
            String snapshot = ContentReport.TARGET_POST.equals(r.getTargetType())
                    ? postTitle.getOrDefault(r.getTargetId(), "（帖子已不存在）")
                    : commentContent.getOrDefault(r.getTargetId(), "（评论已不存在）");
            it.setTargetSnapshot(snapshot);
            it.setReason(r.getReason());
            it.setDetail(r.getDetail());
            it.setReporterId(r.getReporterId());
            it.setReporterName(nicknames.getOrDefault(r.getReporterId(), r.getReporterId()));
            it.setCreatedAt(r.getCreatedAt() == null ? null : r.getCreatedAt().format(TS));
            it.setStatus(r.getStatus());
            if (r.getHandledBy() != null) {
                it.setHandledBy(handlerNames.getOrDefault(r.getHandledBy(), r.getHandledBy()));
            }
            it.setHandledAt(r.getHandledAt() == null ? null : r.getHandledAt().format(TS));
            it.setHandleNote(r.getHandleNote());
            items.add(it);
        }
        return items;
    }

    /**
     * 举报准入（与前端「能不能点举报」同源，服务端必须同门禁）。
     *
     * <p>修正前的缺口：{@link #create} 只校验"对象是否存在"，于是
     * ① <b>作者可以举报自己的帖子</b>（前端此前也真的显示了举报按钮）；
     * ② <b>未公开内容也能被举报</b>（草稿/审核中/未通过/已下架）。
     * 举报是"给其他用户对抗违规内容"的工具，作者对自己内容有编辑/删除手段；
     * 而未公开内容本就没有对外暴露，举报既无意义，又可能被用来给他人刷违规次数
     * （举报成立会 bumpViolation 给作者记违规），因此必须在服务端挡住。
     */
    private void guardReportable(String type, Long targetId, String reporterId) {
        if (!ContentReport.TARGET_POST.equals(type)) {
            return; // 评论：仅要求评论存在且未删除（父帖可见性由发布路径保证）
        }
        TravelPost post = postRepository.selectById(targetId);
        if (post == null) {
            return; // 存在性已由 validateTargetExists 判定
        }
        if (reporterId != null && reporterId.equals(post.getUserId())) {
            throw new IllegalArgumentException("不能举报自己发布的内容");
        }
        if (!post.isPubliclyVisible()) {
            throw new IllegalArgumentException("该内容尚未公开，无法举报");
        }
    }

    private void validateTargetExists(String type, Long targetId) {
        if (ContentReport.TARGET_POST.equals(type)) {
            TravelPost post = postRepository.selectById(targetId);
            if (post == null || TravelPost.STATUS_DELETED.equals(post.getStatus())) {
                throw new PostNotFoundException("被举报的帖子不存在");
            }
        } else {
            PostComment comment = commentRepository.selectById(targetId);
            if (comment == null || PostComment.STATUS_DELETED.equals(comment.getStatus())) {
                throw new PostNotFoundException("被举报的评论不存在");
            }
        }
    }

    private String normalizeTargetType(String type) {
        String t = type == null ? "" : type.trim().toUpperCase();
        if (ContentReport.TARGET_POST.equals(t) || ContentReport.TARGET_COMMENT.equals(t)) {
            return t;
        }
        throw new IllegalArgumentException("举报对象类型需为 POST 或 COMMENT");
    }
}
