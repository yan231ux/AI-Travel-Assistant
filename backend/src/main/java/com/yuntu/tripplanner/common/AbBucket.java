package com.yuntu.tripplanner.common;

/**
 * A/B 实验确定性分桶（阶段四任务 6：推荐 A/B 实验）。
 *
 * <p>纯静态工具、无 IO，便于单测。核心性质：
 * <ol>
 *   <li><b>可复现</b>：同一 (用户, 实验名, 流量比例, 对照组比例) 永远落同一桶
 *       （FNV-1a 64 稳定哈希 → 0~99 百分位），不引入 Random；</li>
 *   <li><b>流量收敛</b>：百分位 ≥ trafficPercent 的用户不参与（返回 null，不写分桶行），
 *       改动 traffic/control 需先关闭实验再重建，避免中途"换桶"污染口径；</li>
 *   <li><b>分组正交</b>：实验名参与哈希，两个实验对同一用户的划分互不相关。</li>
 * </ol>
 */
public final class AbBucket {

    /** 实验变体：对照组（现状/基线） */
    public static final String VARIANT_CONTROL = "CONTROL";
    /** 实验变体：处理组（新策略） */
    public static final String VARIANT_TREATMENT = "TREATMENT";

    private AbBucket() {
    }

    /**
     * 计算某用户在某实验下的变体。
     *
     * @param userId         用户 ID（null/blank → 返回 null）
     * @param experimentName 实验名（参与哈希，保证多实验正交）
     * @param trafficPercent 参与流量 0~100（0 → 无人参与）
     * @param controlPercent 对照组占参与流量的百分比 0~100（100 → 全对照）
     * @return CONTROL / TREATMENT；不参与流量时返回 null
     */
    public static String variantOf(String userId, String experimentName,
                                   int trafficPercent, int controlPercent) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        long hash = fnv1a64(userId + "#" + experimentName
                + "#traffic" + trafficPercent + "#control" + controlPercent);
        long pct = Math.floorMod(hash, 100L);
        if (trafficPercent <= 0 || pct >= trafficPercent) {
            return null; // 落在参与流量之外：不参与本实验
        }
        long controlCut = (long) trafficPercent * Math.max(0, Math.min(100, controlPercent)) / 100L;
        return pct < controlCut ? VARIANT_CONTROL : VARIANT_TREATMENT;
    }

    /** FNV-1a 64 稳定哈希（非加密用途：分桶只要求均匀与可复现） */
    static long fnv1a64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }
}
