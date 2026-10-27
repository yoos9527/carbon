package com.carbon.framework.http;

import java.nio.charset.StandardCharsets;

/**
 * 响应/日志文本截断（按<b>字节</b>计，且在 UTF-8 字符边界处截），防止超大方把内存打满。
 *
 * <p>两个用途：① {@link HttpExecutor} 限制单次响应体大小；② 调用日志落库前的 body 截断。
 *
 * @author carbon
 */
public final class ResponseTruncator {

    /** 默认单次响应体上限：5MB（超出即截断并打标记，日志里只看到头部）。 */
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 5 * 1024 * 1024;

    /** 日志里 body 的默认上限：64KB，对齐 §11 `platform.log.body-max-bytes`。 */
    public static final int DEFAULT_MAX_LOG_BYTES = 64 * 1024;

    /** 截断标记（追加在末尾，便于人眼与程序同时识别）。 */
    public static final String MARKER = "…[truncated by carbon]";

    private ResponseTruncator() {
    }

    /**
     * 截断结果。
     *
     * @param text      截断后的文本
     * @param byteLength 原始字节数（截断前）
     * @param truncated 是否发生截断
     */
    public record Result(String text, int byteLength, boolean truncated) {

        /**
         * 空结果。
         *
         * @return 结果
         */
        public static Result empty() {
            return new Result("", 0, false);
        }
    }

    /**
     * 按字节上限截断字节数组并解码为 UTF-8。
     *
     * @param data     原始字节
     * @param maxBytes 上限（字节）
     * @return 结果
     */
    public static Result of(byte[] data, int maxBytes) {
        if (data == null || data.length == 0) {
            return Result.empty();
        }
        int limit = maxBytes <= 0 ? Integer.MAX_VALUE : maxBytes;
        if (data.length <= limit) {
            return new Result(new String(data, StandardCharsets.UTF_8), data.length, false);
        }
        int safe = utf8SafeLength(data, limit);
        return new Result(new String(data, 0, safe, StandardCharsets.UTF_8) + MARKER, data.length, true);
    }

    /**
     * 按字节上限截断字符串。
     *
     * @param text     原始文本
     * @param maxBytes 上限（字节）
     * @return 结果
     */
    public static Result of(String text, int maxBytes) {
        if (text == null || text.isEmpty()) {
            return Result.empty();
        }
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        return of(data, maxBytes);
    }

    /**
     * 计算不超过 {@code limit} 字节、且不切断 UTF-8 字符的最大长度。
     *
     * @param data  原始字节
     * @param limit 字节上限
     * @return 安全长度
     */
    static int utf8SafeLength(byte[] data, int limit) {
        int end = Math.min(limit, data.length);
        // 回退到字符起始字节：0b10xxxxxx 是 UTF-8 的续字节
        while (end > 0 && end < data.length && (data[end] & 0xC0) == 0x80) {
            end--;
        }
        return end;
    }
}
