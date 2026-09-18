package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.client.LlmClient;
import com.yuntu.tripplanner.common.ContentModerationRuleEngine;
import com.yuntu.tripplanner.common.ContentModerationRuleEngine.RuleHit;
import com.yuntu.tripplanner.config.LLMConfig;
import com.yuntu.tripplanner.model.AuditLog;
import com.yuntu.tripplanner.model.ContentModerationTask;
import com.yuntu.tripplanner.repository.ContentModerationTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * AI 内容审核服务（设计方案 §4，阶段三完整闭环）。
 *
 * <p>流水线（§4.2）：内容创建/编辑 → 规则检查 → AI 结构化初筛 → 阈值聚合 →
 * 自动放行 / 人工复核 → 人工最终决策 → 审计。
 *
 * <p>关键设计：
 * <ul>
 *   <li><b>规则先于 AI</b>（§4.3）：规则命中高危直接进人工复核，连 AI 都不调（省钱+确定）；</li>
 *   <li><b>AI 结构化输出</b>（§4.5）：要求 JSON（decision/risk_level/risk_score/categories），
 *       解析失败等同 AI 失败 → 进待审，绝不能自动放行；</li>
 *   <li><b>阈值决策</b>（§4.6）：score≥0.90 必须人工复核（HIGH）；0.65~0.90 进审核队列（MEDIUM）；
 *       <0.65 且规则通过 → 自动放行；AI 失败 → 待审；</li>
 *   <li><b>去重防旧覆新</b>（§4.4）：同目标同 content_hash 存在未终局任务时不重复建；
 *       攻略绑 revision_id；</li>
 *   <li><b>异步执行</b>：任务提交在专用线程池跑（规则+AI 秒级耗时不能卡内容发布主链路）；
 *       内容以快照载荷传入，异步线程不回读源表（规避"事务未提交读不到"的坑）；</li>
 *   <li><b>AI 只是建议</b>：管理员可覆盖结论（APPROVE/REJECT），REJECT 必须填原因，全程审计。</li>
 * </ul>
 */
@Slf4j
@Service
public class ContentModerationService {

    /** 阈值（§4.6）：≥0.90 必须人工复核 */
    static final double THRESHOLD_HIGH = 0.90;
    /** ≥0.65 进审核队列 */
    static final double THRESHOLD_MEDIUM = 0.65;

    public static final String PROMPT_VERSION = "v1";

    /** 自动发布时写入 decision_by 的系统主体（与管理员人工决策可区分，便于审计与追责） */
    public static final String ACTOR_SYSTEM_AI = "system:ai";

    /** 模型判定：明确通过 / 需复核 / 明确违规 */
    public static final String AI_DECISION_PASS = "PASS";
    public static final String AI_DECISION_REVIEW = "REVIEW";
    public static final String AI_DECISION_REJECT = "REJECT";

    /**
     * 受控风险类别码（§4.5）：提示词只允许从这份词表里选，代码侧才能据此做确定性判断。
     * 自由文本类别（如"涉黄"、"低质"混写）无法在代码里可靠匹配 —— 这正是"敏感信息/争议
     * 必须移交人工"落不了地的原因。
     */
    public static final String CAT_PRIVACY = "PRIVACY";           // 敏感信息（手机号/身份证/银行卡/住址）
    public static final String CAT_CONTROVERSY = "CONTROVERSY";   // 争议性话题（地域/群体/宗教等）
    /** 命中这些类别 → 强制转人工（即使模型给低风险分） */
    private static final java.util.Set<String> FORCE_HUMAN_CATEGORIES =
            java.util.Set.of(CAT_PRIVACY, CAT_CONTROVERSY);

    private final ContentModerationTaskRepository taskRepository;
    private final ContentModerationRuleEngine ruleEngine;
    private final LlmClient llmClient;
    private final LLMConfig llmConfig;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final Executor toolExecutor;
    /** 低风险内容是否允许"AI 放行即自动发布"（配置开关，默认开）；关掉则退回全人工，便于对比两种治理策略 */
    private final boolean autoPublishEnabled;

    public ContentModerationService(ContentModerationTaskRepository taskRepository,
                                    ContentModerationRuleEngine ruleEngine,
                                    LlmClient llmClient,
                                    LLMConfig llmConfig,
                                    ObjectMapper objectMapper,
                                    AuditService auditService,
                                    @Qualifier("toolExecutor") Executor toolExecutor,
                                    @Value("${moderation.auto-publish-enabled:true}") boolean autoPublishEnabled) {
        this.taskRepository = taskRepository;
        this.ruleEngine = ruleEngine;
        this.llmClient = llmClient;
        this.llmConfig = llmConfig;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.toolExecutor = toolExecutor;
        this.autoPublishEnabled = autoPublishEnabled;
    }

    /**
     * 异步提交审核任务（内容发布主链路旁路调用，绝不抛出）。
     *
     * @param targetType  POST / COMMENT / GUIDE / SPOT
     * @param targetId    对象 ID（字符串，兼容 Long 与 spot_id）
     * @param revisionId  攻略版本 ID（其他类型传 null）
     * @param title       内容标题（可空）
     * @param city        城市（可空）
     * @param text        正文内容快照（异步线程不回读源表）
     * @param triggeredBy 触发人（作者/提交人）
     */
    public void submitAsync(String targetType, String targetId, Long revisionId,
                            String title, String city, String text, String triggeredBy) {
        try {
            toolExecutor.execute(() -> {
                try {
                    submitAndProcess(targetType, targetId, revisionId, title, city, text, triggeredBy);
                } catch (Exception e) {
                    log.warn("AI 审核任务执行失败（不影响内容主链路）: {} {} err={}",
                            targetType, targetId, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("AI 审核任务提交失败（不影响内容主链路）: {}", e.getMessage());
        }
    }

    /** 同步入口（测试/重试用）：建任务 → 跑完流水线。 */
    public ContentModerationTask submitAndProcess(String targetType, String targetId, Long revisionId,
                                                  String title, String city, String text,
                                                  String triggeredBy) {
        String hash = sha256(targetType + "|" + (title == null ? "" : title) + "|"
                + (text == null ? "" : text));

        // 去重（§4.4）：同目标同 hash 已有未终局任务 → 不重复创建
        Long dup = taskRepository.selectCount(new LambdaQueryWrapper<ContentModerationTask>()
                .eq(ContentModerationTask::getTargetType, targetType)
                .eq(ContentModerationTask::getTargetId, targetId)
                .eq(ContentModerationTask::getContentHash, hash)
                .in(ContentModerationTask::getStatus,
                        ContentModerationTask.STATUS_PENDING,
                        ContentModerationTask.STATUS_RUNNING,
                        ContentModerationTask.STATUS_PASSED,
                        ContentModerationTask.STATUS_REVIEW));
        if (dup != null && dup > 0) {
            log.debug("审核任务去重命中：{} {} hash={}", targetType, targetId, hash.substring(0, 8));
            return null;
        }

        ContentModerationTask task = new ContentModerationTask();
        task.setTargetType(targetType);
        task.setTargetId(targetId);
        task.setRevisionId(revisionId);
        task.setContentHash(hash);
        task.setTaskType("SAFETY");
        task.setStatus(ContentModerationTask.STATUS_PENDING);
        task.setRuleHitCount(0);
        task.setRetryCount(0);
        task.setContentTitle(truncate(title, 200));
        task.setContentText(truncate(text, 20000));
        task.setCity(city);
        task.setCreatedBy(triggeredBy);
        task.setCreatedAt(LocalDateTime.now());
        taskRepository.insert(task);

        process(task);
        return task;
    }

    /** 流水线主体：规则 → （高危短路）→ AI 初筛 → 阈值聚合 → 终态落库 + 审计 */
    void process(ContentModerationTask task) {
        // 1) 规则层（§4.3）：便宜、确定、可解释
        List<RuleHit> hits = ruleEngine.scan(task.getContentTitle(), task.getContentText());
        task.setRuleHitCount(hits.size());
        try {
            task.setMatchedRulesJson(objectMapper.writeValueAsString(hits));
        } catch (Exception ignore) {
            task.setMatchedRulesJson("[]");
        }
        if (ruleEngine.hasHighSeverity(hits)) {
            // 高危规则命中：直接人工复核，不调 AI（省钱 + 确定性优先）
            finish(task, ContentModerationTask.STATUS_REVIEW,
                    ContentModerationTask.RISK_HIGH, null, null, null,
                    "规则命中高危（" + hits.get(0).name() + "），直接转人工复核");
            return;
        }

        // 2) AI 结构化初筛（§4.5）
        task.setStatus(ContentModerationTask.STATUS_RUNNING);
        task.setModelName(llmConfig.getModel());
        task.setPromptVersion(PROMPT_VERSION);
        taskRepository.updateById(task);
        String aiJson;
        try {
            LlmClient.LlmResult result = llmClient.chat(buildPrompt(task));
            aiJson = result == null ? null : result.content();
        } catch (Exception e) {
            // AI 失败：不能自动放行，进待审（§4.6）
            finish(task, ContentModerationTask.STATUS_REVIEW, null, null,
                    null, null, "AI 调用失败：" + e.getMessage());
            return;
        }

        AiVerdict verdict = parseVerdict(aiJson);
        if (verdict == null) {
            // 拿不到内容 / 内容解析不出，都等同失败：宁可多审，不可误放。
            // 但两者必须分开表述——处置动作一样，运维含义却完全不同：
            //   aiJson == null          → 基础设施问题（未配 Key / 免费额度耗尽 / 4xx 业务错误），该去查配置与额度；
            //   aiJson 非空但解析不出   → 模型没按要求吐 JSON，该去查提示词与模型行为。
            // 合并成一句"AI 输出不可解析"会把"配额耗尽"误报成"模型不听话"，
            // 实测中真的因此把环境问题误判成代码问题，多花了一轮排查。
            String reason = aiJson == null
                    ? "LLM 未返回内容（未配置 Key / 配额耗尽 / 服务返回业务错误），转人工复核"
                    : "AI 输出不可解析，转人工复核";
            if (aiJson == null) {
                log.warn("AI 初筛未拿到内容，转人工复核：target={}:{}", task.getTargetType(), task.getTargetId());
            }
            finish(task, ContentModerationTask.STATUS_REVIEW, null, null,
                    null, aiJson, reason);
            return;
        }

        // 3) 分级决策（§4.6 强化：自动放行必须"三条件同时成立"）
        //
        //    修正前的缺陷：只看 risk_score 三档，模型显式给的 decision 被丢弃，
        //    且"自动放行"只写审核任务表、从不回写内容 —— 于是界面上显示"已自动放行"，
        //    帖子却仍在待审队列等人点通过，AI 等于没用（用户实测到的"有没有都一样"）。
        //
        //    现在的准入：只要命中下面任意一条就必须人工复核，全部不命中才允许自动放行——
        //      ① 规则有 HIGH/MEDIUM 命中（确定性证据优先于模型的分数）
        //      ② 模型 decision 不是明确的 PASS（低分但说 REVIEW/REJECT 时，分数不可信）
        //      ③ 命中"强制人工"类别（敏感信息 / 争议话题）
        //      ④ 模型自评 risk_level 为 HIGH/CRITICAL（防"低分 + 高危等级"这种自相矛盾输出）
        //      ⑤ risk_score >= 0.65（沿用既有中风险线）
        boolean elevatedRiskLevel = ContentModerationTask.RISK_HIGH.equals(verdict.riskLevel())
                || ContentModerationTask.RISK_CRITICAL.equals(verdict.riskLevel());
        boolean blockingRuleHit = ruleEngine.hasBlockingSeverity(hits);
        boolean modelPassed = AI_DECISION_PASS.equals(verdict.decision());
        boolean modelRejects = AI_DECISION_REJECT.equals(verdict.decision());
        double score = verdict.riskScore();

        boolean mustReview = score >= THRESHOLD_MEDIUM
                || modelRejects
                || blockingRuleHit
                || verdict.needsHuman()
                || !modelPassed
                || elevatedRiskLevel;

        String riskLevel;
        if (score >= THRESHOLD_HIGH || modelRejects
                || ContentModerationTask.RISK_CRITICAL.equals(verdict.riskLevel())) {
            riskLevel = ContentModerationTask.RISK_HIGH;
        } else if (mustReview) {
            riskLevel = ContentModerationTask.RISK_MEDIUM;
        } else {
            riskLevel = ContentModerationTask.RISK_LOW;
        }
        String status = mustReview ? ContentModerationTask.STATUS_REVIEW
                : ContentModerationTask.STATUS_PASSED;
        finish(task, status, riskLevel, score, verdict, aiJson, null);

        // 4) 自动发布（仅低风险放行的帖子）：把"放行"变成真实后果，而不是一句显示文案
        if (ContentModerationTask.STATUS_PASSED.equals(status)) {
            autoPublish(task);
        }
    }

    /**
     * 低风险放行 → 自动发布帖子（可控开关）。
     *
     * <p>关键约束：
     * <ul>
     *   <li><b>复用既有状态机</b>：调 {@link PostService#autoPublish}（而非需要管理员权限的
     *       {@code approve}）——系统主体没有管理员账号，走人工入口必然权限失败；</li>
     *   <li><b>只处理帖子</b>（COMMENT/GUIDE/SPOT 各有自己的终局流程）；</li>
     *   <li><b>失败不影响审核结论</b>：内容可能已被作者删除/已改状态，捕获后仅告警留痕；</li>
     *   <li><b>写 decision 便于回溯</b>：decision=APPROVE、decision_by=system:ai，
     *       管理端据此区分"AI 自动放行"与"人工通过"，并仍可改判下架。</li>
     * </ul>
     */
    private void autoPublish(ContentModerationTask task) {
        if (!autoPublishEnabled) {
            log.info("AI 放行但自动发布开关关闭，保持待人工：taskId={}", task.getId());
            return;
        }
        if (!ContentModerationTask.TARGET_POST.equals(task.getTargetType())) {
            return;
        }
        long postId;
        try {
            postId = Long.parseLong(task.getTargetId());
        } catch (NumberFormatException e) {
            return;
        }
        try {
            // P1-1：带上 revisionId —— 若本次审的是"已发布帖的修改版本"，则只切换版本内容，
            // 不改变帖子状态/发布时间；revisionId 与帖子当前待审指针不一致 → 视为过期，不放行。
            postService.autoPublish(ACTOR_SYSTEM_AI, postId, task.getRevisionId());
        } catch (Exception e) {
            // 例如作者已撤回提交、内容已删除 → 自动发布无效，但审核结论仍然成立
            log.warn("AI 自动发布失败（审核结论保留）: taskId={} postId={} err={}",
                    task.getId(), postId, e.getMessage());
            return;
        }
        task.setDecision(ContentModerationTask.DECISION_APPROVE);
        task.setDecisionBy(ACTOR_SYSTEM_AI);
        task.setDecisionReason("低风险自动放行（规则无命中 + 模型判定通过 + 分数低于阈值）");
        task.setDecidedAt(LocalDateTime.now());
        taskRepository.updateById(task);
        auditService.record(ACTOR_SYSTEM_AI, AuditLog.CAT_CONTENT, "moderation_auto_publish",
                "post", String.valueOf(postId),
                AuditService.detailOf("taskId", String.valueOf(task.getId()),
                        "risk", ContentModerationTask.RISK_LOW));
        log.info("AI 自动发布：taskId={} postId={}", task.getId(), postId);
    }

    /** 终态落库 + 审计（单点维护 finished_at / 风险字段 / 审计动作） */
    private void finish(ContentModerationTask task, String status, String riskLevel,
                        Double riskScore, AiVerdict verdict, String resultJson, String errorMessage) {
        task.setStatus(status);
        task.setRiskLevel(riskLevel);
        task.setRiskScore(riskScore);
        if (resultJson != null) {
            task.setResultJson(truncate(resultJson, 20000));
        }
        task.setErrorMessage(truncate(errorMessage, 500));
        task.setFinishedAt(LocalDateTime.now());
        taskRepository.updateById(task);

        String action = "moderation_" + status.toLowerCase();
        auditService.record("system", AuditLog.CAT_CONTENT, action,
                "moderation", String.valueOf(task.getId()),
                AuditService.detailOf("target", task.getTargetType() + ":" + task.getTargetId(),
                        "risk", riskLevel == null ? "?" : riskLevel));
        log.info("AI 审核任务完成：id={} {} {} -> {} (risk={})", task.getId(),
                task.getTargetType(), task.getTargetId(), status, riskLevel);
    }

    /** 管理员决策（覆盖 AI 结论；REJECT 必须填原因），并应用到内容。 */
    public void decide(String adminId, Long taskId, String action, String reason) {
        if (!ContentModerationTask.DECISION_APPROVE.equals(action)
                && !ContentModerationTask.DECISION_REJECT.equals(action)) {
            throw new IllegalArgumentException("决策动作非法");
        }
        if (ContentModerationTask.DECISION_REJECT.equals(action)
                && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("覆盖 AI 结论必须填写原因");
        }
        ContentModerationTask task = taskRepository.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在");
        }
        task.setDecision(action);
        task.setDecisionBy(adminId);
        task.setDecisionReason(reason);
        task.setDecidedAt(LocalDateTime.now());
        taskRepository.updateById(task);

        applyDecisionToContent(adminId, task, action, reason);
        auditService.record(adminId, AuditLog.CAT_ADMIN,
                "moderation_decided_" + action.toLowerCase(), "moderation",
                String.valueOf(taskId),
                AuditService.detailOf("target", task.getTargetType() + ":" + task.getTargetId(),
                        "reason", reason == null ? "" : reason));
    }

    /** 决策落地：POST 复用既有审核动作（按内容当前状态选动作）；COMMENT 拒绝=软删；其余留痕由对应流程把关 */
    private void applyDecisionToContent(String adminId, ContentModerationTask task,
                                        String action, String reason) {
        try {
            long targetId = Long.parseLong(task.getTargetId());
            if (ContentModerationTask.TARGET_POST.equals(task.getTargetType())) {
                if (ContentModerationTask.DECISION_REJECT.equals(action)) {
                    // 按内容**当前状态**选动作：已发布（含被 AI 自动放行的）→ 下架；待审 → 拒绝。
                    // 修正前这里无条件调 reject()，而被自动放行的帖子已是 PUBLISHED，
                    // 状态机自校验会抛错 → 异常被吞 → 帖子仍然在线（"改判"实际不生效）。
                    postService.applyRejectFromModeration(adminId, targetId, reason);
                } else {
                    postService.approve(adminId, targetId);
                }
            } else if (ContentModerationTask.TARGET_COMMENT.equals(task.getTargetType())
                    && ContentModerationTask.DECISION_REJECT.equals(action)) {
                postCommentService.delete(adminId, targetId);
            }
            // GUIDE / SPOT：AI 仅初筛建议，最终处置走攻略审核流/景点治理流程（决策已留痕）
        } catch (NumberFormatException ignore) {
            // spot_id 等非数字 ID：决策留痕即可
        } catch (Exception e) {
            // 内容可能已被删除/状态已变：决策留痕仍有效，不回滚
            log.warn("审核决策应用失败（决策已留痕）: taskId={} err={}", task.getId(), e.getMessage());
        }
    }

    /**
     * 帖子/评论服务回调（setter + @Lazy 注入破构造环：PostService/PostCommentService 在内容动作里
     * 触发审核，审核决策又回调它们的既有审核动作 —— 构造/字段注入都会让 Spring 报
     * "currently in creation"；@Lazy 注入的是代理，首次真正调用时才解析目标 bean）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public void setModerationCallbacks(@org.springframework.context.annotation.Lazy PostService postService,
                                       @org.springframework.context.annotation.Lazy PostCommentService postCommentService) {
        this.postService = postService;
        this.postCommentService = postCommentService;
    }

    private PostService postService;
    private PostCommentService postCommentService;

    /** FAILED 任务重试（管理员手动；重跑流水线） */
    public void retry(String adminId, Long taskId) {
        ContentModerationTask task = taskRepository.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("审核任务不存在");
        }
        if (!ContentModerationTask.STATUS_FAILED.equals(task.getStatus())
                && !ContentModerationTask.STATUS_REVIEW.equals(task.getStatus())) {
            throw new IllegalArgumentException("仅失败或待审任务可重试");
        }
        task.setRetryCount((task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1);
        task.setStatus(ContentModerationTask.STATUS_PENDING);
        taskRepository.updateById(task);
        toolExecutor.execute(() -> {
            try {
                process(task);
            } catch (Exception e) {
                log.warn("审核任务重试失败: {} err={}", taskId, e.getMessage());
            }
        });
    }

    /** 分页查询（管理端列表） */
    public Page<ContentModerationTask> page(String status, String targetType, int page, int pageSize) {
        LambdaQueryWrapper<ContentModerationTask> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.eq(ContentModerationTask::getStatus, status);
        }
        if (targetType != null && !targetType.isBlank()) {
            qw.eq(ContentModerationTask::getTargetType, targetType);
        }
        qw.orderByDesc(ContentModerationTask::getCreatedAt);
        return taskRepository.selectPage(new Page<>(page, pageSize), qw);
    }

    public ContentModerationTask get(Long id) {
        return taskRepository.selectById(id);
    }

    /**
     * 作者视角：取某内容最新一条审核任务的精简视图（不暴露规则命中/AI 原文等敏感信息）。
     * 无任务时返回 null（=还没排上/还没建任务）。
     */
    public java.util.Map<String, Object> latestForViewer(String targetType, String targetId) {
        ContentModerationTask t = taskRepository.selectOne(new LambdaQueryWrapper<ContentModerationTask>()
                .eq(ContentModerationTask::getTargetType, targetType)
                .eq(ContentModerationTask::getTargetId, targetId)
                .orderByDesc(ContentModerationTask::getCreatedAt)
                .last("LIMIT 1"));
        if (t == null) {
            return null;
        }
        java.util.Map<String, Object> v = new LinkedHashMap<>();
        v.put("task_id", t.getId());
        v.put("status", t.getStatus());                    // PENDING / RUNNING / PASSED / REVIEW / FAILED
        v.put("risk_level", t.getRiskLevel());
        v.put("rule_hit_count", t.getRuleHitCount());
        v.put("decision", t.getDecision());                // APPROVE / REJECT / null
        v.put("decision_by", t.getDecisionBy());
        v.put("decision_reason", t.getDecisionReason());
        v.put("error_message", t.getErrorMessage());
        v.put("created_at", t.getCreatedAt());
        v.put("finished_at", t.getFinishedAt());
        return v;
    }

    /* ================= AI 初筛 ================= */

    /** AI 初筛提示词：只输出结构化 JSON（§4.5），不要自然语言 */
    String buildPrompt(ContentModerationTask task) {
        return String.format("""
                你是旅行社区的内容安全审核员。请审核以下用户内容，识别其中是否存在：
                导流广告（联系方式/外链/二维码/营销）、违法违禁（色情/赌博/毒品/暴力）、
                敏感信息（手机号/身份证号/银行卡号/精确住址）、争议性话题（地域对立/群体歧视/宗教民族敏感）、
                明显虚假信息（如价格与常识严重不符）、垃圾灌水。

                decision 判定口径（务必与 risk_score 保持一致）：
                  PASS   —— 上述各项均未发现，可安全公开展示；
                  REVIEW —— 存在轻度问题或你无法确定，需要人工复核；
                  REJECT —— 明确违反上述任一项，绝不可公开展示。

                categories[].code 只能从以下词表中选取（一份内容可命中多个）：
                  AD_SPAM 导流广告 | ILLEGAL 违法违禁 | PORNOGRAPHY 色情低俗 | GAMBLING 赌博
                  FRAUD 诈骗/虚假信息 | PRIVACY 敏感信息 | CONTROVERSY 争议性话题
                  FALSE_INFO 不实信息 | FLOOD 垃圾灌水 | OTHER 其他

                注意：命中 PRIVACY 或 CONTROVERSY 时，即使你认为风险不高，decision 也必须为 REVIEW（移交人工）。
                只输出 JSON，不要包含任何其他文字或代码块标记：
                {
                  "decision": "PASS|REVIEW|REJECT",
                  "risk_level": "LOW|MEDIUM|HIGH|CRITICAL",
                  "risk_score": 0.0到1.0的小数（越高风险越大）,
                  "categories": [{"code": "受控词表中的类别码", "confidence": 0.0到1.0, "evidence": "命中的原文片段"}],
                  "suggestion": "一句话处理建议"
                }

                内容类型：%s
                标题：%s
                正文：
                %s
                """,
                task.getTargetType(),
                task.getContentTitle() == null ? "（无）" : task.getContentTitle(),
                truncate(task.getContentText(), 4000));
    }

    /** 解析 AI 结构化输出；不可解析返回 null（调用方按"AI 失败"处理） */
    AiVerdict parseVerdict(String aiJson) {
        if (aiJson == null || aiJson.isBlank()) {
            return null;
        }
        try {
            String json = aiJson.trim();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start == -1 || end == -1 || end <= start) {
                return null;
            }
            Map<?, ?> m = objectMapper.readValue(json.substring(start, end + 1), Map.class);
            Object score = m.get("risk_score");
            double s = score instanceof Number n ? n.doubleValue() : -1;
            if (s < 0 || s > 1) {
                return null;
            }
            Object risk = m.get("risk_level");
            // decision 必须解析出来：它是"能不能自动放行"的硬条件之一。
            // 缺字段/写法不规范 → 归一为 null（下游按"未明确通过"处理，转人工），绝不默认放行。
            Object decision = m.get("decision");
            String decisionNorm = decision == null ? null : decision.toString().trim().toUpperCase();
            return new AiVerdict(s, risk == null ? null : risk.toString(), decisionNorm,
                    categoriesForceHuman(m.get("categories")));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * categories 是否命中"强制人工"类别（敏感信息 / 争议话题）。
     *
     * <p>以受控词表（{@link #CAT_PRIVACY}/{@link #CAT_CONTROVERSY}）为准，另加中文关键词兜底——
     * 模型偶尔不按词表输出（"隐私信息"、"争议"），只认英文码会漏判，而漏判的后果是
     * 含个人敏感信息的内容被自动发布上线。
     */
    private static boolean categoriesForceHuman(Object categories) {
        if (!(categories instanceof List<?> list)) {
            return false;
        }
        for (Object item : list) {
            String raw;
            if (item instanceof Map<?, ?> cm) {
                Object code = cm.get("code");
                raw = code == null ? "" : code.toString();
            } else {
                raw = item == null ? "" : item.toString();
            }
            String trimmed = raw.trim();
            if (FORCE_HUMAN_CATEGORIES.contains(trimmed.toUpperCase())) {
                return true;
            }
            if (trimmed.contains("隐私") || trimmed.contains("敏感信息") || trimmed.contains("争议")) {
                return true;
            }
        }
        return false;
    }

    /**
     * AI 初筛结论（只取聚合必需字段；完整输出另存 result_json）。
     *
     * @param decision  模型显式判定 PASS/REVIEW/REJECT（可能为 null：输出未给或不可识别）
     * @param needsHuman 命中"强制人工"类别（敏感信息/争议），即使分数低也必须人工
     */
    record AiVerdict(double riskScore, String riskLevel, String decision, boolean needsHuman) {
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "hash_error_" + s.hashCode();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
