package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 全链路审计日志（对应 audit_log 表，阶段四任务 10）。
 *
 * <p>记录系统内"值得事后追溯"的操作：注册/登录、行程保存与删除、内容发布与审核、
 * 举报处理、隐藏下架、关注/取关、画像问卷更新、A/B 实验创建/关闭等。
 * 只记动作本身（谁/何时/对什么/附加信息），不存敏感凭据与高吞吐行为
 * （点赞/浏览等互动不进审计，避免表膨胀）。写入全部 fail-soft：审计失败不影响主流程。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    /** 审计分类：用户与鉴权 */
    public static final String CAT_USER = "USER";
    /** 审计分类：行程 */
    public static final String CAT_TRIP = "TRIP";
    /** 审计分类：内容（帖子/评论） */
    public static final String CAT_CONTENT = "CONTENT";
    /** 审计分类：审核与治理（管理员动作） */
    public static final String CAT_ADMIN = "ADMIN";
    /** 审计分类：社交（关注关系） */
    public static final String CAT_SOCIAL = "SOCIAL";
    /** 审计分类：画像 */
    public static final String CAT_PROFILE = "PROFILE";
    /** 审计分类：运营（A/B 实验/推荐策略） */
    public static final String CAT_OPS = "OPS";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作者用户 ID；系统/匿名动作为空（如登录失败） */
    @TableField("actor_id")
    private String actorId;

    /** 分类：USER/TRIP/CONTENT/ADMIN/SOCIAL/PROFILE/OPS */
    @TableField("category")
    private String category;

    /** 动作（英文小写下划线，如 post_approved） */
    @TableField("action")
    private String action;

    /** 对象类型（post/comment/report/trip/experiment/user…） */
    @TableField("target_type")
    private String targetType;

    /** 对象 ID（帖子/举报/行程/实验名等） */
    @TableField("target_id")
    private String targetId;

    /** 附加上下文 JSON（如拒绝原因、举报处理动作、问卷来源），≤1000 截断 */
    @TableField("detail")
    private String detail;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
