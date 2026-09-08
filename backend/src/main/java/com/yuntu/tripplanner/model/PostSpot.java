package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 帖子关联景点（对应 post_spot 表，阶段二社区）。
 *
 * <p>结构化关联优先 spot_id/poi_id（PRODUCT_EVOLUTION_PLAN §8.6：名称只做展示快照与兜底），
 * 供详情页"关联景点"与阶段三帖子个性化（景点→帖子召回）使用。
 */
@Data
@TableName("post_spot")
public class PostSpot {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("post_id")
    private Long postId;

    @TableField("spot_id")
    private String spotId;

    @TableField("poi_id")
    private String poiId;

    @TableField("spot_name")
    private String spotName;

    @TableField("sort_order")
    private Integer sortOrder;
}
