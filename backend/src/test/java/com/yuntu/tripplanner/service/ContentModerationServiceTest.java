package com.yuntu.tripplanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.client.LlmClient;
import com.yuntu.tripplanner.common.ContentModerationRuleEngine;
import com.yuntu.tripplanner.config.LLMConfig;
import com.yuntu.tripplanner.model.AuditLog;
import com.yuntu.tripplanner.model.ContentModerationTask;
import com.yuntu.tripplanner.repository.ContentModerationTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 审核流水线单测（阶段三）：
 * 规则高危短路（不调 AI）/ 阈值聚合三档 / AI 失败与不可解析进待审 /
 * 同 hash 去重 / 管理员决策（覆盖必填原因）+ 决策落到帖子状态机。
 */
@ExtendWith(MockitoExtension.class)
class ContentModerationServiceTest {

    @Mock
    private ContentModerationTaskRepository taskRepository;
    @Mock
    private LlmClient llmClient;
    @Mock
    private AuditService auditService;
    @Mock
    private PostService postService;
    @Mock
    private PostCommentService postCommentService;

    private ContentModerationService service;

    /** 同步执行器：异步路径在测试里直接跑完，便于断言终态 */
    private static final Executor DIRECT = Runnable::run;

    @BeforeEach
    void setUp() {
        service = buildService(true);
        service.setModerationCallbacks(postService, postCommentService);
        lenient().when(taskRepository.selectCount(any())).thenReturn(0L);
    }

    /** 构造被测服务（autoPublishEnabled 可控，用于验证"自动发布"开关） */
    private ContentModerationService buildService(boolean autoPublishEnabled) {
        return new ContentModerationService(taskRepository, new ContentModerationRuleEngine(),
                llmClient, new LLMConfig(), new ObjectMapper(), auditService, DIRECT, autoPublishEnabled);
    }

    /** 默认给 REVIEW（保守：模型没明确说通过就不许自动放行） */
    private LlmClient.LlmResult ai(double score) {
        return ai(score, "REVIEW");
    }

    private LlmClient.LlmResult ai(double score, String decision) {
        return ai(score, decision, "[]");
    }

    private LlmClient.LlmResult ai(double score, String decision, String categoriesJson) {
        return new LlmClient.LlmResult(
                "{\"decision\":\"" + decision + "\",\"risk_level\":\"LOW\",\"risk_score\":" + score
                        + ",\"categories\":" + categoriesJson + ",\"suggestion\":\"测试\"}", 10, 20);
    }

    /** 一段能通过规则扫描的正常正文（不含高危/中危关键词，长度足够避免 TOO_SHORT） */
    private static final String CLEAN_TEXT = "这是一篇正常的旅行攻略正文内容，分享行程与心得，供大家参考。";

    @Test
    void highRuleHit_skipsAiAndGoesToReview() {
        // 手机号 = 高危规则命中 → 直接 REVIEW/HIGH，连 AI 都不调（省钱+确定性）
        ContentModerationTask task = service.submitAndProcess("POST", "1", null,
                "杭州攻略", "杭州", "详情请联系 13812345678", "u1");

        assertNotNull(task);
        assertEquals(ContentModerationTask.STATUS_REVIEW, task.getStatus());
        assertEquals(ContentModerationTask.RISK_HIGH, task.getRiskLevel());
        assertEquals(1, task.getRuleHitCount());
        verify(llmClient, never()).chat(any());
        verify(auditService, times(1)).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void thresholds_aggregateToThreeBands() {
        // ≥0.90 必须人工复核
        when(llmClient.chat(any())).thenReturn(ai(0.95, "REJECT"));
        ContentModerationTask high = service.submitAndProcess("POST", "1", null,
                "正常标题", "杭州", CLEAN_TEXT, "u1");
        assertEquals(ContentModerationTask.STATUS_REVIEW, high.getStatus());
        assertEquals(ContentModerationTask.RISK_HIGH, high.getRiskLevel());

        // 0.65~0.90 进审核队列（MEDIUM）
        when(llmClient.chat(any())).thenReturn(ai(0.70, "REVIEW"));
        ContentModerationTask medium = service.submitAndProcess("POST", "2", null,
                "正常标题", "杭州", CLEAN_TEXT, "u1");
        assertEquals(ContentModerationTask.STATUS_REVIEW, medium.getStatus());
        assertEquals(ContentModerationTask.RISK_MEDIUM, medium.getRiskLevel());

        // <0.65 且规则无命中 + 模型明确 PASS → 放行，并**真的自动发布**（不再是只写任务表）
        when(llmClient.chat(any())).thenReturn(ai(0.30, "PASS"));
        ContentModerationTask low = service.submitAndProcess("POST", "3", null,
                "正常标题", "杭州", CLEAN_TEXT, "u1");
        assertEquals(ContentModerationTask.STATUS_PASSED, low.getStatus());
        assertEquals(ContentModerationTask.RISK_LOW, low.getRiskLevel());
        // P1-1：放行时把审核任务绑定的版本号一并透传（纯帖子提交 revisionId=null）
        verify(postService).autoPublish("system:ai", 3L, null);
        // 自动发布写入 decision/decision_by，便于管理端区分"AI 自动放行"并仍可改判
        assertEquals(ContentModerationTask.DECISION_APPROVE, low.getDecision());
        assertEquals("system:ai", low.getDecisionBy());
    }

    @Test
    void lowScoreButModelSaysReview_neverAutoPublishes() {
        // 分数很低，但模型没有明确 PASS → 转人工，绝不自动上线（分数不可信时以显式判定为准）
        when(llmClient.chat(any())).thenReturn(ai(0.05, "REVIEW"));
        ContentModerationTask task = service.submitAndProcess("POST", "4", null,
                "正常标题", "杭州", CLEAN_TEXT, "u1");

        assertEquals(ContentModerationTask.STATUS_REVIEW, task.getStatus());
        verify(postService, never()).autoPublish(any(), any());
    }

    @Test
    void lowScoreButModelRejects_goesHighRiskReview() {
        // 模型显式判违规：即使分数低也按高危转人工
        when(llmClient.chat(any())).thenReturn(ai(0.20, "REJECT"));
        ContentModerationTask task = service.submitAndProcess("POST", "5", null,
                "正常标题", "杭州", CLEAN_TEXT, "u1");

        assertEquals(ContentModerationTask.STATUS_REVIEW, task.getStatus());
        assertEquals(ContentModerationTask.RISK_HIGH, task.getRiskLevel());
        verify(postService, never()).autoPublish(any(), any());
    }

    @Test
    void privacyCategory_forcesHumanEvenWithLowScoreAndPass() {
        // 敏感信息（PRIVACY）属"强制人工"类别：模型即便给 PASS + 极低分也必须人工
        when(llmClient.chat(any())).thenReturn(ai(0.10, "PASS",
                "[{\"code\":\"PRIVACY\",\"confidence\":0.9,\"evidence\":\"身份证 3301...\"}]"));
        ContentModerationTask task = service.submitAndProcess("POST", "6", null,
                "正常标题", "杭州", CLEAN_TEXT, "u1");

        assertEquals(ContentModerationTask.STATUS_REVIEW, task.getStatus());
        verify(postService, never()).autoPublish(any(), any());
    }

    @Test
    void controversyCategory_forcesHuman_viaChineseFallback() {
        // 模型没按受控词表输出（中文类别）也要兜住 —— 漏判的后果是敏感内容被自动发布
        when(llmClient.chat(any())).thenReturn(ai(0.10, "PASS",
                "[{\"code\":\"争议话题\",\"confidence\":0.8,\"evidence\":\"地域对比\"}]"));
        ContentModerationTask task = service.submitAndProcess("POST", "7", null,
                "正常标题", "杭州", CLEAN_TEXT, "u1");

        assertEquals(ContentModerationTask.STATUS_REVIEW, task.getStatus());
        verify(postService, never()).autoPublish(any(), any());
    }

    @Test
    void mediumSeverityRuleHit_blocksAutoPublish_evenIfAiPasses() {
        // 二维码引流 = MEDIUM 命中：确定性证据优先于模型分数，不得自动放行
        when(llmClient.chat(any())).thenReturn(ai(0.10, "PASS"));
        ContentModerationTask task = service.submitAndProcess("POST", "8", null,
                "正常标题", "杭州", "想要路线可以扫二维码联系我，这里附上详细说明若干字。", "u1");

        assertEquals(ContentModerationTask.STATUS_REVIEW, task.getStatus());
        verify(postService, never()).autoPublish(any(), any());
    }

    @Test
    void lowSeverityRuleHit_doesNotBlockAutoPublish() {
        // "正文过短"是 LOW 提示性命中：不该拦住正常放行（否则所有短帖都被拖进人工队列）
        when(llmClient.chat(any())).thenReturn(ai(0.10, "PASS"));
        ContentModerationTask task = service.submitAndProcess("POST", "12", null,
                "短标题", "杭州", "风景真好", "u1");

        assertTrue(task.getRuleHitCount() > 0, "应有 LOW 级规则命中");
        assertEquals(ContentModerationTask.STATUS_PASSED, task.getStatus());
        verify(postService).autoPublish("system:ai", 12L, null);
    }

    @Test
    void autoPublish_carriesRevisionId_forPostEditRevision() {
        // P1-1 版本化：审的是"已发布帖的修改版本"时，放行必须把 revisionId 传下去，
        // 否则 PostService 无法判断该切换哪个版本（旧任务结论不能套用到新稿）。
        when(llmClient.chat(any())).thenReturn(ai(0.10, "PASS"));
        ContentModerationTask task = service.submitAndProcess("POST", "20", 777L,
                "正常标题", "杭州", CLEAN_TEXT, "u1");

        assertEquals(ContentModerationTask.STATUS_PASSED, task.getStatus());
        verify(postService).autoPublish("system:ai", 20L, 777L);
    }

    @Test
    void autoPublishDisabled_keepsTaskPassedButNeverTouchesContent() {
        // 开关关闭 = 退回全人工：AI 仍给出"已放行"结论，但不自动上线
        ContentModerationService manual = buildService(false);
        manual.setModerationCallbacks(postService, postCommentService);
        when(llmClient.chat(any())).thenReturn(ai(0.10, "PASS"));

        ContentModerationTask task = manual.submitAndProcess("POST", "13", null,
                "正常标题", "杭州", CLEAN_TEXT, "u1");

        assertEquals(ContentModerationTask.STATUS_PASSED, task.getStatus());
        assertNull(task.getDecision(), "开关关闭时不得写系统决策，等人工");
        verify(postService, never()).autoPublish(any(), any());
    }

    @Test
    void autoPublish_onlyAppliesToPosts() {
        // 评论/攻略/景点各有自己的终局流程，自动发布不得越界
        when(llmClient.chat(any())).thenReturn(ai(0.10, "PASS"));
        ContentModerationTask task = service.submitAndProcess("COMMENT", "77", null,
                "评论", "杭州", CLEAN_TEXT, "u1");

        assertEquals(ContentModerationTask.STATUS_PASSED, task.getStatus());
        verify(postService, never()).autoPublish(any(), any());
    }

    @Test
    void aiFailure_goesToReviewNeverAutoPass() {
        // AI 调用失败：不能自动放行（§4.6）
        when(llmClient.chat(any())).thenThrow(new RuntimeException("LLM 超时"));
        ContentModerationTask task = service.submitAndProcess("POST", "9", null,
                "正常标题", "杭州", "这是一篇正常的旅行攻略正文内容，分享行程与心得。", "u1");
        assertEquals(ContentModerationTask.STATUS_REVIEW, task.getStatus());
        assertTrue(task.getErrorMessage().contains("LLM 超时"));
    }

    @Test
    void llmReturnsNull_contentGoesToReviewWithInfraReason() {
        // LLM 客户端返回 null（未配 Key / 免费额度耗尽 / 4xx 业务错误）：
        // 既不能自动放行，也不该被报成"模型不听话"——文案必须指向基础设施，
        // 否则排查时会拿环境问题去查提示词。（实测踩过：配额 403 被误读成"输出不可解析"）
        when(llmClient.chat(any())).thenReturn(null);
        ContentModerationTask task = service.submitAndProcess("POST", "11", null,
                "正常标题", "杭州", CLEAN_TEXT, "u1");

        assertNotNull(task);
        assertEquals(ContentModerationTask.STATUS_REVIEW, task.getStatus());
        assertTrue(task.getErrorMessage().contains("未返回内容"),
                "应明确是「没拿到内容」而非「解析失败」，实际=" + task.getErrorMessage());
        verify(postService, never()).autoPublish(any(), any());
    }

    @Test
    void unparseableAiOutput_goesToReview() {
        when(llmClient.chat(any())).thenReturn(
                new LlmClient.LlmResult("我觉得这篇帖子没什么问题，挺好的。", 10, 20));
        ContentModerationTask task = service.submitAndProcess("POST", "10", null,
                "正常标题", "杭州", "这是一篇正常的旅行攻略正文内容，分享行程与心得。", "u1");
        assertEquals(ContentModerationTask.STATUS_REVIEW, task.getStatus(),
                "AI 输出不可解析等同失败，宁可多审不可误放");
    }

    @Test
    void sameContentHash_dedupSkipsSecondTask() {
        when(taskRepository.selectCount(any())).thenReturn(1L);
        ContentModerationTask task = service.submitAndProcess("POST", "1", null,
                "同一篇", "杭州", "同一篇内容重复提交，不应再建审核任务。", "u1");
        assertNull(task, "同目标同 hash 未终局任务存在 → 不重复创建");
        verify(taskRepository, never()).insert(any(ContentModerationTask.class));
    }

    @Test
    void decide_rejectRequiresReasonAndAppliesToPost() {
        ContentModerationTask task = new ContentModerationTask();
        task.setId(5L);
        task.setTargetType(ContentModerationTask.TARGET_POST);
        task.setTargetId("9");
        task.setStatus(ContentModerationTask.STATUS_REVIEW);
        when(taskRepository.selectById(5L)).thenReturn(task);

        // 覆盖 AI 结论必须填原因（§4.6）
        assertThrows(IllegalArgumentException.class,
                () -> service.decide("admin-1", 5L, ContentModerationTask.DECISION_REJECT, " "));

        service.decide("admin-1", 5L, ContentModerationTask.DECISION_REJECT, "正文含导流广告");

        assertEquals(ContentModerationTask.DECISION_REJECT, task.getDecision());
        assertEquals("admin-1", task.getDecisionBy());
        assertNotNull(task.getDecidedAt());
        // 决策落到帖子状态机（按内容当前状态选动作：已发布→下架 / 待审→拒绝）
        verify(postService).applyRejectFromModeration("admin-1", 9L, "正文含导流广告");
        // 本服务记一条决策审计（postService.reject 内部的审计在其真实实现里，mock 不触发）
        verify(auditService, times(1)).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void decide_commentReject_softDeletes() {
        ContentModerationTask task = new ContentModerationTask();
        task.setId(6L);
        task.setTargetType(ContentModerationTask.TARGET_COMMENT);
        task.setTargetId("77");
        when(taskRepository.selectById(6L)).thenReturn(task);

        service.decide("admin-1", 6L, ContentModerationTask.DECISION_REJECT, "广告评论");

        verify(postCommentService).delete("admin-1", 77L);
    }

    @Test
    void prompt_includesStructuredJsonRequirement() {
        ContentModerationTask task = new ContentModerationTask();
        task.setTargetType("POST");
        task.setContentTitle("标题");
        task.setContentText("正文");
        String prompt = service.buildPrompt(task);
        assertTrue(prompt.contains("risk_score"), "提示词必须要求结构化 JSON 输出（§4.5）");
        assertTrue(prompt.contains("标题"));
    }

    @Test
    void auditRecordedWithContentCategory() {
        when(llmClient.chat(any())).thenReturn(ai(0.10));
        service.submitAndProcess("POST", "11", null, "正常标题", "杭州",
                "这是一篇正常的旅行攻略正文内容，分享行程与心得。", "u1");
        ArgumentCaptor<String> cat = ArgumentCaptor.forClass(String.class);
        verify(auditService, times(1)).record(any(), cat.capture(), any(), any(), any(), any());
        assertEquals(AuditLog.CAT_CONTENT, cat.getValue());
    }
}
