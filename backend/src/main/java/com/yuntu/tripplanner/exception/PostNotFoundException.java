package com.yuntu.tripplanner.exception;

/**
 * 帖子/评论/举报目标不存在（阶段二社区）→ 404。
 * Controller 层无需逐处 try/catch，由 GlobalExceptionHandler 统一转 404 JSON。
 */
public class PostNotFoundException extends RuntimeException {

    public PostNotFoundException(String message) {
        super(message);
    }
}
