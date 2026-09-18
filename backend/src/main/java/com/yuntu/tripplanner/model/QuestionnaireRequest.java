package com.yuntu.tripplanner.model;

import lombok.Data;

import java.util.List;

/**
 * 偏好问卷提交参数（个性化阶段一：首次偏好采集）。
 *
 * <p>撤销契约（PERSONALIZATION_PLAN §5.4）：字段<b>缺失</b> = 本次不涉及该域（保持现状，
 * 不覆盖历史推断结果）；列表传<b>空数组</b>、单值传<b>空串</b> = 用户主动清空该域
 * ——明细与主档会一起清掉，用户「取消勾选之前选的偏好」能真正生效。
 *
 * <p>⚠️ 列表字段<b>不能</b>给默认值 `new ArrayList<>()`：那样「字段缺失」与「传空数组」
 * 在 Java 侧都变成空列表，二者不可区分，撤销语义会被误伤（漏传的域被当成"要清空"）。
 */
@Data
public class QuestionnaireRequest {

    /** 旅行风格多选（自然风景/历史文化/美食探索/城市漫游/拍照打卡/亲子活动/夜生活/购物/户外运动）；null = 不涉及 */
    private List<String> travelStyles;

    /** 节奏偏好（轻松/适中/紧凑）；null = 不涉及，空串 = 清空 */
    private String pace;

    /** 住宿偏好（经济型/舒适型/高档型）；null = 不涉及，空串 = 清空 */
    private String hotelLevel;

    /** 口味偏好多选（火锅/海鲜/本帮菜/…）；null = 不涉及 */
    private List<String> foodPreferences;

    /** 饮食约束多选（少辣/不吃香菜/不吃葱/…）；null = 不涉及 */
    private List<String> dietaryRestrictions;

    /** 行为约束多选（不早起/少换酒店/少走路/优先公共交通/避开人多的景点/适合老人或儿童）；null = 不涉及 */
    private List<String> behaviorNotes;
}
