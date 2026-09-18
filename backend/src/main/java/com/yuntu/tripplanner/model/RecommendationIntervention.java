package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 推荐人工干预（对应 recommendation_intervention 表，设计方案 §6.3）。
 *
 * <p>运营在推荐链路上的低风险动作登记：不做"手填最终分"，只提供
 * PIN(置顶)/DEMOTE(降权)/BLACKLIST(推荐黑名单)/FEATURED(城市精选) 四类，
 * 全部带原因 + 生效窗口 + 审计。干预与算法分数严格分离：
 * 不写入任何 score/quality 字段，只在推荐流排序层消费（黑名单剔除、置顶优先、降权沉底），
 * 载荷以独立 interventions meta 输出，避免运营规则与算法分数混在一起。
 * (target_type, target_id, action) 唯一 → 重复保存 = 覆盖窗口/原因（不留多行历史）。
 */
@Data
@TableName("recommendation_intervention")
public class RecommendationIntervention {

    /** 对象类型：景点（PIN/DEMOTE/BLACKLIST） */
    public static final String TARGET_SPOT = "SPOT";
    /** 对象类型：城市（FEATURED） */
    public static final String TARGET_CITY = "CITY";

    /** 置顶：该景点在其所属城市推荐流中排到最前（稳定，不动算法分） */
    public static final String ACTION_PIN = "PIN";
    /** 降权：该景点在其所属城市推荐流中沉到末尾（稳定） */
    public static final String ACTION_DEMOTE = "DEMOTE";
    /** 推荐黑名单：该景点从推荐候选池剔除（不再出现于任何推荐 Tab） */
    public static final String ACTION_BLACKLIST = "BLACKLIST";
    /** 城市精选：标记城市为精选（流载荷 featured_city 标识，运营标记类） */
    public static final String ACTION_FEATURED = "FEATURED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** SPOT / CITY */
    @TableField("target_type")
    @JsonProperty("target_type")
    private String targetType;

    /** SPOT=spot_id；CITY=城市名（如 北京） */
    @TableField("target_id")
    @JsonProperty("target_id")
    private String targetId;

    /** PIN / DEMOTE / BLACKLIST / FEATURED */
    @TableField("action")
    private String action;

    @TableField("reason")
    private String reason;

    @TableField("effective_from")
    @JsonProperty("effective_from")
    private LocalDateTime effectiveFrom;

    @TableField("effective_until")
    @JsonProperty("effective_until")
    private LocalDateTime effectiveUntil;

    @TableField("created_by")
    @JsonProperty("created_by")
    private String createdBy;

    @TableField("created_at")
    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;
}
