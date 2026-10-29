package com.carbon.modules.engine.credential;

import com.carbon.common.core.enums.BizError;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.framework.http.HttpExecutor;
import com.carbon.framework.script.GroovySandbox;
import com.carbon.framework.script.ScriptError;
import com.carbon.modules.partner.support.CredentialConfigCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 凭证解析：把一条合作方凭证变成「这次请求要加的 header / query」。
 *
 * <p>两种类型（2026-10-29 起，替代原来的六种固定模式）：
 * <ul>
 *   <li>{@code header}：静态配置若干条请求头/查询参数，纯本地计算，无网络；</li>
 *   <li>{@code script}：跑一段 Groovy，脚本自己算出要加什么。脚本能拿到
 *       {@code ctx.secrets}（已解密的密钥）、{@code ctx.http}（换 token 用）、
 *       {@code utils}（json/hmacSha256/md5/sha256/base64/urlEncode/jsonpath），
 *       往 {@code ctx.headers} / {@code ctx.queryParams} 里写结果，
 *       需要缓存就设 {@code ctx.ttlSeconds}。</li>
 * </ul>
 *
 * <p><b>为什么脚本要缓存与单飞</b>：脚本里通常是「换 token」。
 * 缓存过期那一刻，10 个并发请求会同时发现「没 token」，不加锁就会并发打 10 次 token 接口；
 * 三方侧往往对 token 接口有严格限流，这一下就把自己封了。
 * 这里用「按 key 分段的 synchronized + 双重检查」解决，同一凭证永远只有一个线程在换 token。
 * 只有显式配了 {@code ttlSeconds > 0} 才进缓存——没配的脚本按「每次都要现算」处理，
 * 因为平台无从判断脚本结果能不能复用。
 *
 * <p><b>为什么 key 是凭证 id 而不是接入点 id</b>：一个合作方可以有多套凭证
 * （新旧并存、查询与回传分开授权）。按接入点缓存会让「同一接入点下两个接口用不同凭证」
 * 互相污染——A 接口的 token 被 B 接口当成自己的。
 *
 * @author carbon
 */
public class CredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(CredentialResolver.class);

    /** 缓存提前过期的安全余量，避免「刚取到就过期」 */
    private static final long EXPIRE_SAFETY_MS = 60_000L;

    /** 缓存 key 后缀：两种类型各自独立，避免同名凭证的 header/script 结果互相覆盖 */
    private static final String SCRIPT_SUFFIX = ":script";

    private final Map<String, CachedOutcome> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final GroovySandbox sandbox;

    /**
     * @param sandbox 脚本沙箱；为 {@code null} 时 {@code script} 型凭证会在调用时明确报错
     */
    public CredentialResolver(GroovySandbox sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * 计算本次请求的凭证产物。
     *
     * @param credentialId 用于缓存分段的 key
     * @param type         {@code null} 视为不鉴权
     * @param config       **明文**配置（已由 {@link CredentialConfigCodec} 解密）
     * @param http         脚本换 token 要用；{@code header} 型可以为 {@code null}
     */
    public CredentialOutcome resolve(Long credentialId, String type, Map<String, Object> config, HttpExecutor http) {
        if (type == null || type.isBlank() || "none".equals(type)) {
            return CredentialOutcome.EMPTY;
        }
        Map<String, Object> cfg = config == null ? Map.of() : config;
        return switch (type) {
            case CredentialConfigCodec.TYPE_HEADER -> header(cfg);
            case CredentialConfigCodec.TYPE_SCRIPT -> script(credentialId, cfg, http);
            default -> throw ServiceException.of(BizError.PARAM_INVALID, "不支持的凭证类型：" + type);
        };
    }

    /** 缓存失效（凭证变更或合作方停用时由事件触发） */
    public void invalidate(Long credentialId) {
        cache.keySet().removeIf(k -> k.startsWith(credentialId + ":"));
    }

    public void clear() {
        cache.clear();
    }

    // ------------------------------------------------------------------ header 型

    private CredentialOutcome header(Map<String, Object> cfg) {
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> query = new LinkedHashMap<>();
        Set<String> sensitive = new LinkedHashSet<>();

        for (Map<String, Object> row : rows(cfg.get("headers"))) {
            String name = str(row.get("name"), null);
            if (name == null || name.isBlank()) {
                continue;
            }
            String value = str(row.get("value"), null);
            if (value == null || value.isBlank()) {
                throw ServiceException.of(BizError.AUTH_FAILED, "请求头 " + name + " 的值是空的");
            }
            String in = str(row.get("in"), "header");
            if ("query".equalsIgnoreCase(in)) {
                query.put(name, value);
            } else {
                headers.put(name, value);
            }
            sensitive.add(name);
        }
        if (headers.isEmpty() && query.isEmpty()) {
            throw ServiceException.of(BizError.AUTH_FAILED, "凭证里没有任何请求头，请检查配置");
        }
        return new CredentialOutcome(headers, query, sensitive, 0L);
    }

    // ------------------------------------------------------------------ script 型

    private CredentialOutcome script(Long credentialId, Map<String, Object> cfg, HttpExecutor http) {
        long ttlSeconds = number(cfg.get("ttlSeconds"), 0L);
        if (ttlSeconds <= 0) {
            return runScript(credentialId, cfg, http);
        }
        return fromCache(credentialId + SCRIPT_SUFFIX, () -> runScript(credentialId, cfg, http));
    }

    private CredentialOutcome runScript(Long credentialId, Map<String, Object> cfg, HttpExecutor http) {
        String script = str(cfg.get("script"), null);
        if (script == null || script.isBlank()) {
            throw ServiceException.of(BizError.AUTH_FAILED, "凭证脚本是空的");
        }
        if (sandbox == null) {
            throw ServiceException.of(BizError.INTERNAL_ERROR, "未装配脚本沙箱，无法执行凭证脚本");
        }

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("credentialId", credentialId);
        ctx.put("secrets", secretsOf(cfg.get("secrets")));
        ctx.put("headers", new LinkedHashMap<String, String>());
        ctx.put("queryParams", new LinkedHashMap<String, String>());
        ctx.put("http", new CredentialScriptHttp(http));

        try {
            sandbox.execute(script, ctx, "credential-" + credentialId);
        } catch (ScriptError e) {
            throw ServiceException.of(BizError.AUTH_FAILED, "凭证脚本执行失败：" + e.getMessage());
        } catch (RuntimeException e) {
            throw ServiceException.of(BizError.AUTH_FAILED, "凭证脚本执行失败：" + e.getMessage());
        }

        Map<String, String> headers = strings(ctx.get("headers"));
        Map<String, String> query = strings(ctx.get("queryParams"));
        if (headers.isEmpty() && query.isEmpty()) {
            throw ServiceException.of(BizError.AUTH_FAILED,
                    "凭证脚本没有设置任何请求头：请往 ctx.headers 里写结果");
        }

        Set<String> sensitive = new LinkedHashSet<>();
        list(cfg.get("sensitiveKeys")).forEach(k -> sensitive.add(str(k, "")));
        sensitive.removeIf(String::isBlank);
        if (sensitive.isEmpty()) {
            // 没声明就全部当敏感：脚本产出的头默认就是凭证，宁可多打码也不能泄漏
            sensitive.addAll(headers.keySet());
            sensitive.addAll(query.keySet());
        }

        long ttl = number(ctx.get("ttlSeconds"), number(cfg.get("ttlSeconds"), 0L)) * 1000L;
        log.debug("凭证脚本执行完成 credentialId={} headers={} query={} ttl={}ms",
                credentialId, headers.size(), query.size(), ttl);
        return new CredentialOutcome(headers, query, sensitive, ttl);
    }

    private static Map<String, Object> secretsOf(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
        }
        return result;
    }

    // ------------------------------------------------------------------ 缓存

    private CredentialOutcome fromCache(String key, OutcomeFetcher fetcher) {
        CachedOutcome cached = cache.get(key);
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            return cached.outcome;
        }
        // 按 key 分段加锁：不同凭证互不阻塞，同一凭证只放一个线程去打 token 接口
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            CachedOutcome again = cache.get(key);
            if (again != null && again.expiresAt > System.currentTimeMillis()) {
                return again.outcome;
            }
            CredentialOutcome fresh = fetcher.fetch();
            long ttl = Math.max(fresh.ttlMs(), 0L);
            if (ttl > 0) {
                // 至少留 5 秒，避免 ttl 很小（如 60s）时因为安全余量把缓存写成「立刻过期」
                long effective = Math.max(ttl - EXPIRE_SAFETY_MS, 5_000L);
                cache.put(key, new CachedOutcome(System.currentTimeMillis() + effective, fresh));
            }
            return fresh;
        }
    }

    // ------------------------------------------------------------------ 工具

    private static String str(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? defaultValue : text;
    }

    private static long number(Object value, long defaultValue) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static java.util.List<Map<String, Object>> rows(Object value) {
        java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();
        if (value instanceof Collection<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    map.forEach((k, v) -> row.put(String.valueOf(k), v));
                    result.add(row);
                }
            }
        }
        return result;
    }

    private static Collection<?> list(Object value) {
        return value instanceof Collection<?> collection ? collection : java.util.List.of();
    }

    /** 脚本里可能写成 Map 也可能写成别的可迭代，统一转成 String→String */
    private static Map<String, String> strings(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (k != null && v != null) {
                    result.put(String.valueOf(k), String.valueOf(v));
                }
            });
        }
        return result;
    }

    @FunctionalInterface
    private interface OutcomeFetcher {
        CredentialOutcome fetch();
    }

    private record CachedOutcome(long expiresAt, CredentialOutcome outcome) {
    }

    /**
     * 凭证产物：要加的 header、要加的 query，以及**哪些键是敏感值**。
     *
     * <p>敏感名单不是给加密用的（加密在 connector 模块），而是给「回显与脱敏」用的：
     * 试调面板要展示实际发出的请求，这份名单决定哪些值显示成 {@code ******}。
     *
     * @param headers       追加到请求头的键值
     * @param queryParams   追加到 URL 的查询参数
     * @param sensitiveKeys 需要脱敏的键名
     * @param ttlMs         脚本声明了 ttlSeconds 时才有意义
     * @author carbon
     */
    public record CredentialOutcome(Map<String, String> headers,
                                    Map<String, String> queryParams,
                                    Set<String> sensitiveKeys,
                                    long ttlMs) {

        public static final CredentialOutcome EMPTY =
                new CredentialOutcome(Map.of(), Map.of(), Set.of(), 0L);

        public CredentialOutcome(Map<String, String> headers, Map<String, String> queryParams,
                                 Set<String> sensitiveKeys) {
            this(headers, queryParams, sensitiveKeys, 0L);
        }

        /** 合并另一份产物（凭证 + 调用方覆盖头） */
        public CredentialOutcome merge(CredentialOutcome other, boolean otherWins) {
            Map<String, String> mergedHeaders = new LinkedHashMap<>(otherWins ? headers : other.headers);
            mergedHeaders.putAll(otherWins ? other.headers : headers);
            Map<String, String> mergedQuery = new HashMap<>(otherWins ? queryParams : other.queryParams);
            mergedQuery.putAll(otherWins ? other.queryParams : queryParams);
            Set<String> keys = new java.util.HashSet<>(sensitiveKeys);
            keys.addAll(other.sensitiveKeys);
            return new CredentialOutcome(mergedHeaders, mergedQuery, keys, Math.max(ttlMs, other.ttlMs));
        }
    }
}
