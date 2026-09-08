package com.yuntu.tripplanner.common;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 帖子 → 画像标签 确定性映射（产品化阶段三：内容个性化闭环的标签信号源）。
 *
 * <p>与 {@link SpotTagMapper} 同源思路：帖子没有天然标签，要让社区行为（收藏/点赞/不感兴趣）
 * 影响画像（进而影响景点推荐、帖子推荐与下一次行程生成），必须先把它映射到与景点同词表、
 * 与 user_preference.category/tag 对齐的偏好标签。规则零 LLM、纯词典：
 * <ul>
 *   <li>关联景点：每个 post_spot 通过 spot 表的高德业态/名称 → {@link SpotTagMapper#styleTags}
 *       （自然风景/历史文化/…，与景点行为完全同一词表，跨内容类型闭环由此打通）；</li>
 *   <li>正文关键词：标题/摘要/正文扫描多字词 → 旅行风格（CONTENT 来源）；</li>
 *   <li>节奏：帖子 pace 字段规范化（POST_FIELD），无则从正文关键词识别（CONTENT）；</li>
 *   <li>城市：帖子 city 字段原样入库（POST_FIELD，category=city）。</li>
 * </ul>
 * 输出经数量上限与去重约束，避免一篇帖子污染过多画像标签（风格 ≤5：关联景点优先）。
 */
public final class PostTagResolver {

    /** travel_style 风格标签总数上限（关联景点优先，正文扫描补充） */
    private static final int MAX_STYLE_TAGS = 5;

    /** 正文关键词 → 旅行风格（只收多字词，避免单字词误伤正文） */
    private static final Map<String, List<String>> CONTENT_STYLE_RULES = Map.of(
            "自然风景", List.of("瀑布", "草原", "峡谷", "雪山", "温泉", "森林公园", "海滨", "海岛", "星空", "日出", "日落", "露营"),
            "历史文化", List.of("古镇", "古城", "老街", "博物馆", "纪念馆", "故居", "遗址", "寺庙", "古建筑", "牌坊", "古村", "石窟", "书院"),
            "美食探索", List.of("美食街", "美食", "小吃", "夜市", "火锅", "菜馆", "食府", "甜品店", "咖啡馆"),
            "城市漫游", List.of("步行街", "商业街", "地标", "citywalk", "CityWalk"),
            "亲子活动", List.of("亲子", "乐园", "动物园", "海洋馆", "游乐园", "遛娃"),
            "户外运动", List.of("徒步", "骑行", "爬山", "登山", "滑雪", "攀岩", "漂流"),
            "购物", List.of("购物", "商场", "逛街", "免税"),
            "夜生活", List.of("酒吧", "演出", "夜游", "夜景"));

    /** 节奏关键词组（有序：优先判"轻松"组，避免"不赶路"里的单字"赶"误判为紧凑） */
    private static final Map<String, List<String>> PACE_RULES = new java.util.LinkedHashMap<>();

    static {
        PACE_RULES.put("轻松", List.of("轻松", "休闲", "悠闲", "慢慢", "慢节奏", "慵懒", "不早起"));
        PACE_RULES.put("紧凑", List.of("紧凑", "赶行程", "特种兵", "充实", "高效"));
        PACE_RULES.put("适中", List.of("适中", "标准"));
    }

    /** 标签来源常量（与 PostTag.SOURCE_* 对应） */
    public static final String SRC_SPOT_LINK = "SPOT_LINK";
    public static final String SRC_CONTENT = "CONTENT";
    public static final String SRC_POST_FIELD = "POST_FIELD";

    /** 帖子可引用的单个关联景点（category 为空时走名称兜底映射） */
    public record SpotRef(String category, String name) {
    }

    /** 解析出的一个标签（category/tag 与 user_preference 同词表） */
    public record Tag(String category, String tag, String source) {
    }

    private PostTagResolver() {
    }

    /**
     * 解析帖子标签。
     *
     * @param title       帖子标题（可空）
     * @param summary     摘要（可空）
     * @param content     正文（可空）
     * @param city        关联城市（可空，原样规范化后作为 city 域标签）
     * @param pace        节奏字段（可空，"轻松/适中/紧凑"或自然描述）
     * @param linkedSpots 帖子关联的景点（可空；内部按景点名称/高德业态映射风格标签）
     * @return 有序标签列表（去重；风格最多 5 个、节奏最多 1 个、城市最多 1 个）
     */
    public static List<Tag> resolve(String title, String summary, String content, String city,
                                    String pace, List<SpotRef> linkedSpots) {
        List<Tag> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String text = joinText(title, summary, content);

        // 1) 旅行风格：关联景点优先（与景点收藏同词表 → 跨内容类型可互相影响）
        List<String> styles = new ArrayList<>();
        if (linkedSpots != null) {
            for (SpotRef ref : linkedSpots) {
                if (styles.size() >= MAX_STYLE_TAGS) {
                    break;
                }
                String name = ref == null ? null : ref.name();
                String category = ref == null ? null : ref.category();
                if (name == null || name.isBlank()) {
                    continue;
                }
                for (String tag : SpotTagMapper.styleTags(category, name)) {
                    if (styles.size() < MAX_STYLE_TAGS && seen.add("s:" + tag)) {
                        styles.add(tag);
                    }
                }
            }
        }
        for (String tag : styles) {
            out.add(new Tag("travel_style", tag, SRC_SPOT_LINK));
        }
        // 2) 旅行风格：正文关键词补充（不够上限才补，避免正文泛词稀释）
        if (styles.size() < MAX_STYLE_TAGS && !text.isBlank()) {
            for (Map.Entry<String, List<String>> rule : CONTENT_STYLE_RULES.entrySet()) {
                if (styles.size() >= MAX_STYLE_TAGS) {
                    break;
                }
                if (seen.contains("s:" + rule.getKey())) {
                    continue;
                }
                for (String kw : rule.getValue()) {
                    if (text.contains(kw)) {
                        styles.add(rule.getKey());
                        seen.add("s:" + rule.getKey());
                        out.add(new Tag("travel_style", rule.getKey(), SRC_CONTENT));
                        break;
                    }
                }
            }
        }
        // 3) 节奏：pace 字段优先，正文关键词兜底（最多 1 个）
        String paceTag = null;
        String paceSource = null;
        if (isNotBlank(pace)) {
            paceTag = normalizePace(pace);
            paceSource = SRC_POST_FIELD;
        }
        if (paceTag == null && !text.isBlank()) {
            for (Map.Entry<String, List<String>> e : PACE_RULES.entrySet()) {
                for (String kw : e.getValue()) {
                    if (text.contains(kw)) {
                        paceTag = e.getKey();
                        paceSource = SRC_CONTENT;
                        break;
                    }
                }
                if (paceTag != null) {
                    break;
                }
            }
        }
        if (paceTag != null && seen.add("p:" + paceTag)) {
            out.add(new Tag("pace", paceTag, paceSource));
        }
        // 4) 城市：结构化字段（原样，仅去空白），城市标签不参与负向回避
        if (isNotBlank(city)) {
            String c = city.trim();
            if (c.length() > 30) {
                c = c.substring(0, 30);
            }
            if (!c.isEmpty() && seen.add("c:" + c)) {
                out.add(new Tag("city", c, SRC_POST_FIELD));
            }
        }
        return out;
    }

    /** "轻松/休闲/慢节奏" 等自然描述 → 规范节奏名（无命中返回 null；按组序取首个命中组） */
    public static String normalizePace(String pace) {
        if (pace == null || pace.isBlank()) {
            return null;
        }
        for (Map.Entry<String, List<String>> e : PACE_RULES.entrySet()) {
            for (String kw : e.getValue()) {
                if (pace.contains(kw)) {
                    return e.getKey();
                }
            }
        }
        return null;
    }

    private static String joinText(String title, String summary, String content) {
        StringBuilder sb = new StringBuilder();
        append(sb, title);
        append(sb, summary);
        append(sb, content);
        return sb.toString();
    }

    private static void append(StringBuilder sb, String part) {
        if (part != null && !part.isBlank()) {
            sb.append(part).append(' ');
        }
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
