package com.carbon.framework.http;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;

/**
 * 连接池客户端工厂：把「平台级默认值」一次性定死。
 *
 * <p>两处默认值<b>必须</b>反向覆盖，否则与平台策略冲突：
 * <ul>
 *   <li>{@code disableAutomaticRetries()} —— HttpClient 默认对幂等方法自动重试，
 *       会让「默认 0 重试」的设计失效，还会让调用日志里的 attempts 变成谎言；</li>
 *   <li>{@code disableRedirectHandling()} —— 默认会跟随 302，这对 SSRF 校验与签名协议都是漏洞。</li>
 * </ul>
 *
 * @author carbon
 */
public final class HttpClientFactory {

    private HttpClientFactory() {
    }

    /**
     * 用平台默认参数创建客户端（池 500/单路由 50、连接 3s、读 10s）。
     *
     * @return 客户端（调用方负责关闭）
     */
    public static CloseableHttpClient createDefault() {
        return create(HttpClientOptions.defaults());
    }

    /**
     * 创建客户端。
     *
     * @param options 参数
     * @return 客户端（调用方负责关闭）
     */
    public static CloseableHttpClient create(HttpClientOptions options) {
        HttpClientOptions opt = options == null ? HttpClientOptions.defaults() : options;
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(opt.connectTimeoutMs()))
                .setSocketTimeout(Timeout.ofMilliseconds(opt.readTimeoutMs()))
                .build();
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(opt.maxConnTotal())
                .setMaxConnPerRoute(opt.maxConnPerRoute())
                .setDefaultConnectionConfig(connectionConfig)
                .build();
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(opt.connectTimeoutMs()))
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(opt.connectTimeoutMs()))
                        .setResponseTimeout(Timeout.ofMilliseconds(opt.readTimeoutMs()))
                        .build())
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .evictExpiredConnections()
                .evictIdleConnections(Timeout.ofSeconds(30))
                .build();
    }

    /**
     * 客户端参数。
     *
     * @param maxConnTotal      全池最大连接数
     * @param maxConnPerRoute   单目标最大连接数
     * @param connectTimeoutMs  建连超时（毫秒）
     * @param readTimeoutMs     读超时（毫秒）
     */
    public record HttpClientOptions(int maxConnTotal, int maxConnPerRoute, int connectTimeoutMs, int readTimeoutMs) {

        /**
         * 平台默认：500 / 50 / 3000 / 10000（对齐 §11）。
         *
         * @return 参数
         */
        public static HttpClientOptions defaults() {
            return new HttpClientOptions(500, 50,
                    HttpRequestSpec.DEFAULT_CONNECT_TIMEOUT_MS, HttpRequestSpec.DEFAULT_READ_TIMEOUT_MS);
        }
    }
}
