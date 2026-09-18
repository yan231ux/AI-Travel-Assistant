package com.yuntu.tripplanner.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * LLM配置类
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "llm")
public class LLMConfig {
    
    /**
     * LLM Base URL
     */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    
    /**
     * API Key
     */
    private String apiKey;
    
    /**
     * 模型名称（qwen-plus 免费额度用完后切到 qwen3.7-plus：35B 稠密 / 100万上下文，兼容 OpenAI 协议）
     */
    private String model = "qwen-turbo";
    
    /**
     * 超时时间（秒）
     */
    private Integer timeoutSeconds = 60;
    
    /**
     * 最大迭代次数
     */
    private Integer maxIterations = 3;

    /**
     * Agent 决策模式：
     * - legacy（默认）：首轮 LLM 决策 + 后续轮规则按缺口补轮（改造前行为，保持可复现与成本可控）
     * - autonomous：每轮都由 LLM 看已获数据决定下一步，并把失败调用回灌让模型自纠
     *
     * 默认 legacy：既有 612 个用例与线上行为完全不变；切 autonomous 后新链路单独用例覆盖。
     */
    private String agentMode = "legacy";

    /**
     * 原生工具调用（function calling）策略，仅在 agent-mode=autonomous 时生效：
     * - auto（默认）：先试原生 tools；模型返回 4xx 明确不接受时，自动降级为文本 JSON 计划，
     *                 并按模型名记住结论（换模型会重新探测）
     * - native：强制走原生 tools（模型不支持则会失败并回落文本计划）
     * - text  ：从不使用 tools，一律用文本 JSON 计划（任何 chat 模型都能跑，最稳）
     *
     * 之所以做成三态：本系统需要能频繁更换模型（常用免费额度模型），
     * 不能让"是否支持 tools"成为换模型的门槛。
     */
    private String toolCalling = "auto";

    /**
     * autonomous 模式下"允许由 LLM 决策的补轮次数"上限（默认 1）。
     *
     * <p>为什么要有这个上限：每轮补轮都问一次模型，prompt token 会随轮数线性上升
     * （实测 3 轮时是 legacy 的 3.3 倍）。首次补轮最需要模型判断（它才知道"上一轮为什么失败、
     * 该换什么关键词"）；再往后继续问，边际收益下降而成本照付。
     * 因此默认只让模型决策**第一次补轮**，之后的补轮改由规则按缺口补齐。
     *
     * <p>设为 0 = 补轮全部走规则（最省，等同放弃失败自纠）；设大 = 更自主但更贵。
     * 补轮总次数仍受 {@link #maxIterations} 约束。
     */
    private Integer llmGapfillRounds = 1;

    /**
     * 文本向量化模型（RAG 用）
     */
    private String embeddingModel = "text-embedding-v3";
}