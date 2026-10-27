package com.carbon.modules.engine.mask;

import com.carbon.common.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 脱敏工具：把「能证明你身份的字符串」换成星号。
 *
 * <p>它服务两类输出：
 * <ul>
 *   <li>试调面板回显的「实际发出的请求」——用户要能复制成 curl，但不能把密钥复制走；</li>
 *   <li>{@code invoke_log} 的落库内容——日志的查看者往往比调用者权限更高，但也不该看到密钥。</li>
 * </ul>
 *
 * <p>判定分两条路：<b>键名黑名单</b>（{@code Authorization}/{@code *secret*}/{@code *password*} 等）
 * 与 <b>显式名单</b>（鉴权解析器告诉我们哪个 header 是它刚注入的密钥）。
 * 只有键名黑名单会漏（三方可能用 {@code X-Wx-Ticket} 这种名字），所以两条都要有。
 *
 * @author carbon
 */
public final class MaskingUtils {

    /** 掩码文本；与前端展示约定一致 */
    public static final String MASK = "******";

    /** 键名黑名单：命中即脱敏（忽略大小写，做子串匹配） */
    private static final Set<String> SENSITIVE_KEYWORDS = Set.of(
            "authorization", "cookie", "token", "secret", "password", "passwd",
            "apikey", "api-key", "api_key", "accesskey", "access-key", "access_key",
            "credential", "signature", "sign", "nonce", "sessionid", "session-id");

    /** 值太短时不脱敏——把 "1" 打成 "******" 只会让人以为配置错了 */
    private static final int MIN_MASK_LENGTH = 6;

    private MaskingUtils() {
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        if (SENSITIVE_KEYWORDS.contains(lower)) {
            return true;
        }
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 脱敏请求头。
     *
     * @param headers   原始头
     * @param extraKeys 调用方显式指定的敏感键（除黑名单之外）
     */
    public static Map<String, String> maskHeaders(Map<String, String> headers, Set<String> extraKeys) {
        Map<String, String> masked = new LinkedHashMap<>();
        if (headers == null) {
            return masked;
        }
        headers.forEach((key, value) -> {
            boolean sensitive = isSensitiveKey(key)
                    || (extraKeys != null && extraKeys.stream().anyMatch(k -> k.equalsIgnoreCase(key)));
            masked.put(key, sensitive ? MASK : value);
        });
        return masked;
    }

    /**
     * 脱敏 URL 的 query 部分。路径与 host 保留原样——排障需要它们。
     *
     * <p>只按 {@code key=value} 处理，遇到没有等号的片段原样保留。
     */
    public static String maskUrl(String url) {
        if (url == null) {
            return null;
        }
        int q = url.indexOf('?');
        if (q < 0) {
            return url;
        }
        String base = url.substring(0, q);
        String query = url.substring(q + 1);
        StringBuilder sb = new StringBuilder(base).append('?');
        String[] pairs = query.split("&");
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                sb.append('&');
            }
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            if (eq < 0) {
                sb.append(pair);
                continue;
            }
            String key = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            sb.append(key).append('=').append(isSensitiveKey(key) ? MASK : value);
        }
        return sb.toString();
    }

    /**
     * 脱敏 JSON 报文体里的敏感字段（递归）。
     *
     * <p>失败时返回原文——脱敏工具不应该让调用方因为「日志脱敏失败」而整个请求失败，
     * 这是它与业务校验最大的区别。
     */
    public static String maskJson(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode node = JsonUtils.mapper().readTree(json);
            maskNode(node);
            return JsonUtils.mapper().writeValueAsString(node);
        } catch (Exception e) {
            return json;
        }
    }

    private static void maskNode(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<String> names = object.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                JsonNode child = object.get(name);
                if (child != null && child.isValueNode() && isSensitiveKey(name)) {
                    String text = child.asText();
                    object.put(name, text.length() < MIN_MASK_LENGTH ? text : MASK);
                } else if (child != null) {
                    maskNode(child);
                }
            }
        } else if (node != null && node.isArray()) {
            node.forEach(MaskingUtils::maskNode);
        }
    }

    /** 单个值脱敏：用于 query/host 之外的零散展示 */
    public static String maskValue(String value) {
        if (value == null || value.length() < MIN_MASK_LENGTH) {
            return value;
        }
        return MASK;
    }
}
