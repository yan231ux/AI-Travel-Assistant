package com.yuntu.tripplanner.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 行为反馈上报参数（个性化阶段二）。
 *
 * <p>前端在结果页对景点执行 收藏/不感兴趣、或对整体行程评分时上报。
 * 服务端以登录态 userId 落库并驱动画像增量更新；tripId/itemId/itemName 仅留痕。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BehaviorRequest {

    /** 对象类型：SPOT/RESTAURANT/TRIP */
    private String itemType;

    /** 对象 ID（景点高德 POI id 或行程 trip_id），可空 */
    private String itemId;

    /** 对象名称（景点/餐厅名），留痕用 */
    private String itemName;

    /** 景点高德业态类型（透传，用于映射旅行风格标签） */
    private String poiType;

    /** 行为类型：SAVE/DISLIKE/REPLACE/RATE 等 */
    private String actionType;

    /** RATE 行为时的整体评分 1~5 */
    private Integer rating;

    /** RATE 且低分时用户点选的"不满意方面"：pace/food/hotel/travel_style */
    private List<String> aspects = new ArrayList<>();

    /** 行程 ID（行为发生的行程） */
    private String tripId;

    /* ================= 负反馈粒度（PLAN §2.2 问题三：只有"这类/标签"级负反馈才降画像权重） ================= */
    /** 原因：不喜欢这个具体地点（仅留痕，不泛化到标签） */
    public static final String REASON_ITEM = "ITEM";
    /** 原因：不喜欢这类地点（默认；降低该地点命中的全部标签权重） */
    public static final String REASON_TYPE = "TYPE";
    /** 原因：不喜欢这个主题标签（只降低 req.tags 指定的标签） */
    public static final String REASON_TAG = "TAG";
    /** 原因：距离太远（上下文类，仅留痕不降权重） */
    public static final String REASON_DISTANCE = "DISTANCE";
    /** 原因：太拥挤（上下文类，仅留痕不降权重） */
    public static final String REASON_CROWDED = "CROWDED";
    /** 原因：价格太高（上下文类，仅留痕不降权重） */
    public static final String REASON_PRICE = "PRICE";
    /** 原因：不适合当前节奏（上下文类，仅留痕不降权重） */
    public static final String REASON_PACE = "PACE";

    /** DISLIKE/REPLACE 时的负反馈原因（null = 旧版客户端，等价 REASON_TYPE，保持向后兼容） */
    private String reason;

    /** REASON_TAG 时用户点选的具体回避标签（如 ["购物"]；空则不降权重） */
    private List<String> tags = new ArrayList<>();
}
