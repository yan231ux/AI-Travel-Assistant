package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.SpotAdminService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 景点数据治理控制器（/admin/spots：管理员后台与内容运营中心设计方案 §5）。
 *
 * <p>能力：列表/详情 → 编辑（人工锁定）/标记异常（flag）/上下线/重复合并/
 * 手动重同步/重新匹配攻略。全部服务端要求 SPOT_GOVERN 权限（CommunityUserService.requirePermission → 403），
 * 每个治理动作由 SpotAdminService 写审计日志（spot_edited/flagged/offlined/onlined/merged/
 * resynced/rematched）。Service 抛 IllegalArgumentException → 全局 400（含业务提示）。
 */
@Slf4j
@RestController
@RequestMapping("/admin/spots")
public class AdminSpotController {

    private final SpotAdminService spotAdminService;

    public AdminSpotController(SpotAdminService spotAdminService) {
        this.spotAdminService = spotAdminService;
    }

    /** 景点治理列表（城市/上下架/治理标记/关键词过滤 + 概览计数） */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String flag,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", spotAdminService.page(UserContext.getUserId(), city, status, flag,
                keyword, page, pageSize));
        return ResponseEntity.ok(body);
    }

    /** 治理详情（完整字段 + 收藏数/别名行/攻略关联/帖子关联数） */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long id) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", spotAdminService.detail(UserContext.getUserId(), id));
        return ResponseEntity.ok(body);
    }

    /**
     * 编辑景点字段（白名单人工修正）：改过的字段自动人工锁定（同步不再覆盖）；
     * 可传 unlockFields 显式解锁。city 不允许直接改（错误归属走合并/ERROR_POI）。
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> edit(@PathVariable Long id,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        spotAdminService.edit(UserContext.getUserId(), id, body == null ? Map.of() : body);
        return ok("已保存（修改字段已人工锁定，自动同步不再覆盖；可传 unlockFields 解锁）");
    }

    /** 设置/清除治理标记（flag 空则清除；NON_SPOT/CLOSED/ERROR_POI 自动下线） */
    @PostMapping("/{id}/flag")
    public ResponseEntity<Map<String, Object>> flag(@PathVariable Long id,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        String flagValue = body == null ? null : strOf(body.get("flag"));
        String reason = body == null ? null : strOf(body.get("reason"));
        boolean clearing = flagValue == null || flagValue.isBlank();
        spotAdminService.setFlag(UserContext.getUserId(), id, flagValue, reason);
        return ok(clearing ? "已清除治理标记" : "已标记 " + flagValue.trim().toUpperCase()
                + "（非景点/已关闭/错误 POI 已自动下线）");
    }

    /** 下线（人工；可填原因，写入 flag_reason 与审计） */
    @PostMapping("/{id}/offline")
    public ResponseEntity<Map<String, Object>> offline(@PathVariable Long id,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        spotAdminService.offline(UserContext.getUserId(), id,
                body == null ? null : strOf(body.get("reason")));
        return ok("已下线（不再进入推荐与展示）");
    }

    /** 重新上线（存在未清除治理标记时拒绝） */
    @PostMapping("/{id}/online")
    public ResponseEntity<Map<String, Object>> online(@PathVariable Long id) {
        spotAdminService.online(UserContext.getUserId(), id);
        return ok("已重新上线");
    }

    /** 合并重复景点：本行并入目标（source 下线 + merged_into；收藏/帖子/攻略引用自动重定向） */
    @PostMapping("/{id}/merge")
    public ResponseEntity<Map<String, Object>> merge(@PathVariable Long id,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = spotAdminService.merge(UserContext.getUserId(), id,
                body == null ? null : strOf(body.get("targetSpotId")),
                body == null ? null : strOf(body.get("reason")));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "合并完成：本景点已下线并指向目标（收藏 "
                + result.get("favorites_moved") + "、攻略关联 " + result.get("guide_links_moved")
                + "、帖子关联 " + result.get("post_links_moved") + " 已重定向）");
        resp.put("data", result);
        return ResponseEntity.ok(resp);
    }

    /** 手动触发单点重新同步（强刷高德；人工锁定字段不覆盖） */
    @PostMapping("/{id}/resync")
    public ResponseEntity<Map<String, Object>> resync(@PathVariable Long id) {
        Map<String, Object> result = spotAdminService.resync(UserContext.getUserId(), id);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", String.valueOf(result.get("message")));
        resp.put("data", result);
        return ResponseEntity.ok(resp);
    }

    /** 重新匹配 RAG 攻略卡片（命中回填简介/升质量；描述/标签锁定则跳过） */
    @PostMapping("/{id}/rematch-guide")
    public ResponseEntity<Map<String, Object>> rematchGuide(@PathVariable Long id) {
        Map<String, Object> result = spotAdminService.rematchGuide(UserContext.getUserId(), id);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", String.valueOf(result.get("message")));
        resp.put("data", result);
        return ResponseEntity.ok(resp);
    }

    /* ================= 载体 ================= */

    private static String strOf(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isBlank() ? null : s;
    }

    private static ResponseEntity<Map<String, Object>> ok(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", message);
        return ResponseEntity.ok(body);
    }
}
