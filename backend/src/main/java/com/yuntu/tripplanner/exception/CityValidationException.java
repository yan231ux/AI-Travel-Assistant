package com.yuntu.tripplanner.exception;

/**
 * 目的地城市名校验失败异常。
 *
 * <p>由 {@code CityValidator} 触发（假城市 / 拼错且无命中），由全局异常处理器转为 HTTP 400，
 * message 为面向用户的提示文案（含纠错建议）。
 *
 * <p>选择"抛异常"而非"返回错误 ResponseEntity"的原因：SSE 端点返回 {@code ResponseEntity<SseEmitter>}
 * 时，SseEmitter 是靠泛型类型才被 {@code SseEmitterReturnValueHandler} 识别的；如果手工拼装 Map 错误体
 * 塞进 ResponseEntity，要么泛型变 {@code <?>} 导致 SSE 支持失效，要么类型不符直接转换异常。
 * 异常统一走 {@code @ExceptionHandler}，两种返回形态（SSE / 普通 JSON）都安全。
 */
public class CityValidationException extends RuntimeException {

    public CityValidationException(String message) {
        super(message);
    }
}
