package com.yuntu.tripplanner.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 偏好问卷提交参数（个性化阶段一：首次偏好采集）。
 *
 * <p>所有字段均可选：仅提交用户明确选择的项，未提交的域保持现状（不覆盖历史推断结果）。
 * travelStyles 等列表字段为空数组 = 该域不修改；为「清空某个域」语义另由画像编辑接口处理。
 */
@Data
public class QuestionnaireRequest {

    /** 旅行风格多选（自然风景/历史文化/美食探索/城市漫游/拍照打卡/亲子活动/夜生活/购物/户外运动） */
    private List<String> travelStyles = new ArrayList<>();

    /** 节奏偏好（轻松/适中/紧凑），null 表示不修改 */
    private String pace;

    /** 住宿偏好（经济型/舒适型/高档型），null 表示不修改 */
    private String hotelLevel;

    /** 口味偏好多选（火锅/海鲜/本帮菜/…） */
    private List<String> foodPreferences = new ArrayList<>();

    /** 饮食约束多选（少辣/不吃香菜/不吃葱/…） */
    private List<String> dietaryRestrictions = new ArrayList<>();

    /** 行为约束多选（不早起/少换酒店/少走路/优先公共交通/避开人多的景点/适合老人或儿童） */
    private List<String> behaviorNotes = new ArrayList<>();
}
