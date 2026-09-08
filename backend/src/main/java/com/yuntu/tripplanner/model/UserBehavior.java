package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户行为记录（对应 user_behavior 表，个性化阶段二）。
 *
 * <p>景点/餐厅/行程级别的行为留痕：VIEW/CLICK/SAVE/DISLIKE/REPLACE/REGENERATE/RATE。
 * 行为既是画像增量更新的信号源（收藏 +0.15 / 不感兴趣 -0.30 / 替换 -0.20），
 * 也是阶段四实验指标（负反馈率、满意度均值）的统计口径。
 */
@Data
@TableName("user_behavior")
public class UserBehavior {

    /** 对象类型：景点 */
    public static final String ITEM_TYPE_SPOT = "SPOT";
    /** 对象类型：餐厅 */
    public static final String ITEM_TYPE_RESTAURANT = "RESTAURANT";
    /** 对象类型：帖子（社区内容，阶段三打通画像闭环） */
    public static final String ITEM_TYPE_POST = "POST";
    /** 对象类型：行程（整体评分） */
    public static final String ITEM_TYPE_TRIP = "TRIP";
    /** 对象类型：城市（阶段三统一行为模型预留，城市专题页后续使用） */
    public static final String ITEM_TYPE_CITY = "CITY";

    /** 行为：浏览 */
    public static final String ACTION_VIEW = "VIEW";
    /** 行为：点击/查看详情 */
    public static final String ACTION_CLICK = "CLICK";
    /** 行为：收藏（正反馈） */
    public static final String ACTION_SAVE = "SAVE";
    /** 行为：不感兴趣（负反馈） */
    public static final String ACTION_DISLIKE = "DISLIKE";
    /** 行为：替换掉（负反馈，本次不满意） */
    public static final String ACTION_REPLACE = "REPLACE";
    /** 行为：重新生成当天/整段 */
    public static final String ACTION_REGENERATE = "REGENERATE";
    /** 行为：整体评分 1~5 */
    public static final String ACTION_RATE = "RATE";
    /** 行为：点赞（弱正反馈，帖子互动进画像，阶段三） */
    public static final String ACTION_LIKE = "LIKE";
    /** 行为：分享（阶段三行为模型扩展，暂不驱动权重） */
    public static final String ACTION_SHARE = "SHARE";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId;

    @TableField("trip_id")
    private String tripId;

    /** SPOT/RESTAURANT/TRIP */
    @TableField("item_type")
    private String itemType;

    @TableField("item_id")
    private String itemId;

    @TableField("item_name")
    private String itemName;

    /** 景点高德业态类型（前端透传，供映射旅行风格标签） */
    @TableField("poi_type")
    private String poiType;

    /** VIEW/CLICK/SAVE/DISLIKE/REPLACE/REGENERATE/RATE */
    @TableField("action_type")
    private String actionType;

    /** RATE 行为时的评分 1~5 */
    @TableField("rating")
    private Integer rating;

    /** 附带上下文 JSON（如不满意的方面列表） */
    @TableField("aspect_json")
    private String aspectJson;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
