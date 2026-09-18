package com.yuntu.tripplanner.controller;

import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.security.UserContext;
import com.yuntu.tripplanner.service.AdminUserService;
import com.yuntu.tripplanner.service.CommunityUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户与账号治理控制器（/admin/users：管理员后台与内容运营中心设计方案 §7 + §11 第五阶段）。
 *
 * <p>能力：治理列表（搜索/状态/注册时间段）→ 治理动作（限制发帖/暂停评论/暂停账号及恢复）
 * → <b>角色分配</b>（第五阶段：权限点控制 + 高风险操作二次确认）。
 *
 * <p><b>权限</b>：整组接口都需要 {@link AdminPermission#USER_GOVERN}（实际等价于只有
 * SUPER_ADMIN 能进）—— 因为这里能改别人的角色，属于"管人和底数"的范围。
 * 每个治理动作由 AdminUserService 写审计（user_governed / user_role_assigned）。
 * Service 抛 IllegalArgumentException → 全局 400（含业务提示）；PostNotFoundException → 404。
 */
@Slf4j
@RestController
@RequestMapping("/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final CommunityUserService communityUserService;

    public AdminUserController(AdminUserService adminUserService,
                               CommunityUserService communityUserService) {
        this.adminUserService = adminUserService;
        this.communityUserService = communityUserService;
    }

    /** 用户治理列表（keyword=用户名/昵称；status=ALL/ACTIVE/SUSPENDED；from/to=注册时间段） */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        communityUserService.requirePermission(UserContext.getUserId(), AdminPermission.USER_GOVERN);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", adminUserService.page(UserContext.getUserId(), keyword, status,
                from, to, page, pageSize));
        return ResponseEntity.ok(body);
    }

    /**
     * 治理动作（POST_LIMIT/UNPOST_LIMIT/COMMENT_BAN/UNCOMMENT_BAN/SUSPEND/RESTORE），
     * 需带 reason 说明。限制即时生效于发帖/评论/登录真实链路。
     */
    @PostMapping("/{id}/govern")
    public ResponseEntity<Map<String, Object>> govern(@PathVariable Long id,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        communityUserService.requirePermission(UserContext.getUserId(), AdminPermission.USER_GOVERN);
        Map<String, Object> req = body == null ? Map.of() : body;
        String action = strOf(req.get("action"));
        String reason = strOf(req.get("reason"));
        if (action == null) {
            throw new IllegalArgumentException(
                    "治理动作不能为空（POST_LIMIT/UNPOST_LIMIT/COMMENT_BAN/UNCOMMENT_BAN/SUSPEND/RESTORE）");
        }
        Map<String, Object> data = adminUserService.govern(UserContext.getUserId(), id, action, reason);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", String.valueOf(data.get("note")));
        resp.put("data", data);
        return ResponseEntity.ok(resp);
    }

    /**
     * 分配管理端角色（高风险操作，§11 第五阶段第 6 条）。
     *
     * <p>请求体：{@code {role, reason, confirm}} —— {@code confirm} 必须为 true 且
     * {@code reason} 非空，否则服务端直接拒绝（前端弹窗二次确认只是体验层，真正的把关在这里）。
     */
    @PostMapping("/{id}/role")
    public ResponseEntity<Map<String, Object>> assignRole(@PathVariable Long id,
                                                          @RequestBody(required = false) Map<String, Object> body) {
        communityUserService.requirePermission(UserContext.getUserId(), AdminPermission.USER_GOVERN);
        Map<String, Object> req = body == null ? Map.of() : body;
        Map<String, Object> data = adminUserService.assignRole(
                UserContext.getUserId(), id, strOf(req.get("role")),
                Boolean.TRUE.equals(req.get("confirm")), strOf(req.get("reason")));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", String.valueOf(data.get("note")));
        resp.put("data", data);
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
}
