package com.carbon.framework.script;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 脚本可用的工具集（暴露为 {@code ctx.utils} 与顶层 {@code utils}）。
 *
 * <p>刻意只给「确定性、无 IO、无反射」的能力：脚本能算能转，但不能读文件、不能发请求、不能碰类加载器。
 * 需要发 HTTP 的场景是连接器节点的职责，不是脚本的。
 *
 * @author carbon
 */
public class ScriptUtils {

    private static final Logger log = LoggerFactory.getLogger(ScriptUtils.class);

    private final ObjectMapper objectMapper;
    private final String traceId;

    /**
     * 构造。
     *
     * @param objectMapper JSON 映射器
     * @param traceId      当前调用的 traceId（脚本日志关联用，可为 null）
     */
    public ScriptUtils(ObjectMapper objectMapper, String traceId) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.traceId = traceId;
    }

    /**
     * 对象序列化为 JSON 文本。
     *
     * @param value 任意对象
     * @return JSON 文本
     */
    public String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("json() 序列化失败: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * JSON 文本解析为 Map / List。
     *
     * @param text JSON 文本
     * @return 解析结果
     */
    public Object jsonParse(String text) {
        try {
            return objectMapper.readValue(text, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("jsonParse() 解析失败: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * JSONPath 取值（与响应抽取同一套实现，路径语法完全一致）。
     *
     * @param json JSON 文本
     * @param path JSONPath 表达式
     * @return 取到的值
     */
    public Object jsonpath(String json, String path) {
        return JsonPath.parse(json).read(path);
    }

    /**
     * 十六进制摘要。
     *
     * @param algorithm {@code MD5} 或 {@code SHA-256}
     * @param text      原文
     * @return 小写十六进制
     */
    public String digest(String algorithm, String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm.toUpperCase(java.util.Locale.ROOT));
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("不支持的摘要算法: " + algorithm, e);
        }
    }

    /**
     * MD5（小写十六进制）。
     *
     * @param text 原文
     * @return 摘要
     */
    public String md5(String text) {
        return digest("MD5", text);
    }

    /**
     * SHA-256（小写十六进制）。
     *
     * @param text 原文
     * @return 摘要
     */
    public String sha256(String text) {
        return digest("SHA-256", text);
    }

    /**
     * HMAC-SHA256（小写十六进制）。
     *
     * @param key  密钥
     * @param text 原文
     * @return 摘要
     */
    public String hmacSha256(String key, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("hmacSha256 计算失败: " + e.getMessage(), e);
        }
    }

    /**
     * Base64 编码（UTF-8）。
     *
     * @param text 原文
     * @return 编码结果
     */
    public String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * URL 编码（UTF-8）。
     *
     * @param text 原文
     * @return 编码结果
     */
    public String urlEncode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    /**
     * 写脚本日志（自动带上 traceId，便于按调用串联）。
     *
     * @param message 日志内容
     */
    public void log(String message) {
        log.info("[script][trace={}] {}", traceId == null ? "-" : traceId, message);
    }
}
