package com.yuntu.tripplanner.model;

import lombok.Data;

/**
 * 一次行为反馈对画像权重产生的实际调整（个性化阶段二）。
 *
 * <p>由 {@code UserProfileService.recordBehavior} 返回，前端据此刻画"你的反馈改变了什么"：
 * 收藏/点击提升对应标签权重、不感兴趣/替换压低权重（负反馈行 → 后续生成提示"避免同类"）。
 * delta 为 clamp 后实际生效的变化量；问卷显式偏好行受负反馈保护时 delta=0 且 protected=true。
 */
@Data
public class PreferenceAdjustment {

    /** 偏好域：travel_style/pace/hotel/food/dietary/behavior */
    private String category;

    /** 偏好标签（自然风景/火锅/…） */
    private String tag;

    /** 本次实际生效的权重变化（clamp 后；受保护时为 0） */
    private double delta;

    /** 调整后的权重（0~1） */
    private double weight;

    /** 是否为本次新建的 FEEDBACK 来源行 */
    private boolean created;

    /** 问卷显式偏好受负反馈保护，本次未改动 */
    private boolean protectedRow;
}
