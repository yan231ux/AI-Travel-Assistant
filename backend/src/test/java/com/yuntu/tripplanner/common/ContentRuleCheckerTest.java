package com.yuntu.tripplanner.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内容确定性规则检查单测（阶段二 §10.1）：长度约束 + 广告/联系方式/外链拦截。
 */
class ContentRuleCheckerTest {

    private final ContentRuleChecker checker = new ContentRuleChecker();

    @Test
    void normalContent_passes() {
        List<String> violations = checker.check("大理慢慢逛攻略",
                "5 个适合慢慢逛的地方",
                "第一天洱海西线骑行看日落，第二天喜洲古镇吃破酥粑粑，节奏很舒服。");
        assertTrue(violations.isEmpty());
    }

    @Test
    void tooShortContent_rejected() {
        List<String> violations = checker.check("标题", null, "太短");
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.contains("正文")));
    }

    @Test
    void phoneNumber_rejected() {
        List<String> violations = checker.check("标题", null, "想去的联系我 13812345678 价格优惠");
        assertTrue(violations.stream().anyMatch(v -> v.contains("手机号")));
    }

    @Test
    void wechatKeyword_rejected() {
        List<String> violations = checker.check("标题", null, "加微信领取低价门票，wxid_abc123 备注攻略");
        assertTrue(violations.stream().anyMatch(v -> v.contains("微信")));
    }

    @Test
    void externalUrl_rejected() {
        List<String> violations = checker.check("标题", null, "详见 https://example.com/guide 的完整攻略");
        assertTrue(violations.stream().anyMatch(v -> v.contains("外链")));
    }

    @Test
    void adKeyword_rejected() {
        List<String> violations = checker.check("标题", null, "本人可以代购景区门票，比官方便宜");
        assertTrue(violations.stream().anyMatch(v -> v.contains("代购")));
    }
}
