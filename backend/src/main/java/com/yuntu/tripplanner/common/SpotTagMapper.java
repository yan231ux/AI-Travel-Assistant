package com.yuntu.tripplanner.common;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POI → 偏好标签 确定性映射（个性化阶段二：行为反馈的标签信号源）。
 *
 * <p>收藏/不感兴趣等反馈作用于「对象」，而画像/打分作用在「标签」上，中间需要一次
 * 可解释的映射。规则零 LLM、纯词典：
 * <ul>
 *   <li>{@link #styleTags(String, String)}：高德业态 type（分号分段）优先，名称关键词兜底，
 *       产出旅行风格标签（对齐问卷选项：自然风景/历史文化/美食探索/城市漫游/亲子活动/户外运动/购物/夜生活）；</li>
 *   <li>{@link #foodTags(String)}：餐厅名称命中口味词典 → 口味标签（category=food）。</li>
 * </ul>
 * 不匹配（如普通商铺/住宅）返回空集 —— 负反馈只针对可解释的偏好域，避免噪声。
 */
public final class SpotTagMapper {

    /** 风格 → 触发关键词（命中高德 type 任一分段即打该风格） */
    private static final Map<String, List<String>> TYPE_RULES = Map.of(
            "自然风景", List.of("风景名胜", "自然保护区", "国家公园", "森林公园", "植物园",
                    "湖泊", "湿地", "瀑布", "温泉", "海滨", "岛屿", "地质", "山岳", "观光"),
            "历史文化", List.of("科教文化", "博物馆", "纪念馆", "文物", "遗址", "故居",
                    "寺庙", "道观", "教堂", "古建筑", "文化"),
            "美食探索", List.of("餐饮服务", "美食", "小吃", "夜市", "餐厅"),
            "城市漫游", List.of("特色商业街", "步行街", "商业街", "广场", "观景台", "旅游街区"),
            "亲子活动", List.of("儿童", "乐园", "亲子", "动物园"),
            "户外运动", List.of("运动场馆", "户外", "滑雪", "登山", "球场", "徒步"),
            "购物", List.of("购物服务", "商场", "免税"),
            "夜生活", List.of("酒吧", "夜总会", "歌舞", "演出"));

    /** 名称兜底关键词 → 风格（type 未命中时用；"湖/山/岛"等单字场景词易误伤地名，仅作末位兜底） */
    private static final Map<String, List<String>> NAME_RULES = Map.of(
            "自然风景", List.of("湖", "山", "岛", "湾", "海", "瀑布", "草原", "森林公园", "湿地", "峡谷", "峰"),
            "历史文化", List.of("寺", "庙", "祠", "宫", "塔", "故居", "博物馆", "遗址", "古镇", "古城", "老街", "坊", "巷", "陵"),
            "美食探索", List.of("美食街", "小吃", "夜市", "食府", "菜馆", "味道"),
            "亲子活动", List.of("游乐园", "乐园", "动物园", "海洋馆", "主题公园"),
            "户外运动", List.of("滑雪场", "徒步", "骑行", "户外"));

    /** 口味关键词（对齐 UserProfileService.DIET_KEYWORDS，餐厅名命中即口味标签） */
    private static final List<String> FOOD_KEYWORDS = List.of(
            "火锅", "海鲜", "本帮菜", "川菜", "粤菜", "小吃", "烧烤", "面食", "甜品",
            "咖啡", "辣", "清淡", "烤肉", "日料", "泰餐");

    private SpotTagMapper() {
    }

    /**
     * 景点 → 旅行风格标签（≤2，去重保序）。
     * 优先高德 type 分段落规则；type 为空/不中时用名称关键词兜底。
     */
    public static List<String> styleTags(String poiType, String name) {
        Set<String> tags = new LinkedHashSet<>();
        if (poiType != null && !poiType.isBlank()) {
            for (String segment : poiType.split(";")) {
                matchSegment(tags, segment.trim(), TYPE_RULES);
                if (tags.size() >= 2) {
                    return List.copyOf(tags);
                }
            }
        }
        if (tags.isEmpty() && name != null && !name.isBlank()) {
            matchSegment(tags, name, NAME_RULES);
        }
        return List.copyOf(tags);
    }

    /** 餐厅名称 → 口味标签（≤2，按名称出现顺序） */
    public static List<String> foodTags(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        Set<String> tags = new LinkedHashSet<>();
        for (String kw : FOOD_KEYWORDS) {
            if (name.contains(kw)) {
                tags.add(kw);
                if (tags.size() >= 2) {
                    break;
                }
            }
        }
        return List.copyOf(tags);
    }

    /** 规则命中：对一段文本逐个 keyword 探测并追加 tag */
    private static void matchSegment(Set<String> out, String text, Map<String, List<String>> rules) {
        for (Map.Entry<String, List<String>> e : rules.entrySet()) {
            if (out.contains(e.getKey())) {
                continue;
            }
            for (String kw : e.getValue()) {
                if (text.contains(kw)) {
                    out.add(e.getKey());
                    break;
                }
            }
        }
    }

    /** 供调试/测试的完整规则规模描述（不参与业务） */
    static int typeRuleCount() {
        return TYPE_RULES.values().stream().mapToInt(List::size).sum();
    }
}
