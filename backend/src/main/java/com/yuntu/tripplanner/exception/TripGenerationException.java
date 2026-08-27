package com.yuntu.tripplanner.exception;

/**
 * 行程生成异常
 *
 * 当 LLM 调用失败或输出无法解析为合法行程 JSON 时抛出，
 * 由上层转为 success=false（generate-with-trace）或 HTTP 500（generate），
 * 避免用兜底假数据污染渲染。
 */
public class TripGenerationException extends RuntimeException {

    public TripGenerationException(String message) {
        super(message);
    }

    public TripGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
