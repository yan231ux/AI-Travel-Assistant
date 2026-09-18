package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yuntu.tripplanner.common.AdminPermission;
import com.yuntu.tripplanner.common.AdminRole;
import com.yuntu.tripplanner.exception.PostNotFoundException;
import com.yuntu.tripplanner.model.AuditLog;
import com.yuntu.tripplanner.model.User;
import com.yuntu.tripplanner.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户与账号治理服务（管理员后台与内容运营中心设计方案 §7）。
 *
 * <p>与旧 /community/moderation/users（内容审核语境下的简单分页）不同：这里是正式治理入口，
 * 支持按用户名/昵称搜索、注册时间段与账号状态筛选，并落地治理动作：
 * POST_LIMIT（限制发帖）/ COMMENT_BAN（暂停评论）/ SUSPEND（暂停账号）及对应恢复动作，
 * 全部 requireAdmin + 审计（user_governed）。限制在发帖/评论/登录三个真实链路即时生效，
 * 不做请求级拦截（已登录老 token 演示语义见类注释：暂停后重新登录即被拒）。
 *
 * <p>保护：ADMIN 账号不可被执行治理动作（避免误锁管理入口）；删除（deleted=1）用户不可见。
 */
@Slf4j
@Service
public class AdminUserService {

    private final UserRepository userRepository;
    private final CommunityUserService communityUserService;
    private final PostService postService;
    private final AuditService auditService;

    public AdminUserService(UserRepository userRepository,
                            CommunityUserService communityUserService,
                            PostService postService,
                            AuditService auditService) {
        this.userRepository = userRepository;
        this.communityUserService = communityUserService;
        this.postService = postService;
        this.auditService = auditService;
    }

    /** 治理动作常量 */
    public static final String ACT_POST_LIMIT = "POST_LIMIT";
    public static final String ACT_UNPOST_LIMIT = "UNPOST_LIMIT";
    public static final String ACT_COMMENT_BAN = "COMMENT_BAN";
    public static final String ACT_UNCOMMENT_BAN = "UNCOMMENT_BAN";
    public static final String ACT_SUSPEND = "SUSPEND";
    public static final String ACT_RESTORE = "RESTORE";

    /** 治理列表（keyword=用户名/昵称模糊；status=ALL/ACTIVE/SUSPENDED；from/to=注册时间段） */
    public Map<String, Object> page(String adminId, String keyword, String status,
                                    String from, String to, int page, int pageSize) {
        communityUserService.requirePermission(adminId, AdminPermission.USER_GOVERN);
        int size = Math.max(1, Math.min(pageSize <= 0 ? 20 : pageSize, 100));
        int pageNo = Math.max(1, page);
        LambdaQueryWrapper<User> w = new LambdaQueryWrapper<User>()
                .orderByDesc(User::getCreatedAt);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            w.and(x -> x.like(User::getUsername, kw).or().like(User::getNickname, kw));
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            w.eq(User::getAccountStatus, status.trim().toUpperCase());
        }
        if (from != null && !from.isBlank()) {
            w.ge(User::getCreatedAt, from.trim() + " 00:00:00");
        }
        if (to != null && !to.isBlank()) {
            w.le(User::getCreatedAt, to.trim() + " 23:59:59");
        }
        long total = userRepository.selectCount(w);
        List<User> rows = userRepository.selectList(w.last(
                "LIMIT " + size + " OFFSET " + (pageNo - 1) * size));
        Map<String, Long> postCounts = postService.publishedCounts(
                rows.stream().map(u -> String.valueOf(u.getId())).toList());
        List<Map<String, Object>> items = new ArrayList<>();
        for (User u : rows) {
            items.add(toView(u, postCounts.getOrDefault(String.valueOf(u.getId()), 0L)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", total);
        body.put("page", pageNo);
        body.put("pageSize", size);
        return body;
    }

    /**
     * 治理动作（POST_LIMIT/UNPOST_LIMIT/COMMENT_BAN/UNCOMMENT_BAN/SUSPEND/RESTORE）。
     * 限制即时生效：发帖→PostService 校验、评论→PostCommentService 校验、登录→AuthService 拒绝。
     */
    @Transactional
    public Map<String, Object> govern(String adminId, long targetUserId, String action, String reason) {
        communityUserService.requirePermission(adminId, AdminPermission.USER_GOVERN);
        User target = userRepository.selectById(targetUserId);
        if (target == null) {
            throw new PostNotFoundException("用户不存在");
        }
        // 任何管理端角色都不接受"用户治理"动作 —— 治理的对象是普通用户，不是同事。
        // 这里必须用 AdminRole 判定而不是比对字符串 "ADMIN"：否则新加的审核员/编辑/运营账号
        // 会被这条判断漏掉，出现"限制发帖"打到内部账号上的情况。
        if (AdminRole.fromCode(target.getRole()).isAdminSide()) {
            throw new IllegalArgumentException("管理端账号不接受用户治理动作（如需停用请先调整其角色）");
        }
        String act = action == null ? "" : action.trim().toUpperCase();
        Map<String, Object> fields = new LinkedHashMap<>();
        String note;
        switch (act) {
            case ACT_POST_LIMIT -> {
                fields.put("post_limited", true);
                note = "已限制发帖";
            }
            case ACT_UNPOST_LIMIT -> {
                fields.put("post_limited", false);
                note = "已解除发帖限制";
            }
            case ACT_COMMENT_BAN -> {
                fields.put("comment_banned", true);
                note = "已暂停评论";
            }
            case ACT_UNCOMMENT_BAN -> {
                fields.put("comment_banned", false);
                note = "已恢复评论";
            }
            case ACT_SUSPEND -> {
                fields.put("account_status", User.STATUS_SUSPENDED);
                note = "已暂停账号（登录即拒绝）";
            }
            case ACT_RESTORE -> {
                fields.put("account_status", User.STATUS_ACTIVE);
                note = "已恢复账号";
            }
            default -> throw new IllegalArgumentException(
                    "治理动作需为 POST_LIMIT/UNPOST_LIMIT/COMMENT_BAN/UNCOMMENT_BAN/SUSPEND/RESTORE");
        }
        communityUserService.applyGovernance(targetUserId, fields);
        auditService.record(adminId, AuditLog.CAT_ADMIN, "user_governed", "user",
                String.valueOf(targetUserId),
                AuditService.detailOf("action", act, "username", target.getUsername(),
                        "reason", reason == null || reason.isBlank() ? null : reason.trim()));
        log.info("用户治理动作: user={}({}) action={} by={} reason={}", target.getUsername(),
                targetUserId, act, adminId, reason);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("note", note);
        r.put("account_status", fields.getOrDefault("account_status",
                target.getAccountStatus() == null ? User.STATUS_ACTIVE : target.getAccountStatus()));
        return r;
    }

    /**
     * 分配管理端角色（设计方案 §11 第五阶段第 6 条：权限点控制 + 高风险操作二次确认）。
     *
     * <p><b>为什么算高风险</b>：这一步直接决定"谁能进后台、能操作哪一块"。把账号提成
     * SUPER_ADMIN 等于交出平台底座（景点数据、用户治理、审计）的全部权力，因此四道约束：
     * <ol>
     *   <li>调用者必须拥有 {@link AdminPermission#USER_GOVERN}（controller 层已校验，这里再兜一次）；</li>
     *   <li>必须 {@code confirmed=true} 且填写非空原因 —— 防误点，也防脚本批量提权；</li>
     *   <li>不能改自己的角色 —— 既防自降级把自己锁在门外，也防绕过同事监督自我提权；</li>
     *   <li>不能把<b>最后一个</b>超级管理员降级 —— 平台必须始终有人能兜底。</li>
     * </ol>
     * 成功分配写审计（user_role_assigned），前后角色 + 原因一起留痕。
     */
    @Transactional
    public Map<String, Object> assignRole(String adminId, long targetUserId, String roleCode,
                                          boolean confirmed, String reason) {
        communityUserService.requirePermission(adminId, AdminPermission.USER_GOVERN);
        if (!confirmed) {
            throw new IllegalArgumentException("高风险操作需二次确认后提交");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("分配角色必须填写原因（审计留痕）");
        }
        if (!AdminRole.isAssignable(roleCode)) {
            throw new IllegalArgumentException("角色需为 USER/CONTENT_REVIEWER/CITY_EDITOR/"
                    + "RECOMMENDATION_OPERATOR/SUPER_ADMIN 之一");
        }
        if (adminId != null && adminId.trim().equals(String.valueOf(targetUserId))) {
            throw new IllegalArgumentException("不能修改自己的角色（避免自降级失权或自我提权）");
        }
        User target = userRepository.selectById(targetUserId);
        if (target == null) {
            throw new PostNotFoundException("用户不存在");
        }
        AdminRole from = AdminRole.fromCode(target.getRole());
        AdminRole to = AdminRole.fromCode(roleCode);
        if (from == to) {
            throw new IllegalArgumentException("该用户已经是「" + to.label() + "」，无需重复分配");
        }
        // 最后一个超管保护：把超管降级前，确认平台还有其他超管兜底
        boolean fromSuper = from == AdminRole.SUPER_ADMIN || from == AdminRole.ADMIN;
        boolean toSuper = to == AdminRole.SUPER_ADMIN;
        if (fromSuper && !toSuper && countSuperAdmins() <= 1) {
            throw new IllegalArgumentException("平台至少需要保留一名超级管理员，请先把其他账号设为超管");
        }
        userRepository.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, targetUserId)
                .set(User::getRole, to.name()));
        auditService.record(adminId, AuditLog.CAT_ADMIN, "user_role_assigned", "user",
                String.valueOf(targetUserId),
                AuditService.detailOf("username", target.getUsername(),
                        "from_role", from.name(), "to_role", to.name(),
                        "reason", reason.trim()));
        log.info("角色分配: user={}({}) {} → {} by={} reason={}", target.getUsername(),
                targetUserId, from.name(), to.name(), adminId, reason);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("note", "已将 " + target.getUsername() + " 的角色设为「" + to.label() + "」");
        r.put("role", to.name());
        r.put("role_label", to.label());
        return r;
    }

    /** 超管数量（含历史值 ADMIN）—— 最后一个超管不可被降级 */
    private long countSuperAdmins() {
        return userRepository.selectCount(new LambdaQueryWrapper<User>()
                .in(User::getRole, List.of(AdminRole.SUPER_ADMIN.name(), AdminRole.ADMIN.name())));
    }

    private Map<String, Object> toView(User u, long postCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(u.getId()));
        m.put("username", u.getUsername());
        m.put("nickname", u.getNickname());
        m.put("role", u.getRole() == null || u.getRole().isBlank()
                ? CommunityUserService.ROLE_USER : u.getRole());
        // 角色中文名由服务端下发（前端不再维护第二份翻译表，避免两侧角色名不一致）
        m.put("role_label", AdminRole.fromCode(u.getRole()).label());
        m.put("account_status", u.getAccountStatus() == null || u.getAccountStatus().isBlank()
                ? User.STATUS_ACTIVE : u.getAccountStatus());
        m.put("post_limited", Boolean.TRUE.equals(u.getPostLimited()));
        m.put("comment_banned", Boolean.TRUE.equals(u.getCommentBanned()));
        m.put("violation_count", u.getViolationCount() == null ? 0 : u.getViolationCount());
        m.put("post_count", postCount);
        m.put("last_login_at", u.getLastLoginAt() == null ? null
                : u.getLastLoginAt().toString().replace("T", " "));
        m.put("created_at", u.getCreatedAt() == null ? null
                : u.getCreatedAt().toString().replace("T", " "));
        return m;
    }
}
