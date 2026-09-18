package com.yuntu.tripplanner.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 攻略 Markdown 解析单测（内容运营骨架）：城市/标题/摘要抽取、景点卡片识别、
 * 标签词典、SHA-256 幂等键。
 */
class GuideMarkdownParserTest {

    private static final String SAMPLE = """
            # 北京旅行攻略

            北京，中国首都。春秋最佳，交通以地铁为主。

            ## 1. 目的地简介

            北京有故宫、长城等世界遗产，胡同文化浓厚。

            ## 2. 核心景点

            ### 2.1 故宫博物院
            - **位置**：东城区景山前街4号
            - **门票**：旺季60元
            - **简介**：明清两代皇家宫殿，世界最大木结构古建筑群。

            ### 2.2 天安门广场
            - **位置**：东城区东长安街
            - **门票**：免费
            - **简介**：世界最大城市广场。

            ### 2.3 老字号餐厅推荐
            - **店名**：全聚德烤鸭，人均 200 元

            ## 3. 注意事项

            - 安检严格，带好身份证。
            """;

    @Test
    void parse_extractsCityTitleSummaryAndSpots() {
        GuideMarkdownParser.ParsedGuide p = GuideMarkdownParser.parse(SAMPLE, "beijing_guide.md");

        assertEquals("北京", p.city());
        assertEquals("北京旅行攻略", p.title());
        assertTrue(p.summary().contains("北京"));
        // 只有含"位置/门票/简介"的卡片算景点；"店名"的餐饮卡片不算
        assertEquals(List.of("故宫博物院", "天安门广场"), p.spotNames());
        assertTrue(p.tags().contains("历史文化"));
    }

    @Test
    void parse_missingH1_fallsBackToFileNameCity() {
        String content = "## 1. 目的地简介\n\n正文内容。";
        GuideMarkdownParser.ParsedGuide p = GuideMarkdownParser.parse(content, "shanghai_guide.md");

        assertEquals("shanghai", p.city());
        assertEquals("shanghai旅行攻略", p.title());
    }

    @Test
    void normalizeCity_stripsGuideSuffixes() {
        assertEquals("成都", GuideMarkdownParser.normalizeCity("成都旅游攻略", null));
        assertEquals("杭州", GuideMarkdownParser.normalizeCity("杭州游玩攻略", null));
        assertEquals("大理", GuideMarkdownParser.normalizeCity("大理", null));
    }

    @Test
    void sha256_deterministicAndContentSensitive() {
        String a = GuideMarkdownParser.sha256("hello");
        String b = GuideMarkdownParser.sha256("hello");
        String c = GuideMarkdownParser.sha256("hello!");

        assertEquals(64, a.length());
        assertEquals(a, b);
        assertNotEquals(a, c);
    }
}
