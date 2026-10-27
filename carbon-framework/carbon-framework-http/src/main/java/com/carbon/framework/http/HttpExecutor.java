package com.carbon.framework.http;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP 传输层：发起请求、按策略重试、按上限截断响应。
 *
 * <p>只负责「把请求发出去、把响应收回来」。鉴权、模板渲染、结果抽取都不在这里。
 *
 * <p><b>重试语义</b>：默认不重试；重试只发生在「策略允许 + 方法幂等（或显式标记幂等）」时，
 * 且仅针对白名单状态码与 IO/超时失败。重试次数用尽后返回<b>最后一次响应</b>（仍是 {@link HttpResult}），
 * 不把 5xx 伪装成异常——上层据此判定 {@code PROVIDER_HTTP_5XX}。
 *
 * @author carbon
 */
public class HttpExecutor {

    private final CloseableHttpClient httpClient;
    private final int maxResponseBytes;

    /**
     * 构造。
     *
     * @param httpClient       已构造好的客户端（<b>必须</b>关闭自动重试与自动重定向）
     * @param maxResponseBytes 单次响应体上限（字节），≤0 用默认 5MB
     */
    public HttpExecutor(CloseableHttpClient httpClient, int maxResponseBytes) {
        if (httpClient == null) {
            throw new IllegalArgumentException("httpClient 不能为 null");
        }
        this.httpClient = httpClient;
        this.maxResponseBytes = maxResponseBytes > 0
                ? maxResponseBytes : ResponseTruncator.DEFAULT_MAX_RESPONSE_BYTES;
    }

    /**
     * 执行请求。
     *
     * @param spec 请求描述
     * @return 最后一次的响应
     * @throws HttpExecuteException 传输层失败且重试未救回
     */
    public HttpResult execute(HttpRequestSpec spec) {
        RetryPolicy policy = spec.getRetryPolicy();
        int attempts = 0;
        long totalCostMs = 0L;
        HttpResult lastResult = null;
        HttpExecuteException lastError = null;

        while (attempts < policy.getMaxAttempts()) {
            attempts++;
            try {
                HttpResult current = executeOnce(spec, attempts);
                totalCostMs += current.getCostMs();
                lastResult = current;
                if (policy.shouldRetryStatus(spec.getMethod(), attempts, current.getStatus())) {
                    sleepBefore(policy, attempts, spec);
                    continue;
                }
                return current.withTotals(totalCostMs, attempts);
            } catch (HttpExecuteException e) {
                lastError = e;
                if (!policy.shouldRetryIo(spec.getMethod(), attempts)) {
                    throw e;
                }
                sleepBefore(policy, attempts, spec);
            }
        }
        if (lastResult != null) {
            return lastResult.withTotals(totalCostMs, attempts);
        }
        throw lastError == null ? HttpExecuteException.ioError(spec.getUrl(), null) : lastError;
    }

    /**
     * 单次尝试。
     *
     * @param spec    请求描述
     * @param attempt 当前第几次（从 1 开始）
     * @return 响应
     * @throws HttpExecuteException 传输层失败
     */
    protected HttpResult executeOnce(HttpRequestSpec spec, int attempt) {
        ClassicHttpRequest request = buildRequest(spec);
        long start = System.nanoTime();
        try {
            HttpResult raw = httpClient.execute(request,
                    response -> readResponse(response.getCode(), response.getHeaders(), response.getEntity()));
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            return new HttpResult(raw.getStatus(), raw.getHeaders(), raw.getBody(), raw.isTruncated(),
                    raw.getBodyBytes(), raw.getContentLength(), costMs, attempt);
        } catch (ConnectionRequestTimeoutException e) {
            throw HttpExecuteException.connectError(spec.getUrl(), e);
        } catch (ConnectTimeoutException e) {
            throw HttpExecuteException.connectTimeout(spec.getUrl(), e);
        } catch (InterruptedIOException e) {
            // SocketTimeoutException 也在此列；非超时的中断单独归类
            throw e instanceof java.net.SocketTimeoutException || isTimeoutMessage(e)
                    ? HttpExecuteException.readTimeout(spec.getUrl(), e)
                    : HttpExecuteException.ioError(spec.getUrl(), e);
        } catch (IOException e) {
            throw classify(spec.getUrl(), e);
        }
    }

    private ClassicHttpRequest buildRequest(HttpRequestSpec spec) {
        HttpUriRequestBase request = new HttpUriRequestBase(spec.getMethod(), URI.create(spec.getUrl()));
        if (spec.hasBody()) {
            request.setEntity(new StringEntity(spec.getBody(), ContentType.parse(spec.getContentType())));
        }
        spec.getHeaders().forEach((name, values) -> values.forEach(value -> request.addHeader(name, value)));
        request.setConfig(RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(spec.getConnectTimeoutMs()))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(spec.getFromPoolTimeoutMs()))
                .setResponseTimeout(Timeout.ofMilliseconds(spec.getReadTimeoutMs()))
                .build());
        return request;
    }

    private HttpResult readResponse(int status, Header[] headerArray, HttpEntity entity) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        if (headerArray != null) {
            for (Header header : headerArray) {
                headers.computeIfAbsent(header.getName().toLowerCase(Locale.ROOT),
                        key -> new ArrayList<>(2)).add(header.getValue());
            }
        }
        if (entity == null) {
            return new HttpResult(status, headers, "", false, 0, 0L, 0L, 1);
        }
        // 多读 1 字节，好让 ResponseTruncator 判定「确实超限」
        int ceiling = maxResponseBytes + 1;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(8192, ceiling));
        int total = 0;
        try (InputStream in = entity.getContent()) {
            byte[] chunk = new byte[8192];
            int read;
            while (total < ceiling && (read = in.read(chunk, 0, Math.min(chunk.length, ceiling - total))) != -1) {
                buffer.write(chunk, 0, read);
                total += read;
            }
        }
        ResponseTruncator.Result text = ResponseTruncator.of(buffer.toByteArray(), maxResponseBytes);
        return new HttpResult(status, headers, text.text(), text.truncated(), text.byteLength(),
                entity.getContentLength(), 0L, 1);
    }

    private void sleepBefore(RetryPolicy policy, int attempt, HttpRequestSpec spec) {
        try {
            policy.getBackoff().sleepBefore(attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw HttpExecuteException.ioError(spec.getUrl(), e);
        }
    }

    private boolean isTimeoutMessage(Throwable cause) {
        String message = cause.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("timeout") || lower.contains("timed out");
    }

    private HttpExecuteException classify(String url, Throwable cause) {
        if (cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException) {
            return HttpExecuteException.connectError(url, cause);
        }
        if (isTimeoutMessage(cause)) {
            return HttpExecuteException.readTimeout(url, cause);
        }
        return HttpExecuteException.ioError(url, cause);
    }

    /**
     * 关闭底层客户端（容器销毁时调用）。
     *
     * @throws IOException 关闭失败
     */
    public void close() throws IOException {
        httpClient.close();
    }
}
