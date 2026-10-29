package com.carbon.modules.engine.invoke;

import com.carbon.modules.invokelog.api.InvokeLogEvent;
import com.carbon.modules.invokelog.api.InvokeLogSink;

import com.carbon.common.core.enums.BizError;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.common.json.JsonUtils;
import com.carbon.framework.expression.RenderContext;
import com.carbon.framework.expression.TemplateRenderer;
import com.carbon.framework.http.Backoff;
import com.carbon.framework.http.HttpExecutor;
import com.carbon.framework.http.HttpRequestSpec;
import com.carbon.framework.http.HttpResult;
import com.carbon.framework.http.RetryPolicy;
import com.carbon.framework.script.GroovySandbox;
import com.carbon.framework.script.ScriptResult;
import com.carbon.modules.connector.domain.entity.Connector;
import com.carbon.modules.connector.domain.entity.ConnectorOperation;
import com.carbon.modules.connector.service.IConnectorService;
import com.carbon.modules.connector.service.IEnvService;
import com.carbon.modules.engine.credential.CredentialResolver;
import com.carbon.modules.partner.domain.entity.Partner;
import com.carbon.modules.partner.service.ICredentialService;
import com.carbon.modules.partner.service.IPartnerService;
import com.carbon.modules.engine.extract.ResponseExtractor;
import com.carbon.common.sensitive.MaskingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 调用执行流水线（试调、网关、编排共用同一条）。
 *
 * <p>九步顺序不能改，每一步的失败都映射到固定的错误码：
 * <pre>
 *  1. 取连接器/接口        → CONNECTOR_NOT_FOUND / OPERATION_NOT_FOUND
 *  2. 取环境变量           → PARAM_INVALID
 *  3. 解析鉴权（可能换 token）→ AUTH_FAILED
 *  4. 前置脚本             → SCRIPT_ERROR
 *  5. 渲染 URL/Header/Body → PARAM_INVALID（模板错误）
 *  6. 发请求（含重试）      → PROVIDER_TIMEOUT / PROVIDER_HTTP_4XX / PROVIDER_HTTP_5XX
 *  7. 后置脚本             → SCRIPT_ERROR
 *  8. 响应抽取             → EXTRACT_FAILED
 *  9. 组装结果 + 脱敏       → 不会失败
 * </pre>
 *
 * <p><b>为什么脚本在渲染之前</b>：脚本的职责是「改入参」，用户最常见的用法就是
 * 「补一个时间戳、算个签名、把金额从元转成分」。渲染之后再跑脚本，改的就是最终报文，
 * 那等于给了用户一个可以随意改 URL 的后门。
 *
 * @author carbon
 */
@Service
public class InvokeService {

    private static final Logger log = LoggerFactory.getLogger(InvokeService.class);

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 3_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 10_000;
    /** 从连接池取连接的最长等待；超过就快速失败，而不是把 Tomcat 线程堆在这 */
    private static final int DEFAULT_POOL_TIMEOUT_MS = 500;
    private static final String TRUNCATED_SUFFIX = "...__TRUNCATED__";

    private final IConnectorService connectorService;
    private final IEnvService envService;
    private final TemplateRenderer templateRenderer;
    private final HttpExecutor httpExecutor;
    private final GroovySandbox groovySandbox;
    private final CredentialResolver credentialResolver;
    private final IPartnerService partnerService;
    private final ICredentialService credentialService;
    /** 日志落库是旁路：没有实现（单测、纯引擎部署）就是空列表，不写条件分支 */
    private final List<InvokeLogSink> logSinks;

    public InvokeService(IConnectorService connectorService,
                         IEnvService envService,
                         TemplateRenderer templateRenderer,
                         HttpExecutor httpExecutor,
                         GroovySandbox groovySandbox,
                         CredentialResolver credentialResolver,
                         IPartnerService partnerService,
                         ICredentialService credentialService,
                         List<InvokeLogSink> logSinks) {
        this.connectorService = connectorService;
        this.envService = envService;
        this.templateRenderer = templateRenderer;
        this.httpExecutor = httpExecutor;
        this.groovySandbox = groovySandbox;
        this.credentialResolver = credentialResolver;
        this.partnerService = partnerService;
        this.credentialService = credentialService;
        this.logSinks = logSinks == null ? List.of() : List.copyOf(logSinks);
    }

    /**
     * 执行一次调用。
     *
     * <p>日志在 {@code finally} 里写：失败路径占这类平台的绝大多数问题单，
     * 「只记成功」的日志等于没有日志。这里刻意不在 catch 里 return 包装结果——
     * 失败必须原样抛出，让调用方（试调面板 / 网关）自己决定怎么表达错误。
     */
    public InvokeResult invoke(InvokeRequest request) {
        String traceId = newTraceId();
        long startNanos = System.nanoTime();
        LogDraft draft = new LogDraft(request.source(), request.appId());
        try {
            return run(request, traceId, startNanos, draft);
        } catch (RuntimeException e) {
            draft.fail(e);
            throw e;
        } finally {
            emitLog(traceId, startNanos, draft);
        }
    }

    private InvokeResult run(InvokeRequest request, String traceId, long startNanos, LogDraft draft) {
        Connector connector = connectorService.requireByCode(request.connectorCode());
        draft.connectorId = connector.getId();

        // 校验顺序是刻意的：合作方（商务主体）→ 接入点（地址）→ 接口（能力）。
        // 反过来会让人对着「接口已停用」去改接口，而真正的原因是那家公司被拉黑了。
        if (connector.getPartnerId() != null) {
            Partner partner = partnerService.requireById(connector.getPartnerId());
            draft.partnerId = partner.getId();
            if (partner.getStatus() == null || partner.getStatus() != 1) {
                throw ServiceException.of(BizError.PARTNER_DISABLED, "合作方已停用：" + partner.getName());
            }
        }
        if (connector.getStatus() == null || connector.getStatus() != 1) {
            throw ServiceException.of(BizError.ENDPOINT_DISABLED, "接入点已停用：" + connector.getCode());
        }
        ConnectorOperation operation = connectorService.requireOperation(connector.getId(), request.operationCode());
        draft.operationId = operation.getId();
        if (operation.getStatus() != null && operation.getStatus() != 1) {
            throw ServiceException.of(BizError.OPERATION_DISABLED, "接口已停用：" + operation.getCode());
        }

        Map<String, Object> envVars = envService.varsOf(request.envCode());
        // 凭证沿「接口覆盖 → 接入点 → 合作方默认」解析；解析不到就是「不鉴权」，不是错误
        ICredentialService.Resolved credential = credentialService.resolve(connector, operation);
        draft.partnerCredentialId = credential.id();
        draft.credentialType = credential.type();
        Map<String, Object> credentialPlain = credential.config() == null ? Map.of() : credential.config();

        // 脚本可见的 ctx：input / vars / auth / env 四个作用域。
        // 作用域名仍然叫 auth：线上已有 Groovy 脚本用的是 ctx.auth，改名字会静默地把它们弄坏。
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("input", new LinkedHashMap<>(request.input()));
        ctx.put("vars", new LinkedHashMap<String, Object>());
        ctx.put("auth", new LinkedHashMap<>(credentialPlain));
        ctx.put("env", envVars);

        runScript(operation.getPreScript(), ctx, traceId, operation.getScriptTimeoutMs());

        RenderContext renderContext = RenderContext.create();
        asMap(ctx.get("input")).forEach(renderContext::input);
        asMap(ctx.get("vars")).forEach(renderContext::var);
        asMap(ctx.get("auth")).forEach(renderContext::auth);
        asMap(ctx.get("env")).forEach(renderContext::env);

        CredentialResolver.CredentialOutcome credentialOutcome;
        try {
            credentialOutcome = credential.authenticated()
                    ? credentialResolver.resolve(credential.id(), credential.type(), credentialPlain, httpExecutor)
                    : CredentialResolver.CredentialOutcome.EMPTY;
        } catch (ServiceException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ServiceException.of(BizError.AUTH_FAILED, "凭证解析失败：" + e.getMessage());
        }

        String url = buildUrl(connector, operation, renderContext, credentialOutcome);
        Map<String, String> headers = buildHeaders(connector, operation, renderContext, credentialOutcome, request);
        String body = buildBody(operation, renderContext);

        draft.method = operation.getMethod().toUpperCase();
        InvokeResult.SentRequest sent = new InvokeResult.SentRequest(
                operation.getMethod().toUpperCase(),
                MaskingUtils.maskUrl(url),
                MaskingUtils.maskHeaders(headers, credentialOutcome.sensitiveKeys()),
                MaskingUtils.maskJson(body));

        draft.realUrl = sent.url();
        draft.reqHeaders = sent.headers();
        draft.reqBody = sent.body();

        if (request.dryRun()) {
            return new InvokeResult(traceId, true, null, null, sent, null, null, false,
                    Map.of(), elapsedMs(startNanos));
        }

        HttpRequestSpec spec = buildSpec(operation, connector, url, headers, body);
        HttpResult result;
        try {
            result = httpExecutor.execute(spec);
        } catch (ServiceException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ServiceException.of(BizError.INTERNAL_ERROR, "请求执行异常：" + e.getMessage());
        }

        // 后置脚本能看到响应（脱敏前），用来处理「三方用非 2xx 表达业务失败」这类情况
        ctx.put("response", Map.of(
                "status", result.getStatus(),
                "headers", result.getHeaders(),
                "body", safeParse(result.getBody())));
        runScript(operation.getPostScript(), ctx, traceId, operation.getScriptTimeoutMs());

        draft.respStatus = result.getStatus();
        draft.respBody = truncate(MaskingUtils.maskJson(result.getBody()));
        draft.truncated = result.isTruncated();

        if (result.getStatus() / 100 != 2) {
            BizError error = result.getStatus() >= 500 ? BizError.PROVIDER_HTTP_5XX : BizError.PROVIDER_HTTP_4XX;
            String maskedBody = draft.respBody;
            throw ServiceException.of(error, "三方返回 HTTP " + result.getStatus() + "：" + maskedBody);
        }

        List<ResponseExtractor.Rule> rules = ResponseExtractor.parseRules(operation.getResponseExtract());
        Map<String, Object> extracted = ResponseExtractor.extract(result.getBody(), rules);

        long duration = elapsedMs(startNanos);
        log.info("试调完成 traceId={} {} {} status={} cost={}ms", traceId, sent.method(), sent.url(),
                result.getStatus(), duration);

        return new InvokeResult(traceId, true, null, null, sent, result.getStatus(),
                truncate(result.getBody()), result.isTruncated(), extracted, duration);
    }

    // ------------------------------------------------------------------ 日志

    private void emitLog(String traceId, long startNanos, LogDraft draft) {
        if (logSinks.isEmpty()) {
            return;
        }
        try {
            InvokeLogEvent event = new InvokeLogEvent(traceId, draft.source, draft.appId, null,
                    draft.connectorId, draft.operationId, draft.method, draft.realUrl,
                    draft.reqHeaders, draft.reqBody, draft.respStatus, draft.respBody,
                    draft.truncated, elapsedMs(startNanos), draft.errCode, draft.errMsg,
                    draft.partnerId, draft.partnerCredentialId, draft.credentialType);
            for (InvokeLogSink sink : logSinks) {
                sink.record(event);
            }
        } catch (RuntimeException e) {
            // 审计旁路自己出问题，不能影响已经成功的业务调用
            log.warn("写调用日志失败 traceId={} cause={}", traceId, e.toString());
        }
    }

    /**
     * 日志要用的可变中间态。
     *
     * <p>用一个可变的 draft 而不是把 12 个值从 {@code run()} 里一路 return 回来：
     * 失败时这些值拿不到返回值，只能靠 draft 带出来。
     */
    private static final class LogDraft {
        private final String source;
        private final Long appId;
        private Long connectorId;
        private Long operationId;
        private Long partnerId;
        private Long partnerCredentialId;
        private String credentialType;
        private String method;
        private String realUrl;
        private Map<String, String> reqHeaders;
        private String reqBody;
        private Integer respStatus;
        private String respBody;
        private boolean truncated;
        private String errCode;
        private String errMsg;

        private LogDraft(String source, Long appId) {
            this.source = source == null ? InvokeLogEvent.SOURCE_CONSOLE : source;
            this.appId = appId;
        }

        private void fail(RuntimeException e) {
            if (e instanceof ServiceException se) {
                this.errCode = se.error().name();
            } else {
                this.errCode = BizError.INTERNAL_ERROR.name();
            }
            this.errMsg = truncateForLog(e.getMessage(), 900);
        }
    }

    /** 错误消息截到 900 字符：{@code err_msg} 列是 VARCHAR(1024)，按字符留余量，别让 DB 抛截断错 */
    private static String truncateForLog(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    // ------------------------------------------------------------------ 渲染

    private String buildUrl(Connector connector,
                            ConnectorOperation operation,
                            RenderContext context,
                            CredentialResolver.CredentialOutcome credentialOutcome) {
        String base = templateRenderer.renderText(connector.getBaseUrl(), context);
        String path = templateRenderer.renderText(operation.getPath(), context);
        String url = joinUrl(base, path);

        Map<String, String> query = new LinkedHashMap<>();
        if (operation.getQueryParams() != null && !operation.getQueryParams().isBlank()) {
            JsonUtils.parseMap(operation.getQueryParams()).forEach((key, value) ->
                    query.put(key, urlEncode(templateRenderer.renderText(String.valueOf(value), context))));
        }
        query.putAll(credentialOutcome.queryParams());

        if (query.isEmpty()) {
            return url;
        }
        StringBuilder sb = new StringBuilder(url);
        sb.append(url.contains("?") ? "&" : "?");
        boolean first = true;
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            sb.append(urlEncode(entry.getKey())).append('=').append(entry.getValue() == null ? "" : entry.getValue());
            first = false;
        }
        return sb.toString();
    }

    /**
     * 拼接 baseUrl 与 path。
     *
     * <p>三条规则：path 是绝对 URL 时直接用它（少数三方给的是一整条回调地址）；
     * base 以 {@code /} 结尾或 path 以 {@code /} 开头时只保留一个斜杠；
     * 其余情况补一个斜杠。
     *
     * <p>path 的每一段单独做 URL 编码：既保证占位符里的值（订单号、邮箱）不会把
     * 路径结构搞乱，又不会把 {@code /} 本身编成 {@code %2F}。
     */
    private String joinUrl(String base, String path) {
        if (path == null || path.isBlank()) {
            return base;
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        String encodedPath = encodePath(path);
        String normalizedBase = base == null ? "" : base;
        if (normalizedBase.endsWith("/") && encodedPath.startsWith("/")) {
            return normalizedBase + encodedPath.substring(1);
        }
        if (!normalizedBase.endsWith("/") && !encodedPath.startsWith("/")) {
            return normalizedBase + "/" + encodedPath;
        }
        return normalizedBase + encodedPath;
    }

    private String encodePath(String path) {
        String[] segments = path.split("/", -1);
        StringBuilder sb = new StringBuilder(path.length() + 16);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(urlEncode(segments[i]));
        }
        return sb.toString();
    }

    private Map<String, String> buildHeaders(Connector connector,
                                             ConnectorOperation operation,
                                             RenderContext context,
                                             CredentialResolver.CredentialOutcome credentialOutcome,
                                             InvokeRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (connector.getDefaultHeaders() != null && !connector.getDefaultHeaders().isBlank()) {
            JsonUtils.parseMap(connector.getDefaultHeaders()).forEach((key, value) ->
                    headers.put(key, templateRenderer.renderText(String.valueOf(value), context)));
        }
        if (operation.getHeaders() != null && !operation.getHeaders().isBlank()) {
            JsonUtils.parseMap(operation.getHeaders()).forEach((key, value) ->
                    headers.put(key, templateRenderer.renderText(String.valueOf(value), context)));
        }
        headers.putAll(credentialOutcome.headers());
        if (request.headerOverrides() != null) {
            request.headerOverrides().forEach((key, value) -> {
                if (key != null && value != null) {
                    headers.put(key, value);
                }
            });
        }
        String contentType = contentTypeOf(operation.getBodyType());
        if (contentType != null) {
            headers.putIfAbsent("Content-Type", contentType);
        }
        headers.putIfAbsent("Accept", "application/json");
        return headers;
    }

    private String buildBody(ConnectorOperation operation, RenderContext context) {
        String bodyType = operation.getBodyType() == null ? "none" : operation.getBodyType();
        String template = operation.getBodyTemplate();
        if ("none".equals(bodyType) || template == null || template.isBlank()) {
            return null;
        }
        if ("json".equals(bodyType)) {
            return templateRenderer.renderJson(template, context);
        }
        if ("form".equals(bodyType) || "urlencoded".equals(bodyType)) {
            // 配置侧仍然写 JSON（可读、可校验），发出去时拍平成 form 编码
            String renderedJson = templateRenderer.renderJson(template, context);
            Map<String, Object> map = JsonUtils.parseMap(renderedJson);
            StringBuilder sb = new StringBuilder();
            map.forEach((key, value) -> {
                if (!sb.isEmpty()) {
                    sb.append('&');
                }
                sb.append(urlEncode(key)).append('=');
                if (value != null) {
                    sb.append(urlEncode(String.valueOf(value)));
                }
            });
            return sb.toString();
        }
        return templateRenderer.renderText(template, context);
    }

    private String contentTypeOf(String bodyType) {
        if (bodyType == null) {
            return null;
        }
        return switch (bodyType) {
            case "json" -> "application/json";
            case "form" -> "multipart/form-data";
            case "urlencoded" -> "application/x-www-form-urlencoded";
            case "raw" -> "text/plain";
            default -> null;
        };
    }

    private HttpRequestSpec buildSpec(ConnectorOperation operation,
                                      Connector connector,
                                      String url,
                                      Map<String, String> headers,
                                      String body) {
        int timeout = operation.getTimeoutMs() != null ? operation.getTimeoutMs()
                : connector.getTimeoutMs() != null ? connector.getTimeoutMs() : DEFAULT_READ_TIMEOUT_MS;

        HttpRequestSpec.Builder builder = HttpRequestSpec.builder(operation.getMethod().toUpperCase(), url)
                .headers(headers)
                .timeouts(DEFAULT_CONNECT_TIMEOUT_MS, timeout);

        if (body != null) {
            builder.body(body);
            String contentType = headers.get("Content-Type");
            if (contentType != null) {
                builder.contentType(contentType);
            }
        }

        RetryPolicy policy = retryPolicyOf(operation.getMethod(), operation.getRetry(), connector.getRetry());
        if (policy != null) {
            builder.retryPolicy(policy);
        }
        return builder.build();
    }

    /**
     * 重试策略：接口级优先，其次连接器级，都没有就不重试。
     *
     * <p>非幂等方法（POST/PATCH）即便配了次数，也要显式把 {@code idempotent=true} 打开才真正重试——
     * 编排里一次误重试就是一笔重复下单。
     */
    private RetryPolicy retryPolicyOf(String method, String operationRetry, String connectorRetry) {
        String json = operationRetry != null && !operationRetry.isBlank() ? operationRetry : connectorRetry;
        if (json == null || json.isBlank()) {
            return null;
        }
        Map<String, Object> cfg = JsonUtils.parseMap(json);
        int maxAttempts = intOf(cfg.get("maxAttempts"), 0);
        if (maxAttempts <= 0) {
            return null;
        }
        long backoffMs = longOf(cfg.get("backoffMs"), 200L);
        boolean idempotent = Boolean.TRUE.equals(cfg.get("idempotent"))
                || RetryPolicy.isIdempotentMethod(method);
        RetryPolicy.Builder builder = RetryPolicy.builder()
                .maxAttempts(maxAttempts)
                .idempotent(idempotent)
                .backoff(new Backoff(backoffMs, 1.0d, backoffMs));
        if (cfg.get("retryOn") instanceof List<?> statuses) {
            Set<Integer> codes = new java.util.HashSet<>();
            statuses.forEach(s -> {
                if (s instanceof Number n) {
                    codes.add(n.intValue());
                }
            });
            if (!codes.isEmpty()) {
                builder.retryStatus(codes);
            }
        }
        return builder.build();
    }

    // ------------------------------------------------------------------ 脚本

    private void runScript(String script, Map<String, Object> ctx, String traceId, Integer timeoutMs) {
        if (script == null || script.isBlank()) {
            return;
        }
        if (groovySandbox == null) {
            throw ServiceException.of(BizError.SCRIPT_ERROR, "脚本引擎未装配，无法执行脚本");
        }
        try {
            ScriptResult result = groovySandbox.execute(script, ctx, traceId);
            // 脚本可以整体替换 ctx 的顶层字段（约定：只允许改值，不新增作用域）
            Map<String, Object> after = result.ctx();
            if (after != null) {
                if (after.get("input") instanceof Map<?, ?> input) {
                    ctx.put("input", new LinkedHashMap<>(castMap(input)));
                }
                if (after.get("vars") instanceof Map<?, ?> vars) {
                    ctx.put("vars", new LinkedHashMap<>(castMap(vars)));
                }
            }
        } catch (ServiceException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ServiceException.of(BizError.SCRIPT_ERROR, "脚本执行失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 工具

    private String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private String urlEncode(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String truncate(String body) {
        if (body == null) {
            return null;
        }
        // 64KB 与 invoke_log 的列宽一致；这里是「展示用」截断，落库另有 ResponseTruncator
        int max = 64 * 1024;
        return body.length() <= max ? body : body.substring(0, max) + TRUNCATED_SUFFIX;
    }

    private Object safeParse(String body) {
        try {
            return JsonUtils.parseMap(body);
        } catch (Exception e) {
            return body;
        }
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return castMap(map);
        }
        return Map.of();
    }

    private Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private int intOf(Object value, int defaultValue) {
        return value instanceof Number n ? n.intValue() : defaultValue;
    }

    private long longOf(Object value, long defaultValue) {
        return value instanceof Number n ? n.longValue() : defaultValue;
    }
}
