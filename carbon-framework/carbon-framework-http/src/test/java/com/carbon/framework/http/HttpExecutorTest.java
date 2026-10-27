package com.carbon.framework.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpExecutor} 端到端用例：用 JDK 自带 {@link HttpServer} 起本地对端，不依赖 Spring、不依赖外部网络。
 *
 * @author carbon
 */
class HttpExecutorTest {

    private HttpServer server;
    private volatile String baseUrl;
    private final AtomicInteger flakyHits = new AtomicInteger();
    private HttpExecutor executor;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-Trace", "abc123");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/echo", exchange -> {
            String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String received = exchange.getRequestHeaders().getFirst("X-Client");
            byte[] resp = (method + "|" + body + "|" + received).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(resp);
            }
        });
        server.createContext("/flaky", exchange -> {
            int hit = flakyHits.incrementAndGet();
            byte[] body = ("hit-" + hit).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(hit == 1 ? 503 : 200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/always-503", exchange -> {
            byte[] body = "down".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(600L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "late".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl + "/ok");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/big", exchange -> {
            byte[] body = ("y".repeat(4096)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        executor = new HttpExecutor(HttpClientFactory.createDefault(), 1024);
    }

    @AfterEach
    void tearDown() throws IOException {
        executor.close();
        server.stop(0);
    }

    @Test
    @DisplayName("200：返回状态、body、响应头，attempts=1")
    void successReturnsBodyAndHeaders() {
        HttpResult result = executor.execute(HttpRequestSpec.builder("GET", baseUrl + "/ok").build());

        assertEquals(200, result.getStatus());
        assertEquals("{\"ok\":true}", result.getBody());
        assertTrue(result.isSuccess());
        assertEquals(1, result.getAttempts());
        assertEquals("abc123", result.header("X-Trace").orElse(null));
        assertNull(result.header("x-missing").orElse(null));
        assertTrue(result.getCostMs() >= 0);
    }

    @Test
    @DisplayName("POST 带 body 与自定义头原样送达")
    void postSendsBodyAndHeaders() {
        HttpRequestSpec spec = HttpRequestSpec.builder("POST", baseUrl + "/echo")
                .header("X-Client", "carbon")
                .body("{\"a\":1}")
                .build();

        HttpResult result = executor.execute(spec);

        assertEquals("POST|{\"a\":1}|carbon", result.getBody());
    }

    @Test
    @DisplayName("503 后重试成功：attempts=2，拿到第二次的 200")
    void retriesOnWhitelistedStatus() {
        flakyHits.set(0);
        HttpRequestSpec spec = HttpRequestSpec.builder("GET", baseUrl + "/flaky")
                .retryPolicy(RetryPolicy.builder().maxAttempts(2).backoff(new Backoff(0L, 2.0d, 0L)).build())
                .build();

        HttpResult result = executor.execute(spec);

        assertEquals(200, result.getStatus());
        assertEquals("hit-2", result.getBody());
        assertEquals(2, result.getAttempts());
        assertEquals(2, flakyHits.get());
    }

    @Test
    @DisplayName("非幂等 POST 即使 503 也不重试：只打一次，返回 503")
    void nonIdempotentPostDoesNotRetry() {
        HttpRequestSpec spec = HttpRequestSpec.builder("POST", baseUrl + "/always-503")
                .retryPolicy(RetryPolicy.builder().maxAttempts(3)
                        .backoff(new Backoff(0L, 2.0d, 0L)).build())
                .build();

        HttpResult result = executor.execute(spec);

        assertEquals(503, result.getStatus());
        assertEquals(1, result.getAttempts());
        assertTrue(result.isServerError());
    }

    @Test
    @DisplayName("302 不自动跟随：直接返回 302 与 Location")
    void redirectIsNotFollowed() {
        HttpResult result = executor.execute(HttpRequestSpec.builder("GET", baseUrl + "/redirect").build());

        assertEquals(302, result.getStatus());
        assertEquals(baseUrl + "/ok", result.header("Location").orElse(null));
    }

    @Test
    @DisplayName("读超时抛 HttpExecuteException(READ_TIMEOUT)，默认策略不重试")
    void readTimeoutThrows() {
        HttpRequestSpec spec = HttpRequestSpec.builder("GET", baseUrl + "/slow")
                .timeouts(2000, 100)
                .build();

        HttpExecuteException ex = assertThrows(HttpExecuteException.class, () -> executor.execute(spec));

        assertEquals(HttpExecuteException.READ_TIMEOUT, ex.getReason());
        assertTrue(ex.isTimeout());
    }

    @Test
    @DisplayName("连接失败抛 CONNECT_ERROR")
    void connectRefusedThrows() {
        // 关掉服务后再打，端口必定不可连
        server.stop(0);
        HttpRequestSpec spec = HttpRequestSpec.builder("GET", baseUrl + "/ok")
                .timeouts(300, 300)
                .build();

        HttpExecuteException ex = assertThrows(HttpExecuteException.class, () -> executor.execute(spec));

        assertEquals(HttpExecuteException.CONNECT_ERROR, ex.getReason());
        assertTrue(ex.getMessage().contains("连接失败"));
    }

    @Test
    @DisplayName("响应超上限被截断并打标记，byteLength 记原始长度")
    void oversizedResponseIsTruncated() {
        HttpResult result = executor.execute(HttpRequestSpec.builder("GET", baseUrl + "/big").build());

        assertTrue(result.isTruncated());
        assertEquals(4096, result.getContentLength());
        assertEquals(4096, result.getPayloadSize());
        assertTrue(result.getBodyBytes() <= 1025);
        assertTrue(result.getBody().endsWith(ResponseTruncator.MARKER));
        assertTrue(result.getBody().length() < 4096);
    }

    @Test
    @DisplayName("空 body 的请求不带 entity，仍能正常返回")
    void emptyBodyRequestWorks() {
        HttpResult result = executor.execute(HttpRequestSpec.builder("GET", baseUrl + "/echo").build());

        assertEquals("GET||null", result.getBody());
    }
}
