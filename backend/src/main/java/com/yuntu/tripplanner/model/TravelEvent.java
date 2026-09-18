package com.yuntu.tripplanner.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 通用行程事件（阶段二数据地基，设计方案 §7.1）。
 *
 * <p>产品分析专用事件流：与 {@link UserBehavior}（画像反馈）分工——
 * 画像行为表继续驱动个性化权重，统计分析（采用率/保存率/收藏热度/城市热度）
 * 一律走本事件表，避免把画像表强行当产品分析表。
 *
 * <p>去重口径（§7.2）：UNIQUE(user_id, trip_id, item_id, event_type) ——
 * 同一用户同一行程的同一事件天然只记一次，"重复刷新结果页"不会重复统计；
 * 次数 / 用户数（DISTINCT user_id）/ 行程数（DISTINCT trip_id）在聚合层区分。
 * trip_id 为空（如收藏）统一存 ''，保证唯一键有效（MySQL NULL 不参与唯一约束）。
 *
 * <p>item_id 口径统一（spot_id → poi_id → 名称兜底）：优先系统稳定 spot_id，
 * 其次高德 poi_id，最后 name: 前缀的名称兜底 —— 跨来源（事件/收藏/景点主档）可关联。
 */
@Data
@TableName("travel_event")
public class TravelEvent {

    /* ---- 事件类型 ---- */
    /** 行程生成完成（trip 维度，item_id = trip_id） */
    public static final String TYPE_TRIP_GENERATED = "TRIP_GENERATED";
    /** 行程保存成功 */
    public static final String TYPE_TRIP_SAVED = "TRIP_SAVED";
    /** 景点进入生成行程（规划采用） */
    public static final String TYPE_SPOT_GENERATED = "SPOT_GENERATED";
    /** 景点随行程保存落库 */
    public static final String TYPE_SPOT_SAVED = "SPOT_SAVED";
    /** 景点被收藏 */
    public static final String TYPE_SPOT_FAVORITED = "SPOT_FAVORITED";
    /** 景点被手动加入行程（预留：结果页"加入行程"功能接入后启用） */
    public static final String TYPE_SPOT_ADD_TO_PLAN = "SPOT_ADD_TO_PLAN";

    /* ---- 对象类型 ---- */
    public static final String ITEM_TRIP = "TRIP";
    public static final String ITEM_SPOT = "SPOT";

    /* ---- 来源 ---- */
    public static final String SOURCE_AGENT = "AGENT";
    public static final String SOURCE_SAVE = "SAVE";
    public static final String SOURCE_FAVORITE = "FAVORITE";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;

    /** 页面会话 id（前端透传，可空；会话数统计用） */
    private String sessionId;

    private String eventType;

    private String itemType;

    private String itemId;

    private String itemName;

    private String city;

    /** 空统一存 ''（唯一键参与判重，见类注释） */
    private String tripId;

    private String source;

    private String metadataJson;

    /** 统计日期（按天预聚合的分区口径，= created_at 的日期部分） */
    private LocalDate statDate;

    private LocalDateTime createdAt;
}
