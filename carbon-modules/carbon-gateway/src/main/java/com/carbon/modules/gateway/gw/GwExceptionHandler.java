package com.carbon.modules.gateway.gw;

import com.carbon.common.core.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把网关里的异常收成对外响应。
 *
 * <p>为什么限定 {@code basePackages}：管理端的 {@code /api/**} 有自己的
 * {@code GlobalExceptionHandler}，两套错误码不能混。网关是对外契约，
 * 管理端改一个 {@code code} 就会让接入方返工，所以这里必须是一份**独立**的映射。
 *
 * <p>顺序很关键：{@code GlobalExceptionHandler} 兜底 {@code Exception.class}，
 * 解析顺序是先注册先生效，所以这里必须 {@link Ordered#HIGHEST_PRECEDENCE}——
 * 否则网关的 401/403 会被它当成「未预期异常」吞成 500「服务器开小差了」。
 *
 * <p>这也意味着控制器里不需要写 try/catch：漏接一个异常最多是 5000，
 * 不会变成「HTTP 200 却带管理端 {@code R} 结构」这种更难查的错。
 *
 * @author carbon
 */
@RestControllerAdvice(basePackages = "com.carbon.modules.gateway")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GwExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GwExceptionHandler.class);

    @ExceptionHandler(GwRejectedException.class)
    public ResponseEntity<GwResult<Void>> handleRejected(GwRejectedException e) {
        return fail(e.errorCode(), e.getMessage());
    }

    /** 引擎/流程/连接器抛的内部错误：按 {@link GwErrorCode#from} 映射，映射不到的落到 5000 */
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<GwResult<Void>> handleService(ServiceException e) {
        return fail(GwErrorCode.from(e.error()), e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<GwResult<Void>> handleUnexpected(RuntimeException e) {
        // 记全栈，只回一句：异常消息里可能带 URL、SQL、内部路径
        log.error("网关调用异常", e);
        return fail(GwErrorCode.INTERNAL_ERROR, null);
    }

    private ResponseEntity<GwResult<Void>> fail(GwErrorCode code, String message) {
        return ResponseEntity.status(code.httpStatus())
                .body(GwResult.fail(code, message, GwGuard.currentTraceId()));
    }
}
