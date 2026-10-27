package com.carbon.framework.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次 HTTP 调用的结果（拿到响应即视为抵达，与业务状态码无关）。
 *
 * <p>三个「大小」字段都与观测有关，别混：
 * <ul>
 *   <li>{@link #getBody()} —— 真正带出来的文本（超上限时被截断并加标记）；</li>
 *   <li>{@link #getBodyBytes()} —— 实际读进内存的字节数（≤ 上限 + 1）；</li>
 *   <li>{@link #getContentLength()} —— 对端声明的 Content-Length，{@code -1} 表示未知（chunked）。</li>
 * </ul>
 *
 * @author carbon
 */
public final class HttpResult {

    /** Content-Length 未知时的取值。 */
    public static final long UNKNOWN_LENGTH = -1L;

    private final int status;
    private final Map<String, List<String>> headers;
    private final String body;
    private final boolean truncated;
    private final int bodyBytes;
    private final long contentLength;
    private final long costMs;
    private final int attempts;

    /**
     * 构造。
     *
     * @param status        状态码
     * @param headers       响应头
     * @param body          响应体（可能被截断）
     * @param truncated     是否被截断
     * @param bodyBytes     实际读到的字节数
     * @param contentLength 对端声明的长度，未知传 {@link #UNKNOWN_LENGTH}
     * @param costMs        耗时（毫秒，含重试）
     * @param attempts      实际尝试次数（含首次）
     */
    public HttpResult(int status, Map<String, List<String>> headers, String body, boolean truncated,
                      int bodyBytes, long contentLength, long costMs, int attempts) {
        this.status = status;
        this.headers = headers == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.body = body == null ? "" : body;
        this.truncated = truncated;
        this.bodyBytes = bodyBytes;
        this.contentLength = contentLength;
        this.costMs = costMs;
        this.attempts = attempts;
    }

    public int getStatus() {
        return status;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public String getBody() {
        return body;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public int getBodyBytes() {
        return bodyBytes;
    }

    public long getContentLength() {
        return contentLength;
    }

    public long getCostMs() {
        return costMs;
    }

    public int getAttempts() {
        return attempts;
    }

    /**
     * 用于日志/看板的响应体大小：优先取对端声明的真实长度，其次取实际读到的字节数。
     *
     * @return 字节数
     */
    public long getPayloadSize() {
        return contentLength > 0 ? contentLength : bodyBytes;
    }

    /**
     * 取第一个同名响应头（大小写不敏感）。
     *
     * @param name 头名
     * @return 头值
     */
    public Optional<String> header(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String target = name.toLowerCase(Locale.ROOT);
        return headers.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase(Locale.ROOT).equals(target))
                .map(e -> e.getValue().isEmpty() ? null : e.getValue().get(0))
                .filter(Objects::nonNull)
                .findFirst();
    }

    /**
     * 2xx。
     *
     * @return 是否成功
     */
    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    /**
     * 4xx。
     *
     * @return 是否 4xx
     */
    public boolean isClientError() {
        return status >= 400 && status < 500;
    }

    /**
     * 5xx。
     *
     * @return 是否 5xx
     */
    public boolean isServerError() {
        return status >= 500 && status < 600;
    }

    /**
     * 替换耗时与尝试次数（重试收尾时用）。
     *
     * @param totalCostMs 累计耗时
     * @param totalAttempts 累计尝试次数
     * @return 新结果
     */
    HttpResult withTotals(long totalCostMs, int totalAttempts) {
        return new HttpResult(status, headers, body, truncated, bodyBytes, contentLength, totalCostMs, totalAttempts);
    }

    @Override
    public String toString() {
        return "HttpResult{status=" + status + ", bytes=" + bodyBytes + "/" + contentLength
                + ", truncated=" + truncated + ", costMs=" + costMs + ", attempts=" + attempts + '}';
    }
}
