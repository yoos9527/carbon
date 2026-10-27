package com.carbon.modules.gateway.gw;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 网关签名协议实现。
 *
 * <pre>
 * stringToSign = METHOD + "\n" + canonicalPathAndQuery + "\n" + timestamp + "\n" + nonce
 *              + "\n" + HEX_LOWER(SHA256(rawBody))
 * X-Sign       = Base64(HmacSHA256(appSecret, stringToSign))
 * </pre>
 *
 * <p>三个容易踩的约定，写死在代码里而不是靠文档：
 * <ul>
 *   <li><b>query 规范化</b>：按 key 字典序、多值保持原顺序、key 与 value 都做 RFC3986 编码后再拼。
 *       不规范化的话，同一个请求换个参数顺序签名就变了，接入方会被这种问题拖一整天；</li>
 *   <li><b>body 参与签名</b>：只签 URL 的话，body 可以被中间人随意改（比如改金额）；</li>
 *   <li><b>空 body 取 SHA256("")</b>，不是空串——否则 GET 请求的签名规则和 POST 不一致；
 *       这三个约定在 {@code docs/03-核心链路.md} §4.6 里是同一份契约。</li>
 * </ul>
 *
 * <p>本类是纯函数集合，不碰 Redis、不碰 Spring，方便被「用 Python/Go 复算签名」这类
 * 跨语言验证直接对照。
 *
 * @author carbon
 */
public final class SignatureVerifier {

    public static final long DEFAULT_WINDOW_MS = 5 * 60 * 1000L;

    private SignatureVerifier() {
    }

    /**
     * 计算待签名串。
     *
     * @param method        HTTP 方法，建议已 uppercase
     * @param pathAndQuery  path + ("?" + canonicalQuery)
     * @param timestamp     毫秒时间戳字符串（原样，不做数值归一化：数字归一化会让 "0173" 与 "173" 同签）
     * @param nonce         随机串
     * @param body          原始请求体，{@code null} 视为空
     */
    public static String stringToSign(String method, String pathAndQuery, String timestamp,
                                     String nonce, byte[] body) {
        byte[] safeBody = body == null ? new byte[0] : body;
        return String.join("\n",
                method == null ? "" : method.toUpperCase(),
                pathAndQuery == null ? "" : pathAndQuery,
                timestamp == null ? "" : timestamp,
                nonce == null ? "" : nonce,
                sha256Hex(safeBody));
    }

    /**
     * 计算签名。
     */
    public static String sign(String secret, String stringToSign) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 不可用", e);
        }
    }

    /**
     * 校验签名。
     *
     * <p>用 {@link MessageDigest#isEqual} 而不是 {@code String.equals}：
     * 后者在第一个不同字节就返回，攻击者可以按耗时逐字节猜出正确签名。
     */
    public static boolean verify(String secret, String method, String pathAndQuery, String timestamp,
                                 String nonce, byte[] body, String providedSign) {
        if (secret == null || providedSign == null || providedSign.isBlank()) {
            return false;
        }
        String expected = sign(secret, stringToSign(method, pathAndQuery, timestamp, nonce, body));
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                providedSign.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 时间窗校验（双向）：既要挡「很久以前抓到的包」，也要挡「时钟跑到未来的机器」。
     */
    public static boolean withinWindow(Long timestamp, long nowMs, long windowMs) {
        if (timestamp == null) {
            return false;
        }
        return Math.abs(nowMs - timestamp) <= windowMs;
    }

    /**
     * query 规范化：key 字典序、同 key 多值保持原序、逐项 RFC3986 编码（空格是 {@code %20} 不是 {@code +}）。
     *
     * @param params 已解码的参数表（Servlet 层给的是解码后的值）
     */
    public static String canonicalQuery(Map<String, ? extends List<String>> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        List<String> pairs = new ArrayList<>();
        params.keySet().stream().sorted(Comparator.naturalOrder()).forEach(key -> {
            List<String> values = params.get(key);
            if (values == null || values.isEmpty()) {
                return;
            }
            for (String value : values) {
                pairs.add(rfc3986(key) + "=" + rfc3986(value == null ? "" : value));
            }
        });
        return String.join("&", pairs);
    }

    /**
     * 拼 {@code path?query}；没有 query 时不带问号（带一个空问号会让签名对不上）。
     */
    public static String pathAndQuery(String path, Map<String, ? extends List<String>> params) {
        String query = canonicalQuery(params);
        return query.isEmpty() ? path : path + "?" + query;
    }

    public static String sha256Hex(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String rfc3986(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
