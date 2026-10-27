package com.carbon.common.core.exception;

import com.carbon.common.core.enums.BizError;

/**
 * 业务异常：带语义错误码，由 {@code GlobalExceptionHandler} 统一转成 {@code R.fail(code, msg)}。
 *
 * <p>约定：{@code message} 是**给使用者看的中文句子**，要能直接照着改；
 * 技术细节（三方返回体、SQL、堆栈）走日志，不进 message。
 *
 * <p>性能：不填堆栈（{@code super(message, null, false, false)}）——
 * 业务异常在参数校验这类高频路径上每分钟可能上百次，抓栈是纯浪费。
 * 需要排障时靠 {@code traceId} 去日志里捞。
 *
 * @author carbon
 */
public class ServiceException extends RuntimeException {

    private final BizError error;

    public ServiceException(BizError error, String message) {
        super(message, null, false, false);
        this.error = error;
    }

    public ServiceException(String message) {
        this(BizError.INTERNAL_ERROR, message);
    }

    public static ServiceException of(BizError error, String message) {
        return new ServiceException(error, message);
    }

    public static ServiceException param(String message) {
        return new ServiceException(BizError.PARAM_INVALID, message);
    }

    public BizError error() {
        return error;
    }

    /** 对应的 HTTP 状态码（管理端与网关都用它） */
    public int status() {
        return error.httpStatus();
    }
}
