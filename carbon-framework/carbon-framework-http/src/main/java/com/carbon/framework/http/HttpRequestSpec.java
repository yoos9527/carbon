package com.carbon.framework.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 HTTP 调用的完整请求描述（不可变，Builder 构造）。
 *
 * <p>URL 由调用方（引擎的渲染器）拼好再传进来——本类<b>不做</b>模板渲染、<b>不做</b>鉴权，
 * 只描述「发什么」。
 *
 * @author carbon
 */
public final class HttpRequestSpec {

    /** 默认连接超时（毫秒），对齐 §11 `platform.http.connect-timeout-ms`。 */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 3000;

    /** 默认读超时（毫秒）。 */
    public static final int DEFAULT_READ_TIMEOUT_MS = 10000;

    private final String method;
    private final String url;
    private final Map<String, List<String>> headers;
    private final String body;
    private final String contentType;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int fromPoolTimeoutMs;
    private final RetryPolicy retryPolicy;

    private HttpRequestSpec(Builder builder) {
        this.method = builder.method;
        this.url = builder.url;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(builder.headers));
        this.body = builder.body;
        this.contentType = builder.contentType;
        this.connectTimeoutMs = builder.connectTimeoutMs;
        this.readTimeoutMs = builder.readTimeoutMs;
        this.fromPoolTimeoutMs = builder.fromPoolTimeoutMs;
        this.retryPolicy = builder.retryPolicy == null ? RetryPolicy.none() : builder.retryPolicy;
    }

    /**
     * 新建 Builder。
     *
     * @param method HTTP 方法（GET/POST/PUT/DELETE/PATCH/HEAD）
     * @param url    完整 URL
     * @return Builder
     */
    public static Builder builder(String method, String url) {
        return new Builder(method, url);
    }

    public String getMethod() {
        return method;
    }

    public String getUrl() {
        return url;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public String getBody() {
        return body;
    }

    public String getContentType() {
        return contentType;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public int getFromPoolTimeoutMs() {
        return fromPoolTimeoutMs;
    }

    public RetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    /**
     * 是否带请求体。
     *
     * @return 是否有 body
     */
    public boolean hasBody() {
        return body != null && !body.isEmpty();
    }

    /**
     * 是否幂等（决定默认可否重试）。
     *
     * @return 是否幂等
     */
    public boolean isIdempotent() {
        return retryPolicy.isIdempotent();
    }

    @Override
    public String toString() {
        return method + ' ' + url + (hasBody() ? " (body " + body.length() + " chars)" : "");
    }

    /** Builder。 */
    public static final class Builder {

        private final String method;
        private final String url;
        private final Map<String, List<String>> headers = new LinkedHashMap<>();
        private String body;
        private String contentType = "application/json; charset=UTF-8";
        private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
        private int readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
        private int fromPoolTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
        private RetryPolicy retryPolicy;

        private Builder(String method, String url) {
            if (method == null || method.isBlank()) {
                throw new IllegalArgumentException("HTTP method 不能为空");
            }
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("URL 不能为空");
            }
            this.method = method.trim().toUpperCase(java.util.Locale.ROOT);
            this.url = url;
        }

        /**
         * 添加 header（同名多值按顺序追加）。
         *
         * @param name  名称
         * @param value 值
         * @return this
         */
        public Builder header(String name, String value) {
            if (name != null && value != null) {
                headers.computeIfAbsent(name, k -> new ArrayList<>(1)).add(value);
            }
            return this;
        }

        /**
         * 批量覆盖 header（值为 {@code null} 的项被忽略）。
         *
         * @param values header 集合
         * @return this
         */
        public Builder headers(Map<String, String> values) {
            if (values != null) {
                values.forEach(this::header);
            }
            return this;
        }

        /**
         * 设置请求体。
         *
         * @param body 请求体原文
         * @return this
         */
        public Builder body(String body) {
            this.body = body;
            return this;
        }

        /**
         * 设置 Content-Type。
         *
         * @param contentType 媒体类型
         * @return this
         */
        public Builder contentType(String contentType) {
            if (contentType != null && !contentType.isBlank()) {
                this.contentType = contentType;
            }
            return this;
        }

        /**
         * 设置超时。
         *
         * @param connectTimeoutMs 连接超时（毫秒）
         * @param readTimeoutMs    读超时（毫秒）
         * @return this
         */
        public Builder timeouts(int connectTimeoutMs, int readTimeoutMs) {
            if (connectTimeoutMs > 0) {
                this.connectTimeoutMs = connectTimeoutMs;
            }
            if (readTimeoutMs > 0) {
                this.readTimeoutMs = readTimeoutMs;
            }
            return this;
        }

        /**
         * 设置从连接池取连接的超时。
         *
         * @param fromPoolTimeoutMs 毫秒
         * @return this
         */
        public Builder fromPoolTimeout(int fromPoolTimeoutMs) {
            if (fromPoolTimeoutMs > 0) {
                this.fromPoolTimeoutMs = fromPoolTimeoutMs;
            }
            return this;
        }

        /**
         * 设置重试策略。
         *
         * @param retryPolicy 策略
         * @return this
         */
        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        /**
         * 构建。
         *
         * @return 请求描述
         */
        public HttpRequestSpec build() {
            return new HttpRequestSpec(this);
        }
    }
}
