package com.carbon.modules.engine.auth;

import com.carbon.common.core.enums.BizError;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.framework.http.HttpExecutor;
import com.carbon.framework.http.HttpRequestSpec;
import com.carbon.framework.http.HttpResult;
import com.carbon.common.json.JsonUtils;
import com.jayway.jsonpath.JsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 鉴权解析：把连接器的鉴权配置变成「这次请求要加的 header / query」。
 *
 * <p>六种类型里前四种是纯本地计算（api_key / basic / bearer / none），
 * 后两种要先去三方换 token（oauth2_cc / login_token），因此需要缓存与单飞。
 *
 * <p><b>单飞（single-flight）为什么必须做</b>：缓存过期那一刻，10 个并发请求会同时发现「没 token」，
 * 如果不加锁就会并发打 10 次 token 接口。三方侧往往对 token 接口有严格限流，
 * 这一下就把自己封了。这里用「按 key 分段的 synchronized + 双重检查」解决，
 * 同一连接器永远只有一个线程在换 token。
 *
 * @author carbon
 */
public class AuthResolver {

    private static final Logger log = LoggerFactory.getLogger(AuthResolver.class);

    /** token 提前过期的安全余量，避免「刚取到就过期」 */
    private static final long EXPIRE_SAFETY_MS = 60_000L;

    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    /**
     * 计算本次请求的鉴权产物。
     *
     * @param connectorId 用于 token 缓存分段的 key
     * @param authType    {@code null} 视为 none
     * @param config      **明文**配置（已由 {@code AuthConfigCodec} 解密）
     * @param http        换 token 要用；纯本地类型可以为 {@code null}
     */
    public AuthOutcome resolve(Long connectorId, String authType, Map<String, Object> config, HttpExecutor http) {
        if (authType == null || "none".equals(authType)) {
            return AuthOutcome.EMPTY;
        }
        Map<String, Object> cfg = config == null ? Map.of() : config;
        return switch (authType) {
            case "api_key" -> apiKey(cfg);
            case "basic" -> basic(cfg);
            case "bearer" -> bearer(cfg);
            case "oauth2_cc" -> fromCache(connectorId + ":oauth2_cc", () -> fetchOAuth2(connectorId, cfg, http));
            case "login_token" -> fromCache(connectorId + ":login_token", () -> fetchLoginToken(connectorId, cfg, http));
            default -> throw ServiceException.of(BizError.PARAM_INVALID, "不支持的鉴权类型：" + authType);
        };
    }

    /** 缓存失效（连接器配置变更时由管理端调用） */
    public void invalidate(Long connectorId) {
        cache.keySet().removeIf(k -> k.startsWith(connectorId + ":"));
    }

    public void clear() {
        cache.clear();
    }

    // ------------------------------------------------------------------ 纯本地类型

    private AuthOutcome apiKey(Map<String, Object> cfg) {
        String name = str(cfg, "name", "X-Api-Key");
        String value = str(cfg, "value", null);
        if (value == null || value.isBlank()) {
            throw ServiceException.of(BizError.AUTH_FAILED, "api_key 鉴权缺少 value");
        }
        String in = str(cfg, "in", "header");
        if ("query".equalsIgnoreCase(in)) {
            return new AuthOutcome(Map.of(), Map.of(name, value), Set.of());
        }
        return new AuthOutcome(Map.of(name, value), Map.of(), Set.of(name));
    }

    private AuthOutcome basic(Map<String, Object> cfg) {
        String username = str(cfg, "username", "");
        String password = str(cfg, "password", "");
        String raw = username + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return new AuthOutcome(Map.of("Authorization", "Basic " + encoded), Map.of(), Set.of("Authorization"));
    }

    private AuthOutcome bearer(Map<String, Object> cfg) {
        String token = str(cfg, "token", null);
        if (token == null || token.isBlank()) {
            throw ServiceException.of(BizError.AUTH_FAILED, "bearer 鉴权缺少 token");
        }
        return new AuthOutcome(Map.of("Authorization", "Bearer " + token), Map.of(), Set.of("Authorization"));
    }

    // ------------------------------------------------------------------ 需要换 token

    private AuthOutcome fromCache(String key, TokenFetcher fetcher) {
        CachedToken cached = cache.get(key);
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            return cached.outcome;
        }
        // 按 key 分段加锁：不同连接器互不阻塞，同一连接器只放一个线程去打 token 接口
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            CachedToken again = cache.get(key);
            if (again != null && again.expiresAt > System.currentTimeMillis()) {
                return again.outcome;
            }
            AuthOutcome fresh = fetcher.fetch();
            long ttl = Math.max(fresh.ttlMs(), 0L);
            if (ttl > 0) {
                // 至少留 5 秒，避免 expires_in 很小（如 60s）时因为安全余量把缓存写成「立刻过期」
                long effective = Math.max(ttl - EXPIRE_SAFETY_MS, 5_000L);
                cache.put(key, new CachedToken(System.currentTimeMillis() + effective, fresh));
            }
            return fresh;
        }
    }

    private AuthOutcome fetchOAuth2(Long connectorId, Map<String, Object> cfg, HttpExecutor http) {
        String tokenUrl = str(cfg, "tokenUrl", null);
        String clientId = str(cfg, "clientId", "");
        String clientSecret = str(cfg, "clientSecret", "");
        if (tokenUrl == null || tokenUrl.isBlank()) {
            throw ServiceException.of(BizError.AUTH_FAILED, "oauth2_cc 缺少 tokenUrl");
        }
        requireHttp(http);

        StringBuilder form = new StringBuilder("grant_type=client_credentials");
        form.append("&client_id=").append(urlEncode(clientId));
        form.append("&client_secret=").append(urlEncode(clientSecret));
        Object scope = cfg.get("scope");
        if (scope != null && !String.valueOf(scope).isBlank()) {
            form.append("&scope=").append(urlEncode(String.valueOf(scope)));
        }
        Object extra = cfg.get("extraParams");
        if (extra instanceof Map<?, ?> map) {
            map.forEach((k, v) -> form.append('&').append(urlEncode(String.valueOf(k)))
                    .append('=').append(urlEncode(String.valueOf(v))));
        }

        HttpRequestSpec spec = HttpRequestSpec.builder("POST", tokenUrl)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(form.toString())
                .timeouts(3_000, 10_000)
                .build();
        HttpResult result = http.execute(spec);
        if (result.getStatus() / 100 != 2) {
            throw ServiceException.of(BizError.AUTH_FAILED,
                    "获取 OAuth2 token 失败：HTTP " + result.getStatus() + "，" + abbreviate(result.getBody()));
        }
        Map<String, Object> body = JsonUtils.parseMap(result.getBody());
        Object token = body.get(str(cfg, "tokenField", "access_token"));
        if (token == null) {
            throw ServiceException.of(BizError.AUTH_FAILED, "token 响应里没有 access_token：" + abbreviate(result.getBody()));
        }
        long ttl = ttlMillis(body, cfg);
        log.debug("oauth2 token 刷新成功 connectorId={} ttl={}ms", connectorId, ttl);
        return headerOrQuery(cfg, String.valueOf(token), ttl);
    }

    private AuthOutcome fetchLoginToken(Long connectorId, Map<String, Object> cfg, HttpExecutor http) {
        String loginUrl = str(cfg, "loginUrl", null);
        if (loginUrl == null || loginUrl.isBlank()) {
            throw ServiceException.of(BizError.AUTH_FAILED, "login_token 缺少 loginUrl");
        }
        requireHttp(http);

        String method = str(cfg, "method", "POST").toUpperCase();
        String body = cfg.get("body") == null ? "" : JsonUtils.toJson(cfg.get("body"));
        HttpRequestSpec.Builder builder = HttpRequestSpec.builder(method, loginUrl)
                .header("Content-Type", "application/json")
                .timeouts(3_000, 10_000);
        if (!body.isEmpty()) {
            builder.body(body);
        }
        if (cfg.get("headers") instanceof Map<?, ?> headers) {
            headers.forEach((k, v) -> builder.header(String.valueOf(k), String.valueOf(v)));
        }

        HttpResult result = http.execute(builder.build());
        if (result.getStatus() / 100 != 2) {
            throw ServiceException.of(BizError.AUTH_FAILED,
                    "登录换 token 失败：HTTP " + result.getStatus() + "，" + abbreviate(result.getBody()));
        }
        String jsonpath = str(cfg, "tokenJsonpath", "$.access_token");
        Object token;
        try {
            token = JsonPath.read(result.getBody(), jsonpath);
        } catch (Exception e) {
            throw ServiceException.of(BizError.AUTH_FAILED,
                    "登录响应里取不到 token（jsonpath=" + jsonpath + "）：" + abbreviate(result.getBody()));
        }
        if (token == null) {
            throw ServiceException.of(BizError.AUTH_FAILED, "登录响应里 token 为空：" + abbreviate(result.getBody()));
        }
        long ttl = ttlMillis(JsonUtils.parseMap(result.getBody()), cfg);
        log.debug("login token 刷新成功 connectorId={} ttl={}ms", connectorId, ttl);
        return headerOrQuery(cfg, String.valueOf(token), ttl);
    }

    private AuthOutcome headerOrQuery(Map<String, Object> cfg, String token, long ttl) {
        String headerName = str(cfg, "headerName", "Authorization");
        String prefix = str(cfg, "tokenPrefix", "Bearer ");
        if ("query".equalsIgnoreCase(str(cfg, "in", "header"))) {
            return new AuthOutcome(Map.of(), Map.of(headerName, token), Set.of(), ttl);
        }
        return new AuthOutcome(Map.of(headerName, prefix + token), Map.of(), Set.of(headerName), ttl);
    }

    private long ttlMillis(Map<String, Object> body, Map<String, Object> cfg) {
        Object expiresIn = body.get("expires_in");
        long seconds = expiresIn instanceof Number n ? n.longValue() : -1L;
        if (seconds <= 0) {
            Object configured = cfg.get("ttlSeconds");
            seconds = configured instanceof Number n ? n.longValue() : 3_600L;
        }
        return seconds * 1000L;
    }

    /**
     * 换 token 用的 HTTP 执行器是可选的：只配 api_key/basic/bearer 的部署不应该被强制创建它。
     */
    private void requireHttp(HttpExecutor http) {
        if (http == null) {
            throw ServiceException.of(BizError.INTERNAL_ERROR, "未装配 HttpExecutor，无法换取 token");
        }
    }

    private static String str(Map<String, Object> cfg, String key, String defaultValue) {
        Object value = cfg.get(key);
        return value == null || String.valueOf(value).isBlank() ? defaultValue : String.valueOf(value);
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }

    @FunctionalInterface
    private interface TokenFetcher {
        AuthOutcome fetch();
    }

    private record CachedToken(long expiresAt, AuthOutcome outcome) {
    }

    /**
     * 鉴权产物：要加的 header、要加的 query，以及**哪些 header 是敏感值**。
     *
     * <p>敏感名单不是给加密用的（加密在 connector 模块），而是给「回显与脱敏」用的：
     * 试调面板要展示实际发出的请求，这份名单决定哪些值显示成 {@code ******}。
     *
     * @param headers       追加到请求头的键值
     * @param queryParams   追加到 URL 的查询参数
     * @param sensitiveKeys 需要脱敏的键名
     * @param ttlMs         仅 token 类鉴权有意义
     * @author carbon
     */
    public record AuthOutcome(Map<String, String> headers,
                              Map<String, String> queryParams,
                              Set<String> sensitiveKeys,
                              long ttlMs) {

        public static final AuthOutcome EMPTY = new AuthOutcome(Map.of(), Map.of(), Set.of(), 0L);

        public AuthOutcome(Map<String, String> headers, Map<String, String> queryParams, Set<String> sensitiveKeys) {
            this(headers, queryParams, sensitiveKeys, 0L);
        }

        /** 合并另一份产物（连接器级鉴权 + 接口级覆盖） */
        public AuthOutcome merge(AuthOutcome other, boolean otherWins) {
            Map<String, String> mergedHeaders = new LinkedHashMap<>(otherWins ? headers : other.headers);
            mergedHeaders.putAll(otherWins ? other.headers : headers);
            Map<String, String> mergedQuery = new HashMap<>(otherWins ? queryParams : other.queryParams);
            mergedQuery.putAll(otherWins ? other.queryParams : queryParams);
            Set<String> keys = new java.util.HashSet<>(sensitiveKeys);
            keys.addAll(other.sensitiveKeys);
            return new AuthOutcome(mergedHeaders, mergedQuery, keys, Math.max(ttlMs, other.ttlMs));
        }
    }
}
