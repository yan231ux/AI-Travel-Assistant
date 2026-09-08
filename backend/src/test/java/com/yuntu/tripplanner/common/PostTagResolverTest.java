package com.yuntu.tripplanner.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 帖子标签解析单测（阶段三：内容个性化闭环的标签信号源）。
 * 覆盖：关联景点 → 风格标签（与景点同词表）、正文关键词 → 风格/节奏、pace/city 字段、
 * 数量上限与去重。纯函数零 IO，直接断言解析结果。
 */
class PostTagResolverTest {

    @Test
    void linkedSpotAndContentAndField_mapToProfileTags() {
        // 模拟"大理适合慢慢逛的 5 个地方"：关联 洱海(风景名胜) + 大理古城(名称兜底)
        List<PostTagResolver.SpotRef> spots = List.of(
                new PostTagResolver.SpotRef("风景名胜", "洱海"),
                new PostTagResolver.SpotRef(null, "大理古城"));
        List<PostTagResolver.Tag> tags = PostTagResolver.resolve(
                "大理适合慢慢逛的 5 个地方",
                "不赶路版攻略",
                "第一天洱海西线骑行，第二天喜洲古镇慢慢逛，晚上去古城酒吧听歌。",
                "大理", null, spots);

        // 关联景点优先：洱海(风景名胜→自然风景) + 大理古城(名称→历史文化)
        assertTrue(contains(tags, "travel_style", "自然风景", PostTagResolver.SRC_SPOT_LINK));
        assertTrue(contains(tags, "travel_style", "历史文化", PostTagResolver.SRC_SPOT_LINK));
        // 正文关键词补充风格（酒吧→夜生活），但被 5 个上限约束
        List<PostTagResolver.Tag> styles = tags.stream()
                .filter(t -> "travel_style".equals(t.category())).toList();
        assertFalse(styles.isEmpty());
        assertTrue(styles.size() <= 5);
        // 节奏：正文"慢慢"→ 轻松（字段未填走正文识别）
        assertTrue(contains(tags, "pace", "轻松", PostTagResolver.SRC_CONTENT));
        // 城市：字段原样
        assertTrue(contains(tags, "city", "大理", PostTagResolver.SRC_POST_FIELD));
    }

    @Test
    void paceField_prioritizedOverContent() {
        List<PostTagResolver.Tag> tags = PostTagResolver.resolve(
                "三亚三日轻松路线", null, "每天睡到自然醒，慢慢逛。",
                "三亚", "紧凑", List.of());
        // 帖子字段明确写了"紧凑" → 用它，正文"轻松/慢慢"不覆盖
        assertTrue(contains(tags, "pace", "紧凑", PostTagResolver.SRC_POST_FIELD));
        assertFalse(contains(tags, "pace", "轻松", PostTagResolver.SRC_POST_FIELD));
    }

    @Test
    void foodKeywords_doNotProduceStyleNoise_fromPlainPosts() {
        // 一篇普通城市漫游随笔：不应因正文出现常见字而误打大量风格标签
        List<PostTagResolver.Tag> tags = PostTagResolver.resolve(
                "上海两日流水账", null,
                "第一天去外滩看夜景，第二天在南京东路逛街。",
                "上海", null, List.of());
        assertTrue(tags.stream().anyMatch(t -> "city".equals(t.category()) && "上海".equals(t.tag())));
        // 全量标签有界（风格≤5 + 节奏≤1 + 城市≤1）
        assertTrue(tags.size() <= 7);
    }

    @Test
    void blankEverything_returnsEmpty() {
        assertEquals(0, PostTagResolver.resolve(null, null, "  ", null, null, null).size());
    }

    private static boolean contains(List<PostTagResolver.Tag> tags, String category, String tag, String source) {
        return tags.stream().anyMatch(t -> category.equals(t.category())
                && tag.equals(t.tag()) && source.equals(t.source()));
    }
}
