package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.model.AbExperiment;
import com.yuntu.tripplanner.model.AuditLog;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.AbExperimentService;
import com.yuntu.tripplanner.service.AuditService;
import com.yuntu.tripplanner.service.CommunityUserService;
import com.yuntu.tripplanner.service.FeedMonitorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 推荐运营控制器（阶段四任务 6/7/10：推荐 A/B 实验 + 推荐流监控 + 全链路审计查询，管理后台）。
 *
 * <p>全部接口服务端校验 ADMIN 角色（CommunityUserService.requireAdmin → 非管理员 403），
 * 不依赖前端隐藏按钮；实验创建/关闭与监控读数只读/写平台侧表，不触碰用户业务数据。
 * 普通用户侧实验变体由推荐流服务在请求内 resolveVariant 决定，本控制器只做管理面；
 * 实验开/关等运营动作写全链路审计（任务 10，CAT_OPS），审计查询也在本控制器。
 */
@Slf4j
@RestController
@RequestMapping("/admin")
public class FeedOpsController {

    private final AbExperimentService abExperimentService;
    private final FeedMonitorService feedMonitorService;
    private final CommunityUserService communityUserService;
    private final AuditService auditService;

    public FeedOpsController(AbExperimentService abExperimentService,
                             FeedMonitorService feedMonitorService,
                             CommunityUserService communityUserService,
                             AuditService auditService) {
        this.abExperimentService = abExperimentService;
        this.feedMonitorService = feedMonitorService;
        this.communityUserService = communityUserService;
        this.auditService = auditService;
    }

    /** 实验列表（管理员）：含每变体参与人数 */
    @GetMapping("/experiments")
    public ResponseEntity<Map<String, Object>> experiments() {
        communityUserService.requireAdmin(UserContext.getUserId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (AbExperimentService.ExperimentWithStat s : abExperimentService.listAll()) {
            items.add(expMap(s));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("items", items);
        return ResponseEntity.ok(body);
    }

    /** 创建实验（管理员） */
    @PostMapping("/experiments")
    public ResponseEntity<Map<String, Object>> createExperiment(
            @RequestBody(required = false) Map<String, String> body) {
        communityUserService.requireAdmin(UserContext.getUserId());
        String name = body == null ? null : body.get("name");
        String feedType = body == null ? null : body.get("feedType");
        String strategy = body == null ? null : body.get("strategy");
        Integer traffic = intOf(body == null ? null : body.get("trafficPercent"));
        Integer control = intOf(body == null ? null : body.get("controlPercent"));
        AbExperiment exp = abExperimentService.create(name,
                body == null ? null : body.get("description"), feedType, strategy,
                traffic == null ? 100 : traffic, control == null ? 50 : control);
        auditService.record(UserContext.getUserId(), AuditLog.CAT_OPS, "experiment_created",
                "experiment", exp.getExpName(),
                AuditService.detailOf("feed_type", feedType, "strategy", strategy,
                        "traffic_percent", exp.getTrafficPercent(),
                        "control_percent", exp.getControlPercent()));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "实验已创建：" + exp.getExpName());
        resp.put("experiment", expMap(abExperimentService.statOf(exp)));
        return ResponseEntity.ok(resp);
    }

    /** 关闭实验（管理员）：CLOSED 后推荐流回到基线，分桶与曝光不再按变体生效 */
    @PostMapping("/experiments/{name}/close")
    public ResponseEntity<Map<String, Object>> closeExperiment(@PathVariable String name) {
        communityUserService.requireAdmin(UserContext.getUserId());
        AbExperiment exp = abExperimentService.close(name);
        auditService.record(UserContext.getUserId(), AuditLog.CAT_OPS, "experiment_closed",
                "experiment", exp.getExpName(), null);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "实验已关闭：" + exp.getExpName());
        return ResponseEntity.ok(resp);
    }

    /** 推荐流监控（管理员）：近 N 天两条推荐流的曝光/命中/质量构成/A-B 对照/反馈漏斗 */
    @GetMapping("/feed-monitor")
    public ResponseEntity<Map<String, Object>> feedMonitor(
            @RequestParam(defaultValue = "7") int days) {
        communityUserService.requireAdmin(UserContext.getUserId());
        FeedMonitorService.MonitorReport report = feedMonitorService.report(days);
        Map<String, Object> feeds = new LinkedHashMap<>();
        report.feeds().forEach((k, v) -> feeds.put(k, v.toMap()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("days", report.days());
        body.put("feeds", feeds);
        return ResponseEntity.ok(body);
    }

    /* ================= 全链路审计查询（任务 10） ================= */

    /** 审计日志（管理员）：按操作者/分类/时间窗过滤，新→旧；actor 昵称批量解析展示 */
    @GetMapping("/audit-logs")
    public ResponseEntity<Map<String, Object>> auditLogs(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        communityUserService.requireAdmin(UserContext.getUserId());
        AuditService.AuditPage result = auditService.page(actor, category, days, page, pageSize);
        List<String> actorIds = new ArrayList<>();
        for (var row : result.items()) {
            if (row.getActorId() != null) {
                actorIds.add(row.getActorId());
            }
        }
        Map<String, String> nicknames = actorIds.isEmpty() ? Map.of()
                : communityUserService.nicknamesOf(actorIds);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var row : result.items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.getId());
            m.put("actor_id", row.getActorId());
            m.put("actor_name", row.getActorId() == null ? null
                    : nicknames.getOrDefault(row.getActorId(), row.getActorId()));
            m.put("category", row.getCategory());
            m.put("action", row.getAction());
            m.put("target_type", row.getTargetType());
            m.put("target_id", row.getTargetId());
            m.put("detail", row.getDetail());
            m.put("created_at", row.getCreatedAt() == null ? null
                    : row.getCreatedAt().toString().replace("T", " "));
            items.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("items", items);
        body.put("total", result.total());
        body.put("page", result.page());
        return ResponseEntity.ok(body);
    }

    /* ================= 载体转换 ================= */

    private Map<String, Object> expMap(AbExperimentService.ExperimentWithStat s) {
        AbExperiment exp = s.experiment();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", exp.getId());
        m.put("name", exp.getExpName());
        m.put("description", exp.getDescription());
        m.put("feed_type", exp.getFeedType());
        m.put("strategy", exp.getStrategy());
        m.put("status", exp.getStatus());
        m.put("traffic_percent", exp.getTrafficPercent());
        m.put("control_percent", exp.getControlPercent());
        m.put("control_users", s.controlUsers());
        m.put("treatment_users", s.treatmentUsers());
        m.put("started_at", exp.getStartedAt() == null ? null
                : exp.getStartedAt().toString().replace("T", " "));
        m.put("closed_at", exp.getClosedAt() == null ? null
                : exp.getClosedAt().toString().replace("T", " "));
        return m;
    }

    private static Integer intOf(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
