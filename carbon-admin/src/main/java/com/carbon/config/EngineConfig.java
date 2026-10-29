package com.carbon.config;

import com.carbon.common.json.JsonUtils;
import com.carbon.framework.http.HttpExecutor;
import com.carbon.framework.script.GroovySandbox;
import com.carbon.framework.script.GroovySandboxOptions;
import com.carbon.modules.engine.credential.CredentialResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 运行时执行器的装配：HTTP 连接池、脚本沙箱、鉴权解析器。
 *
 * <p>三个连接池参数这样定的理由：
 * <ul>
 *   <li>{@code maxTotal=500}：连接器平台同时要打几十个三方，池子小了会互相排队；</li>
 *   <li>{@code defaultMaxPerRoute=50}：单个三方吃满整池是最常见的故障形态（比如对方限流后大量超时重试）；</li>
 *   <li>{@code connectionRequestTimeout=500ms}：拿不到连接就**快速失败**，不要把 Tomcat 线程挂在这里等。</li>
 * </ul>
 *
 * <p>{@code disableAutomaticRetries()} 与 {@code disableRedirectHandling()} 是必须显式关闭的：
 * HttpClient 的默认行为是「幂等请求自动重试 3 次」和「自动跟随 302」，
 * 两者都与本平台的策略相反——重试由 {@code RetryPolicy} 统一管，
 * 重定向则会把带鉴权的请求头泄漏到第三方域。
 *
 * @author carbon
 */
@Configuration
public class EngineConfig {

    @Bean(destroyMethod = "close")
    public HttpExecutor httpExecutor(
            @Value("${carbon.http.max-total:500}") int maxTotal,
            @Value("${carbon.http.max-per-route:50}") int maxPerRoute,
            @Value("${carbon.http.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${carbon.http.pool-timeout-ms:500}") int poolTimeoutMs,
            @Value("${carbon.http.max-response-bytes:65536}") int maxResponseBytes) {

        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                        .setSocketTimeout(Timeout.ofMilliseconds(60_000))
                        .setValidateAfterInactivity(TimeValue.ofSeconds(2))
                        .build())
                .setMaxConnTotal(maxTotal)
                .setMaxConnPerRoute(maxPerRoute)
                .build();

        CloseableHttpClient client = HttpClientBuilder.create()
                .setConnectionManager(connectionManager)
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .setUserAgent("carbon-connector-platform/1.0")
                .build();

        return new HttpExecutor(client, maxResponseBytes);
    }

    @Bean(destroyMethod = "close")
    public GroovySandbox groovySandbox(@Value("${carbon.script.enabled:true}") boolean enabled) {
        GroovySandboxOptions options = new GroovySandboxOptions(
                enabled,
                GroovySandboxOptions.DEFAULT_TIMEOUT_MS,
                GroovySandboxOptions.DEFAULT_CACHE_MAX,
                GroovySandboxOptions.DEFAULT_CACHE_TTL_MS);
        return new GroovySandbox(options, JsonUtils.mapper());
    }

    @Bean
    public CredentialResolver credentialResolver() {
        return new CredentialResolver();
    }
}
