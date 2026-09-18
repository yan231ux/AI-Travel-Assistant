package com.yuntu.tripplanner.common;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 管理端角色与权限矩阵（设计方案 §11 第五阶段：USER/CONTENT_REVIEWER/CITY_EDITOR/
 * RECOMMENDATION_OPERATOR/SUPER_ADMIN + 权限点控制）。
 *
 * <p><b>角色职责边界</b>（按"管什么"划分，与左侧导航对应）：
 * <ul>
 *   <li>{@link #CONTENT_REVIEWER} 内容审核员：管<b>用户发的和 AI 初筛的内容</b>
 *       —— 帖子审核、举报处理、帖子上下架；</li>
 *   <li>{@link #CITY_EDITOR} 城市内容编辑：管<b>城市攻略内容</b> —— 攻略增删改审发布下线、城市专题；</li>
 *   <li>{@link #RECOMMENDATION_OPERATOR} 推荐运营：管<b>怎么推</b> —— A/B 实验、人工干预、推荐质量；</li>
 *   <li>{@link #SUPER_ADMIN} 超级管理员：管<b>底数和人</b> —— 景点数据治理、用户与账号治理、审计，
 *       并且天然拥有上面全部权限。</li>
 * </ul>
 *
 * <p><b>为什么景点治理只给 SUPER_ADMIN</b>：景点主档是所有城市推荐/攻略/行程的公共底座，
 * 下线与合并（merged_into 重定向）不可逆且影响面跨城市；城市内容编辑负责的是"内容"，
 * 底座数据变更收口给超管，边界更清楚也更安全。
 *
 * <p><b>兼容旧值</b>：存量库 role 只有 USER/ADMIN 两种。{@link #ADMIN} 是历史值，
 * 权限等同 SUPER_ADMIN；启动期 {@code SchemaAutoUpgrade} 会把 role='ADMIN' 迁成 'SUPER_ADMIN'，
 * 但代码这里仍保留映射 —— 迁移失败或漏迁移时绝不能把管理员变成无权限的人。
 */
public enum AdminRole {

    /** 普通用户：无任何管理端权限 */
    USER("普通用户", EnumSet.noneOf(AdminPermission.class)),

    /** 内容审核员：帖子审核 + 举报处理 + 帖子治理，能看到审核漏斗看板 */
    CONTENT_REVIEWER("内容审核员", EnumSet.of(
            AdminPermission.CONTENT_REVIEW,
            AdminPermission.ANALYTICS_VIEW)),

    /** 城市内容编辑：攻略与城市专题内容的运营闭环（数据看板只读，一并开放） */
    CITY_EDITOR("城市内容编辑", EnumSet.of(
            AdminPermission.GUIDE_MANAGE,
            AdminPermission.ANALYTICS_VIEW)),

    /** 推荐运营：实验、干预、推荐质量与数据看板 */
    RECOMMENDATION_OPERATOR("推荐运营", EnumSet.of(
            AdminPermission.RECOMMEND_OPS,
            AdminPermission.ANALYTICS_VIEW)),

    /** 超级管理员：全部权限 */
    SUPER_ADMIN("超级管理员", EnumSet.allOf(AdminPermission.class)),

    /** 历史值 ADMIN（迁移前存量数据）：权限等同超级管理员，避免迁移差异导致管理员失权 */
    ADMIN("管理员（历史值）", EnumSet.allOf(AdminPermission.class));

    private final String label;
    private final Set<AdminPermission> permissions;

    AdminRole(String label, Set<AdminPermission> permissions) {
        this.label = label;
        this.permissions = Collections.unmodifiableSet(permissions);
    }

    public String label() {
        return label;
    }

    /** 该角色拥有的权限点（只读） */
    public Set<AdminPermission> permissions() {
        return permissions;
    }

    /** 是否属于管理端（能进 /admin）：USER 以外的都算，含兼容值 ADMIN */
    public boolean isAdminSide() {
        return this != USER;
    }

    public boolean has(AdminPermission permission) {
        return permission != null && permissions.contains(permission);
    }

    /**
     * 角色码解析（大小写不敏感、去空白）。
     *
     * <p><b>未知值一律按 USER 处理</b>：这是 fail-safe —— 数据库里出现拼错的角色名时，
     * 结果必须是"这个人没有权限"而不是"这个人有权限"，绝不能靠猜。
     */
    public static AdminRole fromCode(String code) {
        if (code == null || code.isBlank()) {
            return USER;
        }
        String normalized = code.trim().toUpperCase();
        for (AdminRole r : values()) {
            if (r.name().equals(normalized)) {
                return r;
            }
        }
        return USER;
    }

    /** 是否是可被分配的合法角色码（分配接口校验用；不含历史值 ADMIN） */
    public static boolean isAssignable(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        AdminRole r = fromCode(code);
        return r != ADMIN && r.name().equals(code.trim().toUpperCase());
    }
}
