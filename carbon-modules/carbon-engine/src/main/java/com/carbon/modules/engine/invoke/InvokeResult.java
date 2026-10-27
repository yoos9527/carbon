package com.carbon.modules.engine.invoke;

import java.util.Map;

/**
 * 试调结果。
 *
 * <p>字段设计围绕「排障时你最想先看到什么」：先看有没有成功、错在哪个阶段，
 * 再看三方原始返回，最后才看抽取结果。所以 {@code rawBody} 不做任何裁剪地给出，
 * 由前端决定折叠还是展开。
 *
 * @param traceId    本次调用链路 id，与日志、后续 invoke_log 对齐
 * @param success    是否成功（2xx 且抽取无 required 落空）
 * @param errorCode  {@code BizError} 名称，成功时为 {@code null}
 * @param errorMsg   面向使用者的错误描述
 * @param request    实际发出的请求（已脱敏），{@code dryRun} 时也返回它
 * @param status     三方 HTTP 状态码，未发出请求时为 {@code null}
 * @param rawBody    三方响应原文（超限时被截断并标记）
 * @param truncated  响应是否因超过上限被截断
 * @param extracted  按 responseExtract 抽出的字段
 * @param durationMs 端到端耗时（含鉴权取 token、重试）
 * @author carbon
 */
public record InvokeResult(String traceId,
                           boolean success,
                           String errorCode,
                           String errorMsg,
                           SentRequest request,
                           Integer status,
                           String rawBody,
                           boolean truncated,
                           Map<String, Object> extracted,
                           long durationMs) {

    /**
     * 实际发出的请求快照。
     *
     * <p>Header 里的鉴权值必须已脱敏——试调面板会把这份数据直接渲染给用户看，
     * 也是「一键复制 curl」的数据源，漏一个 header 就等于把密钥贴在了屏幕上。
     *
     * @param method  大写方法
     * @param url     渲染后的完整 URL（query 中敏感值已替换）
     * @param headers 渲染后的请求头（鉴权值已脱敏）
     * @param body    渲染后的请求体
     * @author carbon
     */
    public record SentRequest(String method, String url, Map<String, String> headers, String body) {
    }
}
