package com.yuntu.tripplanner.common;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 约束标签词典：全局唯一来源。
 *
 * 标签 → 触发关键词（命中文本任一关键词即打该标签）。
 *
 * 之前 TAG_KEYWORDS 散落在 RagService 与 TravelAgent 两处手工维护，
 * 极易出现两侧维度不一致（雨天维度曾漏"备选"），导致 RAG 元数据过滤漏命中。
 * 现收敛为唯一来源，由攻略标注（RagService.deriveTags）与用户需求抽取
 * （TravelAgent.extractTags）共用，保证两侧过滤维度严格对齐。
 */
public final class TagDictionary {

    /** 标签 → 触发关键词（命中任一即打标）。维度与攻略 chunk 标注保持一致。 */
    public static final Map<String, List<String>> TAG_KEYWORDS = Map.of(
            "预算", List.of("预算", "省钱", "穷游", "性价比"),
            "亲子", List.of("亲子", "带娃", "儿童", "孩子", "家庭"),
            "雨天", List.of("雨天", "下雨", "室内", "备选", "台风"));

    private TagDictionary() {
    }

    /**
     * 在文本中匹配约束标签：命中任一关键词即打该标签。
     * 返回有序集合（按首命中顺序），保证多次调用结果稳定。
     */
    public static Set<String> match(String text) {
        Set<String> tags = new LinkedHashSet<>();
        if (text == null) {
            return tags;
        }
        for (Map.Entry<String, List<String>> e : TAG_KEYWORDS.entrySet()) {
            for (String kw : e.getValue()) {
                if (text.contains(kw)) {
                    tags.add(e.getKey());
                    break;
                }
            }
        }
        return tags;
    }

    /**
     * 攻略片段标签推导：标题 + 正文拼接后匹配，逗号分隔，便于存进 chunk map。
     * 与常规模块行为一致（title/text 任一命中即打标）。
     */
    public static String deriveTags(String title, String text) {
        StringBuilder sb = new StringBuilder();
        for (String t : match((title == null ? "" : title) + " " + (text == null ? "" : text))) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(t);
        }
        return sb.toString();
    }
}
