package com.yuntu.tripplanner.common;

import com.yuntu.tripplanner.common.ContentModerationRuleEngine.RuleHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 审核规则引擎单测：高危（联系方式/违法）→ MEDIUM（外链/营销词）→ 低危（过短），正常内容零命中。 */
class ContentModerationRuleEngineTest {

    private final ContentModerationRuleEngine engine = new ContentModerationRuleEngine();

    @Test
    void normalTravelContent_hasNoHits() {
        List<RuleHit> hits = engine.scan("杭州两日游",
                "第一天上午去了西湖，下午逛了灵隐寺，第二天去了龙井村喝茶，体验很好。");
        assertTrue(hits.isEmpty(), "正常旅行内容不应命中任何规则");
    }

    @Test
    void phoneAndWechat_areHighSeverity() {
        List<RuleHit> hits = engine.scan("攻略", "加微信 lx12345 获取更多，电话 13812345678");
        assertTrue(engine.hasHighSeverity(hits));
        assertTrue(hits.stream().anyMatch(h -> "CONTACT_PHONE".equals(h.code())));
        assertTrue(hits.stream().anyMatch(h -> "CONTACT_EXPORT".equals(h.code())));
    }

    @Test
    void illegalContent_isHighSeverity() {
        assertTrue(engine.hasHighSeverity(engine.scan("x", "这里有赌博资源")));
    }

    @Test
    void externalUrlAndAdKeyword_areMediumOnly() {
        List<RuleHit> hits = engine.scan("优惠", "详情见 https://example.com，免费领取优惠券");
        assertFalse(engine.hasHighSeverity(hits), "外链/营销词是 MEDIUM，不应短路 AI");
        assertTrue(hits.stream().anyMatch(h -> "EXTERNAL_URL".equals(h.code())));
        assertTrue(hits.stream().anyMatch(h -> "ADVERTISING".equals(h.code())));
    }

    @Test
    void tooShortText_isLowSeverity() {
        List<RuleHit> hits = engine.scan("标题", "太短了");
        assertFalse(engine.hasHighSeverity(hits));
        assertTrue(hits.stream().anyMatch(h -> "TOO_SHORT".equals(h.code())));
    }

    @Test
    void blankContent_returnsEmpty() {
        assertTrue(engine.scan(null, "  ").isEmpty());
    }
}
