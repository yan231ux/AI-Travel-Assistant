package com.yuntu.tripplanner.agent;

import com.yuntu.tripplanner.model.AgentTraceStep;

/**
 * Agent 执行过程回调（SSE 流式推送用）
 *
 * - onStep：每个阶段完成后回调一条轨迹（plan_search/tool_execution/assess/generate）
 * - onProgress：阻塞调用（LLM/工具）开始前的进度提示
 * - isClosed：客户端是否已断开；为 true 时 Agent 提前退出，避免白占线程
 *
 * 回调实现必须自己 catch 一切异常（如 SSE 发送失败），绝不能向 Agent 抛出，
 * 否则会被 execute() 的 try/catch 当成生成失败处理。
 */
@FunctionalInterface
public interface AgentCallback {

    AgentCallback NOOP = step -> {};

    void onStep(AgentTraceStep step);

    default void onProgress(String phase, String message) {
    }

    default boolean isClosed() {
        return false;
    }
}
