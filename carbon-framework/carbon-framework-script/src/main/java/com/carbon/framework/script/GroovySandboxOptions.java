package com.carbon.framework.script;

/**
 * 脚本沙箱参数。
 *
 * @param enabled     总开关（关闭时 {@link GroovySandbox} 直接跳过，返回上下文原样）
 * @param timeoutMs   单次脚本墙钟超时（毫秒）
 * @param cacheMax    编译缓存条数上限
 * @param cacheTtlMs  编译缓存访问过期（毫秒）
 * @author carbon
 */
public record GroovySandboxOptions(boolean enabled, long timeoutMs, int cacheMax, long cacheTtlMs) {

    /** 单次脚本默认超时：3s。 */
    public static final long DEFAULT_TIMEOUT_MS = 3000L;

    /** 编译缓存默认上限：500 条。 */
    public static final int DEFAULT_CACHE_MAX = 500;

    /** 编译缓存默认访问过期：1 小时。 */
    public static final long DEFAULT_CACHE_TTL_MS = 60 * 60 * 1000L;

    /**
     * 紧凑构造：参数兜底。
     */
    public GroovySandboxOptions {
        timeoutMs = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
        cacheMax = cacheMax <= 0 ? DEFAULT_CACHE_MAX : cacheMax;
        cacheTtlMs = cacheTtlMs <= 0 ? DEFAULT_CACHE_TTL_MS : cacheTtlMs;
    }

    /**
     * 默认参数（开启 + 3s + 500 条 + 1h）。
     *
     * @return 参数
     */
    public static GroovySandboxOptions defaults() {
        return new GroovySandboxOptions(true, DEFAULT_TIMEOUT_MS, DEFAULT_CACHE_MAX, DEFAULT_CACHE_TTL_MS);
    }
}
