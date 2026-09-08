package com.yuntu.tripplanner.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 帖子查重检测器（阶段四任务 8，PRODUCT_EVOLUTION_PLAN §15 任务 8：内容去重与低质识别）。
 *
 * <p>确定性规则：把标题/摘要归一化（去空白、标点、语气词），再与候选标题集合做
 * 「完全相同 / 包含 / 编辑距离小」三层判定，返回疑似重复的候选项。
 * 用途：提交审核时若与已有已发布/待审帖子高度相似 → 提示可能重复投稿，辅助管理员。
 */
public final class PostDuplicateDetector {

    private PostDuplicateDetector() {
    }

    /** 归一化标题：小写 + 去所有空白/标点/emoji（仅留中英文数字） */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{S}\\s]+", "")
                .trim();
    }

    /**
     * 在候选标题/摘要中找疑似重复。
     *
     * @param text    待检内容（通常 title + " " + summary）
     * @param others  已有的已发布/待审帖子（title + summary）
     * @return 命中的疑似重复原文（去重后），无则空列表
     */
    public static List<String> findDuplicates(String text, List<String> others) {
        List<String> hits = new ArrayList<>();
        if (text == null || text.isBlank() || others == null || others.isEmpty()) {
            return hits;
        }
        String norm = normalize(text);
        if (norm.length() < 4) {
            return hits;
        }
        for (String other : others) {
            if (other == null || other.isBlank()) {
                continue;
            }
            String otherNorm = normalize(other);
            if (otherNorm.length() < 4) {
                continue;
            }
            // 1) 完全相同
            if (norm.equals(otherNorm)) {
                hits.add(other.trim());
                continue;
            }
            // 2) 一方包含另一方（避免"成都三日游攻略" vs "成都三日游攻略（带预算）"）
            if (norm.contains(otherNorm) || otherNorm.contains(norm)) {
                hits.add(other.trim());
                continue;
            }
            // 3) 编辑距离 ≤ 2（容忍细微差别："大理5日" vs "大理五日"）
            if (Math.abs(norm.length() - otherNorm.length()) <= 2
                    && levenshtein(norm, otherNorm) <= 2) {
                hits.add(other.trim());
            }
        }
        return hits;
    }

    /** 编辑距离（Levenshtein），用于近似标题查重 */
    public static int levenshtein(String a, String b) {
        int m = a.length();
        int n = b.length();
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[n];
    }
}
