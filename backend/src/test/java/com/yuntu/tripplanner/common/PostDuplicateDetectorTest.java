package com.yuntu.tripplanner.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 帖子查重检测器单测（阶段四任务 8）：归一化 / 相同 / 包含 / 编辑距离近似。
 */
class PostDuplicateDetectorTest {

    @Test
    void normalize_stripsPunctuationAndWhitespace() {
        assertEquals("成都三日游攻略", PostDuplicateDetector.normalize("成都 三日游 攻略！"));
        assertEquals("dali5days", PostDuplicateDetector.normalize("Dali 5 Days,"));
    }

    @Test
    void exactSame_found() {
        List<String> hits = PostDuplicateDetector.findDuplicates(
                "成都三日游攻略", List.of("成都三日游攻略", "西安四日游"));

        assertEquals(1, hits.size());
        assertTrue(hits.get(0).contains("成都三日游攻略"));
    }

    @Test
    void containment_found() {
        List<String> hits = PostDuplicateDetector.findDuplicates(
                "成都三日游攻略（含预算）", List.of("成都三日游攻略"));

        assertEquals(1, hits.size());
    }

    @Test
    void nearEditDistance_found() {
        List<String> hits = PostDuplicateDetector.findDuplicates(
                "大理五日慢游记录", List.of("大理5日慢游记录"));

        assertEquals(1, hits.size());
    }

    @Test
    void unrelated_notFound() {
        List<String> hits = PostDuplicateDetector.findDuplicates(
                "三亚看海三天攻略", List.of("哈尔滨冰雪大世界两日游"));

        assertTrue(hits.isEmpty());
    }

    @Test
    void emptyInput_noHit() {
        assertTrue(PostDuplicateDetector.findDuplicates("  ", List.of("随便什么")).isEmpty());
        assertTrue(PostDuplicateDetector.findDuplicates("成都攻略", List.of()).isEmpty());
    }
}
