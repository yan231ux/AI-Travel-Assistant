package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户景点收藏（对应 user_spot_favorite 表，产品化阶段一）。
 *
 * <p>与 user_behavior 分工：本表只回答「用户收藏了哪些景点」——幂等唯一键
 * (user_id, spot_id) 防重复收藏、取消收藏、/favorites 我的收藏页与卡片 isCollected 状态；
 * SAVE/DISLIKE 行为仍写 user_behavior 供画像权重更新与实验指标统计（两表职责不重叠）。
 *
 * <p>name/city/imageUrl 为收藏时快照，列表展示免联表查询。
 *
 * <p>序列化对齐：本类被 GET /user/spot-favorites 直接当 DTO 返回，字段统一 snake_case
 * （与其余 API 契约一致）；数据库列名由 @TableField 负责，互不影响。
 */
@Data
@TableName("user_spot_favorite")
public class SpotFavorite {

    @TableId(type = IdType.AUTO)
    @JsonProperty("id")
    private Long id;

    @TableField("user_id")
    @JsonProperty("user_id")
    private String userId;

    /** 系统景点 ID（spot_城市_poiId） */
    @TableField("spot_id")
    @JsonProperty("spot_id")
    private String spotId;

    /** 高德 POI ID */
    @TableField("poi_id")
    @JsonProperty("poi_id")
    private String poiId;

    /** 景点名称快照 */
    @TableField("name")
    @JsonProperty("name")
    private String name;

    /** 景点城市 */
    @TableField("city")
    @JsonProperty("city")
    private String city;

    /** 图片 URL 快照 */
    @TableField("image_url")
    @JsonProperty("image_url")
    private String imageUrl;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}
