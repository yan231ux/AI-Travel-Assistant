package com.yuntu.tripplanner.common;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A/B 确定性分桶单测（阶段四任务 6）：
 * 可复现（同用户同实验同参数永远同桶）/ 流量收敛（不参与返回 null）/ 组间近似均衡 /
 * 匿名不入组 / 多实验正交（不同实验对同一用户的划分互不相关，无法从 A 推 B）。
 */
class AbBucketTest {

    @Test
    void sameUserAndParams_alwaysSameBucket() {
        String a = AbBucket.variantOf("u100", "spot_feed_quality_gate", 100, 50);
        String b = AbBucket.variantOf("u100", "spot_feed_quality_gate", 100, 50);
        assertEquals(a, b);
        assertTrue(AbBucket.VARIANT_CONTROL.equals(a) || AbBucket.VARIANT_TREATMENT.equals(a));
    }

    @Test
    void anonymousOrBlankUser_notParticipating() {
        assertNull(AbBucket.variantOf(null, "exp", 100, 50));
        assertNull(AbBucket.variantOf("  ", "exp", 100, 50));
    }

    @Test
    void zeroTraffic_noOneParticipates() {
        for (int i = 0; i < 100; i++) {
            assertNull(AbBucket.variantOf("u" + i, "exp", 0, 50));
        }
    }

    @Test
    void trafficGatesParticipation() {
        // 10% 流量：约 1/10 用户参与，绝大多数不参与
        Map<String, Integer> count = new HashMap<>();
        for (int i = 0; i < 2000; i++) {
            String v = AbBucket.variantOf("u" + i, "exp", 10, 50);
            count.merge(v == null ? "NONE" : v, 1, Integer::sum);
        }
        int none = count.getOrDefault("NONE", 0);
        assertTrue(none > 1500, "10% 流量下不参与用户应占绝大多数，实际 NONE=" + none);
        assertTrue(none < 2000);
    }

    @Test
    void controlOnly_whenControlPercent100() {
        for (int i = 0; i < 500; i++) {
            String v = AbBucket.variantOf("u" + i, "exp", 100, 100);
            assertEquals(AbBucket.VARIANT_CONTROL, v);
        }
    }

    @Test
    void fiftyFiftySplit_isApproximatelyBalanced() {
        int control = 0;
        int treatment = 0;
        for (int i = 0; i < 4000; i++) {
            String v = AbBucket.variantOf("u" + i, "exp", 100, 50);
            if (AbBucket.VARIANT_CONTROL.equals(v)) {
                control++;
            } else {
                treatment++;
            }
        }
        // 2000 ± 15%（哈希均匀性容差）
        assertTrue(Math.abs(control - 2000) < 300, "control=" + control);
        assertTrue(Math.abs(treatment - 2000) < 300, "treatment=" + treatment);
    }

    @Test
    void differentExperiments_areOrthogonal() {
        // 同一用户落不同实验的桶不应全等（实验名参与哈希）：
        // 若两实验完全相关则 same=400；完全正交期望 ~200（±25%）
        int same = 0;
        for (int i = 0; i < 400; i++) {
            String a = AbBucket.variantOf("u" + i, "exp_a", 100, 50);
            String b = AbBucket.variantOf("u" + i, "exp_b", 100, 50);
            if (a != null && a.equals(b)) {
                same++;
            }
        }
        assertTrue(same > 130 && same < 270, "same=" + same);
    }
}
