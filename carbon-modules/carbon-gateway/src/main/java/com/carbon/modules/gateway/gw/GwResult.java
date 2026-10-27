package com.carbon.modules.gateway.gw;

/**
 * 网关统一响应。
 *
 * <p>为什么不直接用 {@code R<T>}：{@code R} 的 {@code code} 是 int、服务于管理端前端，
 * 随时可能为了贴合前端框架而调整；而这里是**对外契约**，一改就是全体接入方返工。
 *
 * <p>字段取舍：{@code providerStatus} 让业务方能自己区分「我调错了」和「第三方挂了」；
 * {@code costMs} 让业务方在排查超时时不必来问你；{@code traceId} 是唯一能把
 * 业务方截图和平台日志对上的东西，所以它在**成功响应里也必须出现**。
 *
 * @param code           语义码，{@code "0"} 表示成功
 * @param message        人读消息
 * @param data           业务数据（抽取结果）
 * @param traceId        链路 id
 * @param providerStatus 三方 HTTP 状态码，未发生请求时为 {@code null}
 * @param costMs         平台侧总耗时（含鉴权、渲染、重试）
 * @author carbon
 */
public record GwResult<T>(String code,
                          String message,
                          T data,
                          String traceId,
                          Integer providerStatus,
                          Long costMs) {

    public static <T> GwResult<T> ok(T data, String traceId, Integer providerStatus, long costMs) {
        return new GwResult<>(GwErrorCode.OK.code(), GwErrorCode.OK.defaultMessage(), data,
                traceId, providerStatus, costMs);
    }

    public static <T> GwResult<T> fail(GwErrorCode error, String message, String traceId) {
        return new GwResult<>(error.code(), message == null ? error.defaultMessage() : message,
                null, traceId, null, null);
    }
}
