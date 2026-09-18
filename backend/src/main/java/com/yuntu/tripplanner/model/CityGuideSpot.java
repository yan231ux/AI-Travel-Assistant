package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 攻略-景点关联（对应 city_guide_spot 表，内容运营骨架）。
 *
 * <p>当前为骨架表：攻略导入/保存时写入从 Markdown 结构解析出的景点名清单（spot_name + 序号），
 * 用于管理后台展示「攻略覆盖景点数」，并作为后续接入景点主档匹配（spot_id/poi_id 关联）的基础。
 */
@Data
@TableName("city_guide_spot")
public class CityGuideSpot {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("guide_id")
    private Long guideId;

    @TableField("revision_id")
    private Long revisionId;

    @TableField("spot_name")
    private String spotName;

    @TableField("spot_id")
    private String spotId;

    @TableField("poi_id")
    private String poiId;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
