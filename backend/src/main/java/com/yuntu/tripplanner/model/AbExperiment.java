package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A/B 实验注册表（对应 ab_experiment 表，阶段四任务 6）。
 *
 * <p>实验 = 「作用于某条推荐流的一版可对照策略」：feed_type 指明作用域
 * （SPOT_FEED=推荐景点流 / POST_FEED=社区推荐流），strategy 说明处理组行为
 * （QUALITY_GATE=只保留有真实攻略的候选 / LOW_QUALITY_FILTER=过滤低质帖子），
 * traffic_percent 控制参与流量、control_percent 控制对照组占比。
 * 只允许一个 feed_type 的 ACTIVE 实验同时存在（并发实验会相互污染口径）；
 * 关闭后不得再打开，需新建实验（换参数 = 换哈希盐，防止中途换桶）。
 */
@Data
@TableName("ab_experiment")
public class AbExperiment {

    /** 状态：进行中（只有它参与分桶） */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 状态：已关闭（停止分桶与策略生效） */
    public static final String STATUS_CLOSED = "CLOSED";

    /** 作用域：推荐景点流 */
    public static final String FEED_SPOT = "SPOT_FEED";
    /** 作用域：帖子推荐流 */
    public static final String FEED_POST = "POST_FEED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 实验名（URL/代码引用键，小写字母数字下划线，唯一） */
    @TableField("exp_name")
    private String expName;

    @TableField("description")
    private String description;

    /** 作用域：SPOT_FEED / POST_FEED */
    @TableField("feed_type")
    private String feedType;

    /** 处理组策略：QUALITY_GATE / LOW_QUALITY_FILTER */
    @TableField("strategy")
    private String strategy;

    /** ACTIVE / CLOSED */
    @TableField("status")
    private String status;

    /** 参与流量 1~100 */
    @TableField("traffic_percent")
    private Integer trafficPercent;

    /** 对照组占参与流量比例 0~100 */
    @TableField("control_percent")
    private Integer controlPercent;

    @TableField(value = "started_at", fill = FieldFill.INSERT)
    private LocalDateTime startedAt;

    @TableField("closed_at")
    private LocalDateTime closedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
