package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 攻略标签（对应 city_guide_tag 表，内容运营骨架）。
 *
 * <p>与帖子标签同词表（travel_style 等画像标签域），用于后台展示「攻略推导标签数」，
 * 供后续城市专题/推荐流按攻略标签做内容组织；当前为骨架表，写入随解析链路一并接入。
 */
@Data
@TableName("city_guide_tag")
public class CityGuideTag {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("guide_id")
    private Long guideId;

    @TableField("revision_id")
    private Long revisionId;

    @TableField("category")
    private String category;

    @TableField("tag")
    private String tag;

    @TableField("source")
    private String source;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
