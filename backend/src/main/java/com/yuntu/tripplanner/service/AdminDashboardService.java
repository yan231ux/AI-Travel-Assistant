package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.model.AuditLog;
import com.yuntu.tripplanner.model.CityGuide;
import com.yuntu.tripplanner.model.ContentReport;
import com.yuntu.tripplanner.model.TravelPost;
import com.yuntu.tripplanner.model.User;
import com.yuntu.tripplanner.repository.AuditLogRepository;
import com.yuntu.tripplanner.repository.CityGuideRepository;
import com.yuntu.tripplanner.repository.ContentReportRepository;
import com.yuntu.tripplanner.repository.TravelPostRepository;
import com.yuntu.tripplanner.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 管理后台首页汇总（管理员后台与内容运营中心设计方案 §3.1 Dashboard）：
 * 待办计数（待审帖子/举报/攻略/低质）+ 内容概览 + 最近操作。
 *
 * 失败语义（P0-1 修复）：**统计查询失败返回 null（JSON null），绝不伪装成业务上的 0**。
 * 旧实现把异常吞成 0，管理员会把"数据库故障"误读成"系统没有数据"。
 * 现在每个失败项会记录到响应的 errors 数组（key + 人读原因），前端据此显示
 * "— / 数据暂时不可用 / 重试"，与"真的是 0"在视觉上明确区分。
 *
 * <p><b>权限</b>：汇总本身是"能进后台"即可看（待办与内容概览属于所有管理端角色的日常工作），
 * 但其中的<b>最近操作</b>来自审计日志，与 /admin/audit-logs 同源同权限，只对持有
 * {@link AdminPermission#AUDIT_VIEW} 的角色返回（否则置空并下发 {@code recent_ops_visible=false}）。
 */
@Slf4j
@Service
public class AdminDashboardService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String ERR_MSG = "查询失败，请检查数据库";

    private final TravelPostRepository postRepository;
    private final ContentReportRepository reportRepository;
    private final CityGuideRepository guideRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final CommunityUserService communityUserService;

    public AdminDashboardService(TravelPostRepository postRepository,
                                 ContentReportRepository reportRepository,
                                 CityGuideRepository guideRepository,
                                 UserRepository userRepository,
                                 AuditLogRepository auditLogRepository,
                                 CommunityUserService communityUserService) {
        this.postRepository = postRepository;
        this.reportRepository = reportRepository;
        this.guideRepository = guideRepository;
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
        this.communityUserService = communityUserService;
    }

    /** 看板汇总（requireAdmin 由 controller 统一执行） */
    public Map<String, Object> summary(String adminId) {
        communityUserService.requireAdmin(adminId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generated_at", LocalDateTime.now().format(TS));
        // 失败项清单：key 形如 "todo.pending_posts"，前端据此把对应 KPI 显示为不可用
        List<Map<String, Object>> errors = new ArrayList<>();

        // 今日待办
        Map<String, Object> todo = new LinkedHashMap<>();
        todo.put("pending_posts", count("todo.pending_posts", errors, () -> postRepository.selectCount(
                new LambdaQueryWrapper<TravelPost>()
                        .eq(TravelPost::getStatus, TravelPost.STATUS_PENDING_REVIEW))));
        todo.put("pending_reports", count("todo.pending_reports", errors, () -> reportRepository.selectCount(
                new LambdaQueryWrapper<ContentReport>()
                        .eq(ContentReport::getStatus, ContentReport.STATUS_PENDING))));
        todo.put("pending_guides", count("todo.pending_guides", errors, () -> guideRepository.selectCount(
                new LambdaQueryWrapper<CityGuide>()
                        .eq(CityGuide::getStatus, CityGuide.STATUS_PENDING_REVIEW))));
        todo.put("low_quality_pending", count("todo.low_quality_pending", errors, () -> postRepository.selectCount(
                new LambdaQueryWrapper<TravelPost>()
                        .eq(TravelPost::getStatus, TravelPost.STATUS_PENDING_REVIEW)
                        .eq(TravelPost::getLowQuality, 1))));
        body.put("todo", todo);

        // 内容概览
        Map<String, Object> content = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        content.put("posts_total", count("content.posts_total", errors,
                () -> postRepository.selectCount(new LambdaQueryWrapper<>())));
        content.put("published_posts", count("content.published_posts", errors, () -> postRepository.selectCount(
                new LambdaQueryWrapper<TravelPost>()
                        .eq(TravelPost::getStatus, TravelPost.STATUS_PUBLISHED))));
        content.put("hidden_posts", count("content.hidden_posts", errors, () -> postRepository.selectCount(
                new LambdaQueryWrapper<TravelPost>()
                        .eq(TravelPost::getStatus, TravelPost.STATUS_HIDDEN))));
        content.put("published_today", count("content.published_today", errors, () -> postRepository.selectCount(
                new LambdaQueryWrapper<TravelPost>()
                        .eq(TravelPost::getStatus, TravelPost.STATUS_PUBLISHED)
                        .ge(TravelPost::getPublishedAt, today.atStartOfDay()))));
        content.put("reports_7d", count("content.reports_7d", errors, () -> reportRepository.selectCount(
                new LambdaQueryWrapper<ContentReport>()
                        .ge(ContentReport::getCreatedAt, LocalDateTime.now().minusDays(7)))));
        content.put("guides_total", count("content.guides_total", errors,
                () -> guideRepository.selectCount(new LambdaQueryWrapper<>())));
        content.put("guides_published", count("content.guides_published", errors, () -> guideRepository.selectCount(
                new LambdaQueryWrapper<CityGuide>()
                        .eq(CityGuide::getStatus, CityGuide.STATUS_PUBLISHED))));
        content.put("cities_with_guide", count("content.cities_with_guide", errors, () -> {
            Set<String> cities = guideRepository.selectList(new LambdaQueryWrapper<CityGuide>()
                            .select(CityGuide::getCity))
                    .stream().map(CityGuide::getCity).collect(Collectors.toSet());
            return (long) cities.size();
        }));
        content.put("users_total", count("content.users_total", errors,
                () -> userRepository.selectCount(new LambdaQueryWrapper<User>())));
        // 举报成立率 = RESOLVED / (RESOLVED + DISMISSED)；任一分子分母不可用则整体不可用
        Long resolved = count("content.report_resolved", errors, () -> reportRepository.selectCount(
                new LambdaQueryWrapper<ContentReport>()
                        .eq(ContentReport::getStatus, ContentReport.STATUS_RESOLVED)));
        Long dismissed = count("content.report_dismissed", errors, () -> reportRepository.selectCount(
                new LambdaQueryWrapper<ContentReport>()
                        .eq(ContentReport::getStatus, ContentReport.STATUS_DISMISSED)));
        content.put("report_resolved", resolved);
        content.put("report_dismissed", dismissed);
        if (resolved == null || dismissed == null) {
            errors.add(Map.of("key", "content.report_resolve_rate", "error", ERR_MSG));
            content.put("report_resolve_rate", null);
        } else {
            content.put("report_resolve_rate", resolved + dismissed == 0 ? null
                    : Math.round(resolved * 1000.0 / (resolved + dismissed)) / 10.0);
        }
        body.put("content", content);

        // 最近操作（谁在何时对什么做了什么）。
        // 权限收口：这块内容和 /admin/audit-logs 是同一份审计数据，必须同权限——
        // 否则"审核员能看板但进不去审计页"就成了一条绕过 AUDIT_VIEW 的旁路
        // （明细里会出现"谁把谁改成了什么角色"这类跨域治理信息）。
        // 无审计权限时不出这块数据（recent_ops_visible=false，前端据此隐藏卡片）。
        // 有权限时，审计查询失败同样显式降级，不伪装成"没有记录"。
        boolean canViewAudit = communityUserService.hasPermission(adminId, AdminPermission.AUDIT_VIEW);
        body.put("recent_ops_visible", canViewAudit);
        if (!canViewAudit) {
            body.put("recent_ops", List.of());
        } else {
            try {
                body.put("recent_ops", recentOps());
            } catch (Exception e) {
                log.error("看板最近操作查询失败", e);
                errors.add(Map.of("key", "recent_ops", "error", ERR_MSG));
                body.put("recent_ops", List.of());
            }
        }
        body.put("degraded", !errors.isEmpty());
        body.put("errors", errors);
        return body;
    }

    /** 最近操作：审计日志最新 10 条，操作者昵称批量解析 */
    private List<Map<String, Object>> recentOps() {
        List<Map<String, Object>> recent = new ArrayList<>();
        List<AuditLog> rows = auditLogRepository.selectList(new LambdaQueryWrapper<AuditLog>()
                .orderByDesc(AuditLog::getId)
                .last("LIMIT 10"));
        Set<String> actorIds = rows.stream().map(AuditLog::getActorId)
                .filter(id -> id != null && !id.isBlank()).collect(Collectors.toSet());
        Map<String, String> names = actorIds.isEmpty() ? Map.of()
                : communityUserService.nicknamesOf(new HashSet<>(actorIds));
        for (AuditLog a : rows) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("action", a.getAction());
            x.put("category", a.getCategory());
            x.put("actor", a.getActorId() == null ? "系统/匿名"
                    : names.getOrDefault(a.getActorId(), a.getActorId()));
            x.put("target_type", a.getTargetType());
            x.put("target_id", a.getTargetId());
            x.put("detail", a.getDetail());
            x.put("created_at", fmt(a.getCreatedAt()));
            recent.add(x);
        }
        return recent;
    }

    /**
     * 单项统计：失败（含返回 null）记入 errors 并返回 null，绝不返回 0。
     * key 用于前端定位是哪个 KPI 不可用，同时便于日志排查。
     */
    private Long count(String key, List<Map<String, Object>> errors, Supplier<Long> query) {
        try {
            Long v = query.get();
            if (v == null) {
                errors.add(Map.of("key", key, "error", ERR_MSG));
                log.error("看板统计 {} 返回空值，按不可用处理", key);
                return null;
            }
            return v;
        } catch (Exception e) {
            log.error("看板统计 {} 失败，按不可用处理", key, e);
            errors.add(Map.of("key", key, "error", ERR_MSG));
            return null;
        }
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? null : t.format(TS);
    }
}
