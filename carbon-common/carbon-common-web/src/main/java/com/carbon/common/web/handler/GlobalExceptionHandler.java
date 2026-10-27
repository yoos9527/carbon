package com.carbon.common.web.handler;

import com.carbon.common.core.domain.R;
import com.carbon.common.core.enums.BizError;
import com.carbon.common.core.exception.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：把一切异常收敛成 {@code R<T>}，且**不让堆栈漏到响应体**。
 *
 * <p>三条原则：
 * <ol>
 *   <li>参数类异常必须给「哪一列错了、应该怎么改」，前端表单要能直接定位到字段；</li>
 *   <li>未预期异常只回一句「服务器开小差了，请把 traceId 给运维」，真实堆栈进日志；</li>
 *   <li>HTTP 状态码与业务码一致，不要出现 200 包着失败的情况。</li>
 * </ol>
 *
 * @author carbon
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<R<Void>> handleService(ServiceException e, HttpServletRequest request) {
        log.warn("业务异常 {} {} -> {}: {}", request.getMethod(), request.getRequestURI(),
                e.error(), e.getMessage());
        return ResponseEntity.status(statusOf(e.error()))
                .body(R.fail(e.status(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::describe)
                .collect(Collectors.joining("；"));
        return badRequest(msg);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<R<Void>> handleBind(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::describe)
                .collect(Collectors.joining("；"));
        return badRequest(msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Void>> handleConstraint(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(GlobalExceptionHandler::describe)
                .collect(Collectors.joining("；"));
        return badRequest(msg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<R<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return badRequest("缺少必填参数：" + e.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<R<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return badRequest("参数 " + e.getName() + " 的类型不对，期望 "
                + (e.getRequiredType() == null ? "未知" : e.getRequiredType().getSimpleName()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        // 不把 Jackson 的原始报错吐出去：它会带类名与字段路径，属于内部信息
        return badRequest("请求体不是合法的 JSON，或字段类型与接口定义不符");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMethod(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(R.fail(HttpStatus.METHOD_NOT_ALLOWED.value(), "该接口不支持 " + e.getMethod() + " 方法"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(R.fail(HttpStatus.NOT_FOUND.value(), "接口不存在：" + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnknown(Exception e, HttpServletRequest request) {
        log.error("未预期异常 {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(BizError.INTERNAL_ERROR.httpStatus(),
                        "服务器开小差了，请把响应头里的 X-Trace-Id 提供给运维"));
    }

    private static ResponseEntity<R<Void>> badRequest(String msg) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(R.fail(BizError.PARAM_INVALID.httpStatus(), msg));
    }

    private static HttpStatus statusOf(BizError error) {
        HttpStatus status = HttpStatus.resolve(error.httpStatus());
        return status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
    }

    private static String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private static String describe(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int dot = path.lastIndexOf('.');
        return (dot >= 0 ? path.substring(dot + 1) : path) + ": " + violation.getMessage();
    }
}
