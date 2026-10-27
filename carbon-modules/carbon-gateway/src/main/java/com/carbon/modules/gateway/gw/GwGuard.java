package com.carbon.modules.gateway.gw;

import com.carbon.common.web.config.TraceIdFilter;
import com.carbon.common.json.JsonUtils;
import com.carbon.modules.connector.domain.entity.Connector;
import com.carbon.modules.connector.service.IAppService;
import com.carbon.modules.connector.service.IConnectorService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 网关入口守卫：签名、防重放、IP、限流、应用授权。
 *
 * <p>为什么从 {@code GwController} 里抽出来：编排入口 {@code /gw/flows/{flowCode}}
 * 与连接器入口必须**逐条一致**地校验（同协议、同错误码、同拒绝顺序）。
 * 复制一份校验代码的结局是可预期的：改了一处忘了另一处，
 * 而漏洞恰好就出在被遗忘的那个入口上。
 *
 * <p>校验顺序按「越便宜越靠前」排，每条都能独立拒绝：
 * <ol>
 *   <li>appKey 存在且启用（一次索引查询）</li>
 *   <li>时间窗 ±5min（纯算术）</li>
 *   <li>签名（一次 HMAC）</li>
 *   <li>nonce 未用过（一次 Redis SET NX）</li>
 *   <li>IP 白名单（字符串比较）</li>
 *   <li>应用 QPS（一次 Redis INCR）</li>
 *   <li>连接器白名单（内存判断，调用方按需追加）</li>
 * </ol>
 *
 * <p>为什么不把 nonce 校验放到签名前面：那样未通过签名的垃圾请求也能把 Redis 写满，
 * 等于给了攻击者一个不需要密钥的放大攻击面。
 *
 * @author carbon
 */
@Component
public class GwGuard {

    public static final String HEADER_APP_KEY = "X-App-Key";
    public static final String HEADER_TIMESTAMP = "X-Timestamp";
    public static final String HEADER_NONCE = "X-Nonce";
    public static final String HEADER_SIGN = "X-Sign";
    public static final String HEADER_ENV = "X-Carbon-Env";

    private final IAppService appService;
    private final IConnectorService connectorService;
    private final NonceReplayGuard nonceReplayGuard;
    private final GwRateLimiter rateLimiter;

    public GwGuard(IAppService appService,
                   IConnectorService connectorService,
                   NonceReplayGuard nonceReplayGuard,
                   GwRateLimiter rateLimiter) {
        this.appService = appService;
        this.connectorService = connectorService;
        this.nonceReplayGuard = nonceReplayGuard;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 通过校验的调用方。
     *
     * @param appKey     应用标识（写入运行记录的 {@code trigger_by}）
     * @param appId      应用 id（写入调用日志的 {@code app_id}）
     * @param envCode    请求头 {@code X-Carbon-Env}，可为 {@code null}
     * @param credential 明文凭证（白名单判断要用）
     */
    public record Caller(String appKey, Long appId, String envCode, IAppService.AppCredential credential) {
    }

    /**
     * 连接器白名单：应用的 {@code allowed_connectors} 为空表示「不限」。
     */
    public void requireConnectorAllowed(Caller caller, String connectorCode) {
        List<Long> allowed = parseAllowed(caller.credential().app().getAllowedConnectors());
        if (allowed.isEmpty()) {
            return;
        }
        Connector connector;
        try {
            connector = connectorService.requireByCode(connectorCode);
        } catch (RuntimeException e) {
            // 连接器不存在时交给下游的 CONNECTOR_NOT_FOUND，这里只负责「授权判断」
            return;
        }
        if (!allowed.contains(connector.getId())) {
            throw new GwRejectedException(GwErrorCode.APP_FORBIDDEN,
                    "应用未授权调用连接器：" + connectorCode);
        }
    }

    /**
     * 编排入口的授权：按流程**实际引用到的**连接器逐个判断。
     *
     * <p>只校验「流程本身」是不够的：应用 A 的白名单里没有 connector9，
     * 却能通过一个引用了 connector9 的流程把数据取走，白名单就白做了。
     */
    public void requireFlowConnectorsAllowed(Caller caller, String flowCode, Collection<Long> connectorIds) {
        List<Long> allowed = parseAllowed(caller.credential().app().getAllowedConnectors());
        if (allowed.isEmpty() || connectorIds == null || connectorIds.isEmpty()) {
            return;
        }
        for (Long id : connectorIds) {
            if (!allowed.contains(id)) {
                throw new GwRejectedException(GwErrorCode.APP_FORBIDDEN,
                        "应用未授权调用流程 " + flowCode + " 所引用的连接器（id=" + id + "）");
            }
        }
    }

    /**
     * 逐条校验（不含连接器授权：那一步按入口不同分别调用）。
     *
     * @param connectorCode 为 {@code null} 时跳过连接器白名单（编排入口用）
     * @throws GwRejectedException 任一条不通过
     */
    public Caller verify(HttpServletRequest request, String rawBody, String connectorCode) {
        String appKey = request.getHeader(HEADER_APP_KEY);
        IAppService.AppCredential credential = appService.findByAppKey(appKey);
        if (credential == null) {
            throw new GwRejectedException(GwErrorCode.SIGN_INVALID, null);
        }

        String timestampHeader = request.getHeader(HEADER_TIMESTAMP);
        Long timestamp = parseLong(timestampHeader);
        String nonce = request.getHeader(HEADER_NONCE);
        String sign = request.getHeader(HEADER_SIGN);
        if (!SignatureVerifier.withinWindow(timestamp, System.currentTimeMillis(),
                SignatureVerifier.DEFAULT_WINDOW_MS)) {
            throw new GwRejectedException(GwErrorCode.SIGN_INVALID, "时间戳超出允许范围（±5 分钟）");
        }
        if (nonce == null || nonce.isBlank() || nonce.length() > 64) {
            throw new GwRejectedException(GwErrorCode.SIGN_INVALID, "nonce 缺失或超长");
        }

        String pathAndQuery = SignatureVerifier.pathAndQuery(request.getRequestURI(), queryParams(request));
        byte[] bodyBytes = rawBody == null ? new byte[0] : rawBody.getBytes(StandardCharsets.UTF_8);
        if (!SignatureVerifier.verify(credential.secret(), request.getMethod(), pathAndQuery,
                timestampHeader, nonce, bodyBytes, sign)) {
            throw new GwRejectedException(GwErrorCode.SIGN_INVALID, null);
        }

        if (!nonceReplayGuard.tryAcquire(appKey, nonce)) {
            throw new GwRejectedException(GwErrorCode.SIGN_INVALID, "nonce 已被使用（疑似重放）");
        }

        if (!ipAllowed(request.getRemoteAddr(), credential)) {
            throw new GwRejectedException(GwErrorCode.APP_FORBIDDEN, "来源 IP 不在白名单内");
        }
        Integer qps = credential.app().getRateLimitQps();
        if (!rateLimiter.tryAcquire(appKey, qps == null ? 50 : qps)) {
            throw new GwRejectedException(GwErrorCode.RATE_LIMITED, null);
        }

        Caller caller = new Caller(appKey, credential.app().getId(),
                request.getHeader(HEADER_ENV), credential);
        if (connectorCode != null) {
            requireConnectorAllowed(caller, connectorCode);
        }
        return caller;
    }

    /**
     * 顶层字段合并成 input：query 与 body 同名时 **body 优先**。
     *
     * <p>理由：只有 body 能表达类型（{@code {"n": 1}} 是数字，query 永远是字符串），
     * 显式写在 body 里的值理应更可信。
     */
    public static Map<String, Object> collectInput(HttpServletRequest request, String rawBody) {
        Map<String, Object> input = new LinkedHashMap<>();
        request.getParameterMap().forEach((key, values) -> {
            if (values != null && values.length > 0) {
                input.put(key, values.length == 1 ? values[0] : List.of(values));
            }
        });
        input.putAll(parseJsonObject(rawBody));
        return input;
    }

    private static Map<String, Object> parseJsonObject(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JsonUtils.parseMap(rawBody);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException e) {
            // 非 JSON body 交给下游的 PARAM_INVALID：网关这一层不做格式教育
            return Map.of();
        }
    }

    private static Map<String, List<String>> queryParams(HttpServletRequest request) {
        return request.getParameterMap().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        e -> List.of(e.getValue()), (a, b) -> a, LinkedHashMap::new));
    }

    private static List<Long> parseAllowed(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JsonUtils.mapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() { });
        } catch (Exception e) {
            // 配置脏了就当「没有任何授权」，比反过来安全
            return List.of(-1L);
        }
    }

    private static boolean ipAllowed(String remoteAddr, IAppService.AppCredential credential) {
        String json = credential.app().getIpWhitelist();
        if (json == null || json.isBlank()) {
            return true;
        }
        try {
            List<String> cidrs = JsonUtils.mapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
            return IpWhitelistMatcher.matches(remoteAddr, cidrs);
        } catch (Exception e) {
            return false;
        }
    }

    private static Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String currentTraceId() {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        return traceId == null ? UUID.randomUUID().toString().replace("-", "") : traceId;
    }
}
