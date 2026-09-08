package com.yuntu.tripplanner.common;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 帖子内容确定性规则检查（阶段二社区 §10.1"提交发布→规则检测→管理员审核"的第一步）。
 *
 * <p>提交审核前做轻量规则检测：长度约束 + 常见违规模式（联系方式广告/外链/恶意导流）。
 * 命中即拒绝提交并给出人读原因（不是 LLM 判断，是确定性规则，可单测、可追溯）。
 * 规则只负责「挡一层明显的」，最终人工审核（ADMIN）仍把关，不被规则完全替代。
 */
@Component
public class ContentRuleChecker {

    /** 标题最大长度 */
    public static final int TITLE_MAX = 120;
    /** 摘要最大长度 */
    public static final int SUMMARY_MAX = 300;
    /** 正文最小长度（防灌水，纯空白不算） */
    public static final int CONTENT_MIN = 10;
    /** 正文最大长度 */
    public static final int CONTENT_MAX = 20000;

    /** 手机号（大陆 1[3-9] 开头 11 位） */
    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
    /** 网址（http/https 外链或裸 www/域名形式） */
    private static final Pattern URL = Pattern.compile("(https?://|www\\.)[\\w.\\-]+");
    /** 微信/QQ 导流词 */
    private static final Pattern WECHAT_QQ = Pattern.compile("(加微信|加V|加v|微信号|VX|vx[:：]?\\s*\\w{4,}|QQ[:：]?\\s*\\d{5,}|qq[:：]?\\s*\\d{5,})");
    /** 营销/导流黑词 */
    private static final String[] AD_KEYWORDS = {
            "代购", "刷单", "兼职日结", "博彩", "棋牌", "外挂", "私服",
            "低价出票", "内部渠道", "百分百中签", "转账", "收款码"
    };

    /**
     * 检查标题/摘要/正文；返回违规原因列表（空 = 通过）。
     */
    public List<String> check(String title, String summary, String content) {
        List<String> violations = new ArrayList<>();
        if (title == null || title.isBlank()) {
            violations.add("标题不能为空");
        } else if (title.trim().length() > TITLE_MAX) {
            violations.add("标题不能超过 " + TITLE_MAX + " 字");
        }
        if (summary != null && summary.trim().length() > SUMMARY_MAX) {
            violations.add("摘要不能超过 " + SUMMARY_MAX + " 字");
        }
        if (content == null || content.trim().length() < CONTENT_MIN) {
            violations.add("正文至少 " + CONTENT_MIN + " 字，请写清楚一点再发布");
        } else if (content.trim().length() > CONTENT_MAX) {
            violations.add("正文不能超过 " + CONTENT_MAX + " 字");
        }
        String blob = (title == null ? "" : title) + "\n"
                + (summary == null ? "" : summary) + "\n"
                + (content == null ? "" : content);
        if (PHONE.matcher(blob).find()) {
            violations.add("内容含手机号，疑似联系方式广告，请删除后再提交");
        }
        if (URL.matcher(blob).find()) {
            violations.add("内容含外链，疑似引流广告，请删除后再提交");
        }
        if (WECHAT_QQ.matcher(blob).find()) {
            violations.add("内容含微信号/QQ 导流信息，请删除后再提交");
        }
        for (String kw : AD_KEYWORDS) {
            if (blob.contains(kw)) {
                violations.add("内容含敏感营销词「" + kw + "」，请删除后再提交");
            }
        }
        return violations;
    }
}
