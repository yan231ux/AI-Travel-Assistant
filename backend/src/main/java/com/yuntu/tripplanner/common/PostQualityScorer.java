package com.yuntu.tripplanner.common;

/**
 * 帖子内容质量评分器（阶段四任务 5，PRODUCT_EVOLUTION_PLAN §15 任务 5/8）。
 *
 * <p>纯确定性规则（零 LLM、零外部依赖），满分 100，维度：
 * <ul>
 *   <li>篇幅与结构（40）：正文长度、段落数（是否分点/换行）——灌水短文拿不到分；</li>
 *   <li>结构化信息（30）：摘要、城市、行程天数、预算、关联景点 —— 干货越足分越高；</li>
 *   <li>表达质量（30）：重复句子、过于稀疏的标点、疑似凑字（大量重复字符/无意义填充）。
 * </ul>
 * 低质判定（&lt; {@link #LOW_QUALITY_THRESHOLD}）用于审核队列低质标记与投稿拦截提示。
 * 评分只落库展示 + 辅助审核，不直接决定帖子能否发布（最终决定权仍在管理员）。
 */
public final class PostQualityScorer {

    /** 低于该分视为低质内容（内容过短/缺结构） */
    public static final int LOW_QUALITY_THRESHOLD = 45;

    private PostQualityScorer() {
    }

    /** 打分输入（避免超长参数列表） */
    public record Input(String title, String summary, String content, String city,
                        Integer travelDays, Double budget, int spotCount) {
    }

    /** 评分结果：总分 0~100 + 低质标记 + 一句话说明 */
    public record Result(int score, boolean lowQuality, String reason) {
    }

    public static Result score(Input in) {
        String content = in.content() == null ? "" : in.content();
        String summary = in.summary() == null ? "" : in.summary();
        int contentLen = content.replaceAll("\\s", "").length();

        // —— 篇幅与结构（40）——
        int structure = 0;
        if (contentLen >= 300) {
            structure += 20;
        } else if (contentLen >= 120) {
            structure += 14;
        } else if (contentLen >= 40) {
            structure += 6;
        }
        int paragraphs = content.split("\\n").length;
        if (paragraphs >= 3) {
            structure += 12;
        } else if (paragraphs >= 2) {
            structure += 6;
        }
        if (contentLen >= 40 && hasPunctuation(content)) {
            structure += 8;
        }

        // —— 结构化信息（30）——
        int info = 0;
        if (!summary.isBlank()) {
            info += 8;
        }
        if (in.city() != null && !in.city().isBlank()) {
            info += 4;
        }
        if (in.travelDays() != null && in.travelDays() > 0) {
            info += 6;
        }
        if (in.budget() != null && in.budget() > 0) {
            info += 6;
        }
        if (in.spotCount() > 0) {
            info += 6;
        }

        // —— 表达质量（30）——
        int quality = 30;
        if (contentLen < 20) {
            quality -= 18;
        }
        // 重复句段扣分（同一句话出现 >=2 次 = 凑字）
        int repeats = countRepeatedLines(content);
        quality -= Math.min(12, repeats * 6);
        // 中英文夹杂过长无标点长句扣分
        if (contentLen > 60 && !hasPunctuation(content)) {
            quality -= 10;
        }

        int score = Math.max(0, Math.min(100, structure + info + quality));
        boolean low = score < LOW_QUALITY_THRESHOLD;
        String reason = low ? lowReason(score, contentLen, paragraphs, info) : "";
        return new Result(score, low, reason);
    }

    /** 是否有中文/英文标点（逗号/句号/感叹/问号等） */
    private static boolean hasPunctuation(String s) {
        return s.matches(".*[，。！？、；：,.!?;:].*");
    }

    /** 统计重复的句子（按换行分段，内容相同的行数之和），用于抓"复制粘贴凑字" */
    private static int countRepeatedLines(String content) {
        java.util.Map<String, Integer> freq = new java.util.HashMap<>();
        for (String line : content.split("\\n")) {
            String t = line.trim();
            if (t.length() < 6) {
                continue; // 太短的行（如空行/短句）不算
            }
            freq.merge(t, 1, Integer::sum);
        }
        int repeats = 0;
        for (int v : freq.values()) {
            if (v > 1) {
                repeats += (v - 1);
            }
        }
        return repeats;
    }

    private static String lowReason(int score, int len, int paragraphs, int info) {
        if (len < 40) {
            return "正文过短（少于约 40 字）";
        }
        if (paragraphs < 2) {
            return "缺少分段，阅读结构差";
        }
        if (info < 12) {
            return "信息量不足：缺少摘要/城市/天数/预算/关联景点等关键信息";
        }
        return "整体内容较单薄";
    }
}
