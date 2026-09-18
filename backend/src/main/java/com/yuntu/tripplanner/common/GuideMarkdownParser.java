package com.yuntu.tripplanner.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 攻略 Markdown 解析（纯静态工具，管理后台内容运营骨架）。
 *
 * <p>输入：与 resources/guides/*.md 同构的攻略 Markdown；输出结构化载体：
 * 城市（H1 去"旅行攻略"等后缀）、标题、摘要（H1 后首个段落）、景点清单（"核心景点"区
 * 三级标题"2.x 名称"且其小节含「位置 / 门票 / 简介」的卡片）、旅行风格标签（关键词词典）。
 *
 * <p>用途：攻略导入判重（content_hash）、列表/编辑页的结构化预览、city_guide_spot/tag 骨架写入。
 */
public final class GuideMarkdownParser {

    private GuideMarkdownParser() {
    }

    private static final Pattern H1 = Pattern.compile("^#\\s+(.+?)\\s*$");
    private static final Pattern H3 = Pattern.compile("^###\\s+(.+?)\\s*$");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.*$");
    private static final Pattern NUMBER_PREFIX = Pattern.compile("^\\d+[.、．\\s]+");
    /** 章节编号（如 2.1/2.10）后的真正景点名 */
    private static final Pattern SPOT_NO = Pattern.compile("^\\d+\\.\\d+\\s+(.+)$");

    private static final Pattern MARKED = Pattern.compile("\\*\\*(位置|门票|简介|游玩时长)\\*\\*");

    /** 汇总字段 */
    public record ParsedGuide(String city, String title, String summary,
                              List<String> spotNames, List<String> tags) {
    }

    /**
     * 解析攻略正文。
     *
     * @param content Markdown 全文
     * @param sourceFileName 来源文件名（如 beijing_guide.md，H1 缺失时兜底取城市）
     */
    public static ParsedGuide parse(String content, String sourceFileName) {
        List<String> lines = content == null ? List.of() : List.of(content.split("\\r?\\n", -1));

        String title = null;
        StringBuilder summary = new StringBuilder();
        boolean afterH1 = false;
        boolean summaryDone = false;

        String currentSpotHeading = null;
        StringBuilder spotBlock = null;
        Set<String> spots = new LinkedHashSet<>();

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                if (spotBlock != null) {
                    spotBlock.append('\n');
                } else if (afterH1 && !summaryDone && !summary.isEmpty()) {
                    summary.append('\n');
                }
                continue;
            }
            Matcher h1 = H1.matcher(line);
            Matcher h3 = H3.matcher(line);
            boolean isHeading = HEADING.matcher(line).matches();
            if (h1.matches() && title == null) {
                title = h1.group(1).trim();
                afterH1 = true;
                continue;
            }
            if (afterH1 && isHeading) {
                summaryDone = true;
            }
            if (afterH1 && !isHeading && !summaryDone && summary.length() < 300) {
                if (!line.startsWith(">")) {
                    if (summary.length() > 0) {
                        summary.append(' ');
                    }
                    summary.append(line);
                }
            }
            // 区块跟踪：### 开新块（记录 heading），其余行累积；块内容含"位置/门票/简介"即视为景点卡
            if (isHeading) {
                if (h3.matches()) {
                    flushSpot(currentSpotHeading, spotBlock, spots);
                    currentSpotHeading = h3.group(1).trim();
                    spotBlock = new StringBuilder();
                } else {
                    flushSpot(currentSpotHeading, spotBlock, spots);
                    currentSpotHeading = null;
                    spotBlock = null;
                }
                continue;
            }
            if (spotBlock != null) {
                spotBlock.append(line).append('\n');
            }
        }
        flushSpot(currentSpotHeading, spotBlock, spots);

        String city = normalizeCity(title, sourceFileName);
        String t = title == null || title.isBlank() ? city + "旅行攻略" : title;
        String sum = summary.toString().trim();
        if (sum.length() > 300) {
            sum = sum.substring(0, 300);
        }
        return new ParsedGuide(city, t, sum, List.copyOf(spots), detectTags(content));
    }

    private static void flushSpot(String heading, StringBuilder block, Set<String> out) {
        if (heading == null || block == null) {
            return;
        }
        if (MARKED.matcher(block).find()) {
            String name = heading;
            Matcher no = SPOT_NO.matcher(name);
            if (no.matches()) {
                name = no.group(1).trim();
            } else {
                name = NUMBER_PREFIX.matcher(name).replaceFirst("").trim();
            }
            if (!name.isBlank()) {
                out.add(name);
            }
        }
    }

    /** H1 文本 → 城市名：剥掉"旅行攻略/旅游攻略/旅游指南/游玩攻略/攻略"等后缀 */
    public static String normalizeCity(String title, String sourceFileName) {
        String t = title == null ? "" : title.trim();
        t = t.replaceAll("(旅行攻略|旅游攻略|游玩攻略|旅游指南|旅行指南|城市攻略|攻略|一日游|旅游)$", "").trim();
        t = t.replaceAll("[#\\s]+$", "").trim();
        if (!t.isBlank()) {
            return t;
        }
        // H1 缺失：回退文件名前缀（beijing_guide.md → beijing）
        if (sourceFileName != null) {
            String f = sourceFileName.replace('\\', '/');
            int slash = f.lastIndexOf('/');
            String base = slash >= 0 ? f.substring(slash + 1) : f;
            base = base.replaceFirst("\\.md$", "");
            String[] parts = base.split("_");
            if (parts.length > 0 && !parts[0].isBlank()) {
                return parts[0];
            }
        }
        return "未命名城市";
    }

    /** 全文关键词词典 → 画像 travel_style 标签（与景点/帖子标签同词表；命中即收，无则空） */
    public static List<String> detectTags(String content) {
        if (content == null) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        if (containsAny(content, "自然风景", "自然", "山水", "湖", "草原", "森林", "峡谷", "瀑布", "海岛", "沙滩", "雪山", "湿地")) {
            tags.add("自然风景");
        }
        if (containsAny(content, "历史", "古都", "古建筑", "宫", "陵", "寺", "庙", "博物馆", "文物", "遗址", "文化")) {
            tags.add("历史文化");
        }
        if (containsAny(content, "美食", "小吃", "餐厅", "夜市", "火锅", "烧烤", "菜", "海鲜")) {
            tags.add("美食探索");
        }
        if (containsAny(content, "打卡", "拍照", "夜景", "摄影", "网红")) {
            tags.add("拍照打卡");
        }
        if (containsAny(content, "亲子", "孩子", "家庭出游", "遛娃")) {
            tags.add("亲子游");
        }
        if (containsAny(content, "徒步", "骑行", "登山", "户外", "运动", "露营")) {
            tags.add("户外运动");
        }
        if (containsAny(content, "古镇", "老街", "胡同", "民俗", "市井", "市集", "节庆")) {
            tags.add("城市漫游");
        }
        return tags;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String k : keywords) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** 内容 SHA-256 十六进制（幂等导入 / 无变化保存判重） */
    public static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content == null ? new byte[0]
                    : content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
