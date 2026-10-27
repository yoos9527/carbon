package com.carbon.modules.gateway.gw;

import com.carbon.common.core.exception.ServiceException;
import com.carbon.common.web.config.TraceIdFilter;
import com.carbon.framework.http.HttpResult;
import com.carbon.modules.connector.domain.entity.Connector;
import com.carbon.modules.connector.service.IAppService;
import com.carbon.modules.connector.service.IConnectorService;
import com.carbon.modules.invokelog.api.InvokeLogEvent;
import com.carbon.modules.engine.invoke.InvokeRequest;
import com.carbon.modules.engine.invoke.InvokeResult;
import com.carbon.modules.engine.invoke.InvokeService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对外网关入口：{@code /gw/{connectorCode}/{operationCode}}。
 *
 * <p>校验顺序是按「越便宜越靠前」排的，且每条都能独立拒绝：
 * <ol>
 *   <li>appKey 存在且启用（一次索引查询）</li>
 *   <li>时间窗 ±5min（纯算术）</li>
 *   <li>签名（一次 HMAC）</li>
 *   <li>nonce 未用过（一次 Redis SET NX）</li>
 *   <li>IP 白名单（字符串比较）</li>
 *   <li>应用 QPS（一次 Redis INCR）</li>
 *   <li>连接器白名单（内存判断）</li>
 * </ol>
 *
 * <p>为什么不把 nonce 校验放到签名前面：那样未通过签名的垃圾请求也能把 Redis 写满，
 * 等于给了攻击者一个不需要密钥的放大攻击面。
 *
 * <p>错误一律返回**同一个** {@code SIGN_INVALID}，不区分「appKey 不存在 / 签名错 / nonce 重放」——
 * 按错误类型给出差异化提示，等于送对方一个探测工具。
 *
 * @author carbon
 */
@RestController
@RequestMapping("/gw")
public class GwController {

    private static final String HEADER_APP_KEY = "X-App-Key";
    private static final String HEADER_TIMESTAMP = "X-Timestamp";
    private static final String HEADER_NONCE = "X-Nonce";
    private static final String HEADER_SIGN = "X-Sign";
    private static final String HEADER_ENV = "X-Carbon-Env";

    private final InvokeService invokeService;
    private final IAppService appService;
    private final IConnectorService connectorService;
    private final NonceReplayGuard nonceReplayGuard;
    private final GwRateLimiter rateLimiter;

    public GwController(InvokeService invokeService,
                        IAppService appService,
                        IConnectorService connectorService,
                        NonceReplayGuard nonceReplayGuard,
                        GwRateLimiter rateLimiter) {
        this.invokeService = invokeService;
        this.appService = appService;
        this.connectorService = connectorService;
        this.nonceReplayGuard = nonceReplayGuard;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 网关调用。GET 与 POST 共用同一个入口：签名协议里 body 参与摘要，两者没有本质区别。
     */
    @RequestMapping("/{connectorCode}/{operationCode}")
    public ResponseEntity<GwResult<Map<String, Object>>> invoke(
            @PathVariable String connectorCode,
            @PathVariable String operationCode,
            HttpServletRequest request,
            @RequestBody(required = false) String rawBody) {

        String traceId = currentTraceId();
        long startedAt = System.currentTimeMillis();

        String appKey = request.getHeader(HEADER_APP_KEY);
        IAppService.AppCredential credential = appService.findByAppKey(appKey);
        if (credential == null) {
            return fail(GwErrorCode.SIGN_INVALID, null, traceId);
        }

        Long timestamp = parseLong(request.getHeader(HEADER_TIMESTAMP));
        String nonce = request.getHeader(HEADER_NONCE);
        String sign = request.getHeader(HEADER_SIGN);
        if (!SignatureVerifier.withinWindow(timestamp, System.currentTimeMillis(),
                SignatureVerifier.DEFAULT_WINDOW_MS)) {
            return fail(GwErrorCode.SIGN_INVALID, "时间戳超出允许范围（±5 分钟）", traceId);
        }

        String pathAndQuery = SignatureVerifier.pathAndQuery(request.getRequestURI(), queryParams(request));
        byte[] bodyBytes = rawBody == null ? new byte[0] : rawBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (!SignatureVerifier.verify(credential.secret(), request.getMethod(), pathAndQuery,
                request.getHeader(HEADER_TIMESTAMP), nonce, bodyBytes, sign)) {
            return fail(GwErrorCode.SIGN_INVALID, null, traceId);
        }

        if (nonce == null || nonce.isBlank() || nonce.length() > 64) {
            return fail(GwErrorCode.SIGN_INVALID, "nonce 缺失或超长", traceId);
        }
        if (!nonceReplayGuard.tryAcquire(appKey, nonce)) {
            return fail(GwErrorCode.SIGN_INVALID, "nonce 已被使用（疑似重放）", traceId);
        }

        if (!ipAllowed(request.getRemoteAddr(), credential)) {
            return fail(GwErrorCode.APP_FORBIDDEN, "来源 IP 不在白名单内", traceId);
        }
        if (!rateLimiter.tryAcquire(appKey, credential.app().getRateLimitQps() == null
                ? 50 : credential.app().getRateLimitQps())) {
            return fail(GwErrorCode.RATE_LIMITED, null, traceId);
        }
        if (!connectorAllowed(connectorCode, credential)) {
            return fail(GwErrorCode.APP_FORBIDDEN, "应用未授权调用连接器：" + connectorCode, traceId);
        }

        // 网关只做变量填充：query 与 body 顶层字段合并成 input，同名时 body 优先
        Map<String, Object> input = new LinkedHashMap<>(collectQueryInput(request));
        input.putAll(parseJsonObject(rawBody));

        try {
            InvokeResult result = invokeService.invoke(new InvokeRequest(
                    connectorCode, operationCode, input,
                    request.getHeader(HEADER_ENV), Map.of(), false,
                    InvokeLogEvent.SOURCE_GATEWAY, credential.app().getId()));
            return ResponseEntity.ok(GwResult.ok(result.extracted(), traceId,
                    result.status(), System.currentTimeMillis() - startedAt));
        } catch (ServiceException e) {
            GwErrorCode code = GwErrorCode.from(e.error());
            return ResponseEntity.status(code.httpStatus())
                    .body(GwResult.fail(code, e.getMessage(), traceId));
        } catch (RuntimeException e) {
            return fail(GwErrorCode.INTERNAL_ERROR, e.getMessage(), traceId);
        }
    }

    private ResponseEntity<GwResult<Map<String, Object>>> fail(GwErrorCode code, String message, String traceId) {
        return ResponseEntity.status(code.httpStatus()).body(GwResult.fail(code, message, traceId));
    }

    private boolean connectorAllowed(String connectorCode, IAppService.AppCredential credential) {
        List<Long> allowed = parseAllowed(credential.app().getAllowedConnectors());
        if (allowed.isEmpty()) {
            return true;
        }
        Connector connector;
        try {
            connector = connectorService.requireByCode(connectorCode);
        } catch (RuntimeException e) {
            // 连接器不存在时交给下游的 CONNECTOR_NOT_FOUND，这里只负责「授权判断」
            return true;
        }
        return allowed.contains(connector.getId());
    }

    private List<Long> parseAllowed(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return com.carbon.common.json.JsonUtils.mapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() { });
        } catch (Exception e) {
            // 配置脏了就当「没有任何授权」，比反过来安全
            return List.of(-1L);
        }
    }

    private boolean ipAllowed(String remoteAddr, IAppService.AppCredential credential) {
        String json = credential.app().getIpWhitelist();
        if (json == null || json.isBlank()) {
            return true;
        }
        try {
            List<String> cidrs = com.carbon.common.json.JsonUtils.mapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
            return IpWhitelistMatcher.matches(remoteAddr, cidrs);
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, List<String>> queryParams(HttpServletRequest request) {
        return request.getParameterMap().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        e -> List.of(e.getValue()), (a, b) -> a, LinkedHashMap::new));
    }

    private Map<String, Object> collectQueryInput(HttpServletRequest request) {
        Map<String, Object> input = new LinkedHashMap<>();
        request.getParameterMap().forEach((key, values) -> {
            if (values != null && values.length > 0) {
                input.put(key, values.length == 1 ? values[0] : List.of(values));
            }
        });
        return input;
    }

    private Map<String, Object> parseJsonObject(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = com.carbon.common.json.JsonUtils.parseMap(rawBody);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String currentTraceId() {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        return traceId == null ? UUID.randomUUID().toString().replace("-", "") : traceId;
    }
}
