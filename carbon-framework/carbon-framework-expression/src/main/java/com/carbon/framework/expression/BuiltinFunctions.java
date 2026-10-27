package com.carbon.framework.expression;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 函数白名单。
 *
 * <p>壳与求值器共用同一份注册表：Aviator 适配器把它注册成 Aviator 函数，自研兜底核直接调用。
 * <b>白名单之外的函数一律不存在</b>——这是「用户可配置表达式」的安全底线。
 *
 * @author carbon
 */
public final class BuiltinFunctions {

    /** 函数实现：入参已转成 Java 值，返回 Java 值。 */
    public interface Fn extends Function<List<Object>, Object> {
    }

    private static final Map<String, Fn> REGISTRY = new LinkedHashMap<>();

    private static final DateTimeFormatter DEFAULT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static {
        REGISTRY.put("now", args -> LocalDateTime.now()
                .format(args.isEmpty() ? DEFAULT_TIME : DateTimeFormatter.ofPattern(str(args.get(0)))));
        REGISTRY.put("timestamp", args -> System.currentTimeMillis());
        REGISTRY.put("uuid", args -> UUID.randomUUID().toString().replace("-", ""));
        REGISTRY.put("randomInt", args -> {
            int min = (int) toLong(args.get(0));
            int max = (int) toLong(args.get(1));
            if (max < min) {
                throw RenderException.paramInvalid("randomInt(min,max) 要求 max >= min，实际 " + min + "," + max);
            }
            return ThreadLocalRandom.current().nextInt(min, max + 1);
        });
        REGISTRY.put("md5", args -> digest("MD5", str(args.get(0))));
        REGISTRY.put("sha256", args -> digest("SHA-256", str(args.get(0))));
        REGISTRY.put("hmacSha256", args -> hmac(str(args.get(0)), str(args.get(1))));
        REGISTRY.put("base64", args -> Base64.getEncoder()
                .encodeToString(str(args.get(0)).getBytes(StandardCharsets.UTF_8)));
        REGISTRY.put("base64Decode", args -> new String(Base64.getDecoder().decode(str(args.get(0))),
                StandardCharsets.UTF_8));
        REGISTRY.put("urlEncode", args -> urlEncode(str(args.get(0))));
        REGISTRY.put("urlDecode", args -> URLDecoder.decode(str(args.get(0)), StandardCharsets.UTF_8));
        REGISTRY.put("jsonpath", args -> jsonpath(str(args.get(0)), str(args.get(1))));
        REGISTRY.put("upper", args -> str(args.get(0)).toUpperCase(Locale.ROOT));
        REGISTRY.put("lower", args -> str(args.get(0)).toLowerCase(Locale.ROOT));
        REGISTRY.put("trim", args -> str(args.get(0)).trim());
        REGISTRY.put("replace", args -> str(args.get(0)).replace(str(args.get(1)), str(args.get(2))));
    }

    private BuiltinFunctions() {
    }

    /**
     * 函数名是否在白名单内。
     *
     * @param name 函数名
     * @return 是否允许
     */
    public static boolean contains(String name) {
        return REGISTRY.containsKey(name);
    }

    /**
     * 函数名全集（顺序稳定，便于文档与前端提示）。
     *
     * @return 只读集合
     */
    public static java.util.Set<String> names() {
        return java.util.Collections.unmodifiableSet(REGISTRY.keySet());
    }

    /**
     * 调用白名单函数。
     *
     * @param name 函数名
     * @param args Java 入参
     * @return 结果
     */
    public static Object call(String name, List<Object> args) {
        Fn fn = REGISTRY.get(name);
        if (fn == null) {
            throw RenderException.paramInvalid("未在白名单中的函数: " + name + "()");
        }
        try {
            return fn.apply(args);
        } catch (RenderException e) {
            throw e;
        } catch (Exception e) {
            throw new RenderException(RenderException.EXPRESSION_ERROR,
                    "函数 " + name + "() 执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * RFC3986 编码（{@code +} → {@code %20}，{@code *} → {@code %2A}，{@code ~} 保留）。
     *
     * @param value 原值
     * @return 编码后的值
     */
    public static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    // ---------------------------------------------------------------- helpers

    private static Object jsonpath(String json, String path) {
        if (path.contains("..")) {
            throw RenderException.paramInvalid("jsonpath 禁止深层扫描(..): " + path);
        }
        try {
            return JsonPath.read(json, path);
        } catch (PathNotFoundException e) {
            return null;
        } catch (Exception e) {
            throw new RenderException(RenderException.EXPRESSION_ERROR, "jsonpath 解析失败: " + e.getMessage(), e);
        }
    }

    private static String digest(String algorithm, String text) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            return hex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RenderException(RenderException.EXPRESSION_ERROR, algorithm + " 不可用", e);
        }
    }

    private static String hmac(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RenderException(RenderException.EXPRESSION_ERROR, "HmacSHA256 失败", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw RenderException.paramInvalid("需要整数，实际为: " + value);
        }
    }
}
