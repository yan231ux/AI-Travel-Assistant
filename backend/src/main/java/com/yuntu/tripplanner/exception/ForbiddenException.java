package com.yuntu.tripplanner.exception;

/**
 * 无操作权限（阶段二社区：非作者改删帖子、非 ADMIN 调用审核接口）→ 403。
 * 由 GlobalExceptionHandler 统一转 403 JSON；服务端强制校验，不依赖前端隐藏按钮。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
