package com.carbon.modules.invokelog.api;

import java.util.Map;

/**
 * 一条调用日志。
 *
 * <p>字段与 `invoke_log` 表一一对应，**没有** {@code success}：成功或失败由
 * {@link #failed()} 从 {@code errCode} 推导。多存一个布尔列，就多一处「errCode 有值但
 * success=true」的自相矛盾可能。
 *
 * <p>{@code reqBody}/{@code respBody} 已经在引擎侧脱敏并截断过，落库实现**不要再动它们**：
 * 日志里出现明文密钥是这类平台最常见的事故，脱敏只放在一个地方才守得住。
 *
 * @param traceId     链路 id
 * @param source      来源：{@code CONSOLE} / {@code GW} / {@code FLOW}
 * @param appId       调用方应用（仅网关有）
 * @param flowRunId   编排执行 id（仅编排有）
 * @param connectorId 连接器 id
 * @param operationId 接口 id
 * @param method      实际发出的 HTTP 方法
 * @param realUrl     脱敏后的完整 URL
 * @param reqHeaders  脱敏后的请求头
 * @param reqBody     渲染后的请求体（已截断）
 * @param respStatus  三方状态码
 * @param respBody    三方响应体（已截断）
 * @param truncated   是否发生过截断
 * @param durationMs  平台侧总耗时
 * @param errCode     语义错误码，成功时为 {@code null}
 * @param errMsg      错误消息（给使用者看的中文句子）
 * @author carbon
 */
public record InvokeLogEvent(String traceId,
                             String source,
                             Long appId,
                             Long flowRunId,
                             Long connectorId,
                             Long operationId,
                             String method,
                             String realUrl,
                             Map<String, String> reqHeaders,
                             String reqBody,
                             Integer respStatus,
                             String respBody,
                             boolean truncated,
                             long durationMs,
                             String errCode,
                             String errMsg) {

    public static final String SOURCE_CONSOLE = "CONSOLE";
    public static final String SOURCE_GATEWAY = "GW";
    public static final String SOURCE_FLOW = "FLOW";

    public boolean failed() {
        return errCode != null;
    }
}
