package com.yuntu.tripplanner.common;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容审核规则引擎（设计方案 §4.3，阶段三 AI 审核流水线第一步）。
 *
 * <p>与 {@link ContentRuleChecker}（发帖提交的"拦截式"检查，命中即拒绝提交）分工：
 * 本引擎是审核任务里的"记录式"扫描 —— 命中不直接拦截，而是产出结构化命中
 * （rule_code / rule_name / matched_text / severity），与 AI 初筛聚合风险等级。
 * 便宜、确定、可解释，先于 AI 执行；命中高危规则直接进人工复核（连 AI 都不用调）。
 */
@Component
public class ContentModerationRuleEngine {

    /** 规则命中（结构化，可解释） */
    public record RuleHit(String code, String name, String matchedText, String severity) {
    }

    public static final String SEVERITY_HIGH = "HIGH";
    public static final String SEVERITY_MEDIUM = "MEDIUM";
    public static final String SEVERITY_LOW = "LOW";

    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern URL = Pattern.compile("(https?://|www\\.)[\\w.\\-]+");
    private static final Pattern WECHAT_QQ = Pattern.compile(
            "(加微信|加V|加v|微信号|VX|vx[:：]?\\s*\\w{4,}|QQ[:：]?\\s*\\d{5,}|qq[:：]?\\s*\\d{5,})");
    private static final Pattern QR_CODE = Pattern.compile("(二维码|扫码|扫一扫)");
    private static final Pattern VIOLENT = Pattern.compile("(色情|赌博|毒品|枪支|代开发票|洗钱)");
    private static final String[] AD_KEYWORDS = {
            "代购", "刷单", "兼职日结", "博彩", "棋牌", "外挂", "私服",
            "低价出票", "内部渠道", "百分百中签", "转账", "收款码",
            "免费领取", "优惠券", "加群", "推广", "代订"
    };

    /** 命中文本在快照里的最大展示长度 */
    private static final int MATCHED_SNIPPET_MAX = 50;

    /**
     * 扫描标题+正文；返回全部规则命中（空 = 规则通过）。
     *
     * @param title 内容标题（可空）
     * @param text  正文内容（可空）
     */
    public List<RuleHit> scan(String title, String text) {
        List<RuleHit> hits = new ArrayList<>();
        String blob = (title == null ? "" : title) + "\n" + (text == null ? "" : text);
        if (blob.isBlank()) {
            return hits;
        }

        if (find(PHONE, blob) != null) {
            hits.add(new RuleHit("CONTACT_PHONE", "手机号联系方式", snippet(find(PHONE, blob)), SEVERITY_HIGH));
        }
        String wq = find(WECHAT_QQ, blob);
        if (wq != null) {
            hits.add(new RuleHit("CONTACT_EXPORT", "微信/QQ 导流", snippet(wq), SEVERITY_HIGH));
        }
        String qr = find(QR_CODE, blob);
        if (qr != null) {
            hits.add(new RuleHit("CONTACT_QR", "二维码引流", snippet(qr), SEVERITY_MEDIUM));
        }
        String violent = find(VIOLENT, blob);
        if (violent != null) {
            hits.add(new RuleHit("ILLEGAL_CONTENT", "违法违禁内容", snippet(violent), SEVERITY_HIGH));
        }
        String url = find(URL, blob);
        if (url != null) {
            hits.add(new RuleHit("EXTERNAL_URL", "外部链接", snippet(url), SEVERITY_MEDIUM));
        }
        for (String kw : AD_KEYWORDS) {
            if (blob.contains(kw)) {
                hits.add(new RuleHit("ADVERTISING", "营销导流词「" + kw + "」", kw, SEVERITY_MEDIUM));
                break; // 同类命中记一条即可，避免刷屏
            }
        }
        if (text != null && text.trim().length() < 10) {
            hits.add(new RuleHit("TOO_SHORT", "正文过短（疑似灌水）",
                    text.trim(), SEVERITY_LOW));
        }
        return hits;
    }

    /** 是否存在高危命中（直接人工复核，跳过 AI） */
    public boolean hasHighSeverity(List<RuleHit> hits) {
        return hits != null && hits.stream().anyMatch(h -> SEVERITY_HIGH.equals(h.severity()));
    }

    /**
     * 是否存在<b>阻断自动放行</b>的命中（HIGH / MEDIUM）。
     *
     * <p>用于"自动发布"的准入判定：只有确定性的高风险（手机号/导流/违禁）与中风险
     * （二维码/外链/营销词）才必须人工看一眼；{@code LOW}（如"正文过短"）属于提示性
     * 命中，不应拦住一篇正常内容上线 —— 否则所有短帖都会被拖进人工队列。
     *
     * <p><b>与 {@link #hasHighSeverity} 的区别</b>：后者决定"要不要调 AI"，本方法决定
     * "AI 说没问题时能不能直接上线"。两者判据不同，不要合并。
     */
    public boolean hasBlockingSeverity(List<RuleHit> hits) {
        return hits != null && hits.stream()
                .anyMatch(h -> SEVERITY_HIGH.equals(h.severity()) || SEVERITY_MEDIUM.equals(h.severity()));
    }

    private String find(Pattern p, String blob) {
        Matcher m = p.matcher(blob);
        return m.find() ? m.group() : null;
    }

    private String snippet(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MATCHED_SNIPPET_MAX ? s : s.substring(0, MATCHED_SNIPPET_MAX) + "…";
    }
}
