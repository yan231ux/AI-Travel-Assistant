package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 景点主档（对应 spot 表，产品化阶段一：推荐景点列表/详情的数据底座）。
 *
 * <p>来源=高德按需同步（覆盖规模）+ RAG 攻略卡片回填（可信简介/标签增强）；
 * {@code spot_id} 为系统内部稳定 ID（如 spot_{city}_{poiId}），{@code poi_id} 为高德来源 ID，
 * 名称仅做兼容与兜底匹配 —— 防止同名/别名把收藏、帖子与推荐关联错（PRODUCT_EVOLUTION_PLAN §8.6）。
 *
 * <p>data_quality 三档：POI_ONLY（仅高德，无攻略命中）/ GUIDE_MATCHED（命中本地攻略卡片）/
 * VERIFIED（攻略+校验通过）。详情页据此区分「真实攻略」与「POI-only 诚实兜底」，不编造简介。
 */
@Data
@TableName("spot")
public class Spot {

    /** 数据来源：仅高德 */
    public static final String SOURCE_AMAP = "AMAP";
    /** 数据来源：仅 RAG 攻略卡片 */
    public static final String SOURCE_RAG = "RAG";
    /** 数据来源：高德 + RAG 攻略增强 */
    public static final String SOURCE_AMAP_AND_RAG = "AMAP_AND_RAG";

    /** 可信度：仅高德 POI 数据（无攻略命中，简介诚实兜底） */
    public static final String QUALITY_POI_ONLY = "POI_ONLY";
    /** 可信度：命中本地攻略卡片（简介/标签来自人工攻略） */
    public static final String QUALITY_GUIDE_MATCHED = "GUIDE_MATCHED";
    /** 可信度：攻略命中且通过校验 */
    public static final String QUALITY_VERIFIED = "VERIFIED";

    /** 上架状态：正常对外推荐/展示 */
    public static final String STATUS_ONLINE = "ONLINE";
    /** 上架状态：管理员下线（不推荐不展示；原因见 flag/flag_reason） */
    public static final String STATUS_OFFLINE = "OFFLINE";

    /** 治理标记：非景点（酒店/商场等被误收为景点） */
    public static final String FLAG_NON_SPOT = "NON_SPOT";
    /** 治理标记：已关闭/暂停营业 */
    public static final String FLAG_CLOSED = "CLOSED";
    /** 治理标记：过时（信息陈旧，需重新同步核验） */
    public static final String FLAG_OUTDATED = "OUTDATED";
    /** 治理标记：错误 POI（高德数据本身错位/不存在） */
    public static final String FLAG_ERROR_POI = "ERROR_POI";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 系统景点稳定 ID（如 spot_上海_B00155FXB3） */
    @TableField("spot_id")
    private String spotId;

    /** 高德 POI ID（来源键；允许为空但不能替代唯一键） */
    @TableField("poi_id")
    private String poiId;

    @TableField("name")
    private String name;

    /** 标准化名称（去城市后缀/空白，名称匹配兜底用） */
    @TableField("normalized_name")
    private String normalizedName;

    @TableField("city")
    private String city;

    @TableField("address")
    private String address;

    @TableField("longitude")
    private Double longitude;

    @TableField("latitude")
    private Double latitude;

    /** 高德业态类型（风景名胜/…） */
    @TableField("category")
    private String category;

    /** 图片 URL（高德，仅作展示增强，不标为官方图） */
    @TableField("image_url")
    private String imageUrl;

    /** 简介（RAG 攻略卡片优先回填；无命中则诚实兜底文案） */
    @TableField("description")
    private String description;

    /** 标签（逗号分隔：自然风景/历史文化/…） */
    @TableField("tags")
    private String tags;

    /** AMAP / RAG / AMAP_AND_RAG */
    @TableField("source")
    private String source;

    /** POI_ONLY / GUIDE_MATCHED / VERIFIED */
    @TableField("data_quality")
    private String dataQuality;

    /** 最近一次从高德同步时间（按需同步的缓存新鲜度依据） */
    @TableField("last_synced_at")
    private LocalDateTime lastSyncedAt;

    /** 上下架状态：ONLINE/OFFLINE（治理；OFFLINE=管理员下线，不推荐不展示） */
    @TableField("status")
    private String status;

    /** 治理标记：NON_SPOT/CLOSED/OUTDATED/ERROR_POI（空=正常） */
    @TableField("flag")
    private String flag;

    /** 治理原因（管理员填写） */
    @TableField("flag_reason")
    private String flagReason;

    /** 是否存在人工修正（同步时跳过被锁定字段） */
    @TableField("manual_override")
    private Boolean manualOverride;

    /** 人工锁定字段（逗号分隔：name,address,description,tags…，同步只更新未锁定字段） */
    @TableField("manual_override_fields")
    private String manualOverrideFields;

    /** 最近人工审核人（用户名） */
    @TableField("last_verified_by")
    private String lastVerifiedBy;

    /** 最近人工审核时间 */
    @TableField("last_verified_at")
    private LocalDateTime lastVerifiedAt;

    /** 重复合并目标 spot_id（本行作为下线别名指向主行） */
    @TableField("merged_into")
    private String mergedInto;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
