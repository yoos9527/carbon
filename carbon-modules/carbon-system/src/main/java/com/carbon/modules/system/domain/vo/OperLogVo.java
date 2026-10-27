package com.carbon.modules.system.domain.vo;

import com.carbon.common.log.domain.SysOperLog;

import java.time.LocalDateTime;

/**
 * 操作日志列表项。
 *
 * <p>直接复用实体字段而不另建映射：这张表就是给人看的，没有需要隐藏的列
 * （敏感内容在写入时已经打码，见 {@code LogAspect}）。再套一层 VO 只会多一份要同步的字段表。
 *
 * @author carbon
 */
public record OperLogVo(Long id,
                        String title,
                        String businessType,
                        String method,
                        String requestMethod,
                        String requestUri,
                        String clientIp,
                        String userAgent,
                        String params,
                        Integer status,
                        String errorMsg,
                        Long durationMs,
                        LocalDateTime createdAt) {

    public static OperLogVo of(SysOperLog e) {
        return new OperLogVo(e.getId(), e.getTitle(), e.getBusinessType(), e.getMethod(),
                e.getRequestMethod(), e.getRequestUri(), e.getClientIp(), e.getUserAgent(),
                e.getParams(), e.getStatus(), e.getErrorMsg(), e.getDurationMs(), e.getCreatedAt());
    }
}
