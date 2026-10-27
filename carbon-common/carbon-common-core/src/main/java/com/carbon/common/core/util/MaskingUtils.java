package com.carbon.common.core.util;

import java.util.Locale;

/**
 * 脱敏工具：任何**要进日志、要回给前端列表页**的密钥都必须过一遍这里。
 *
 * <p>规则来自 §7.2：只暴露「前缀 4 位 + 长度」，让运维能对上是哪把钥匙，又拿不到钥匙本身。
 * 短于 8 位的值直接全遮掉——短密钥的前缀本身就是半个密钥。
 *
 * @author carbon
 */
public final class MaskingUtils {

    private static final String MASK = "****";

    private MaskingUtils() {
    }

    public static String mask(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        if (secret.length() < 8) {
            return MASK;
        }
        return secret.substring(0, 4) + MASK + "(" + secret.length() + ")";
    }

    /**
     * 按敏感键名判断是否需要脱敏。键名比对忽略大小写，覆盖常见命名风格。
     */
    public static boolean sensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("password") || k.contains("passwd") || k.contains("secret")
                || k.contains("token") || k.contains("credential") || k.contains("apikey")
                || k.contains("api_key") || k.contains("private")
                || k.endsWith("pwd") || k.equals("authorization");
    }

    /**
     * URL 脱敏：query 里可能带 {@code ?token=}，日志里只保留 scheme + host + path。
     */
    public static String maskUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        int q = url.indexOf('?');
        if (q < 0) {
            return url;
        }
        return url.substring(0, q) + "?" + MASK;
    }
}
