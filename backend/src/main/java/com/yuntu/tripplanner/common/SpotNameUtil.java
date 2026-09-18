package com.yuntu.tripplanner.common;

/**
 * 景点名称标准化（纯静态工具，匹配用）。
 *
 * <p>同一套口径被两处消费，保证「攻略解析出的景点名」与「spot 主档 normalized_name」
 * 可比对：1) {@code RecommendationFeedService} 写 spot 表时落库 normalized_name；
 * 2) 攻略后台把 Markdown 解析出的景点名匹配到 spot 主档（管理员后台与内容运营中心
 * 设计方案 §4.4「识别 N 个景点 / M 已匹配 spot 表」）。
 *
 * <p>规则：去空白/全半角括号，剥尾部行政区划通名（风景区/景区/公园/古镇/老街/景点）。
 */
public final class SpotNameUtil {

    private SpotNameUtil() {
    }

    /** 名称规范化（仅匹配用）：去空白/全半角括号/行政区划后缀 */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("[\\s\\u3000（）()]", "")
                .replaceAll("(风景区|景区|公园|古镇|老街|景点)$", "");
    }
}
