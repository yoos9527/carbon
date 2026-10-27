package com.carbon.framework.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RetryPolicy} / {@link Backoff} / {@link ResponseTruncator} 的纯逻辑用例。
 *
 * @author carbon
 */
class RetryPolicyTest {

    @Test
    @DisplayName("默认策略：只尝试一次、不重试 IO")
    void noneMeansNoRetry() {
        RetryPolicy policy = RetryPolicy.none();
        assertEquals(1, policy.getMaxAttempts());
        assertFalse(policy.allowsMore(1));
        assertFalse(policy.shouldRetryIo("GET", 1));
        assertFalse(policy.shouldRetryStatus("GET", 1, 503));
    }

    @Test
    @DisplayName("GET 天然幂等，可在白名单状态码上重试")
    void getRetriesOnWhitelistedStatus() {
        RetryPolicy policy = RetryPolicy.of(3, false);
        assertTrue(policy.shouldRetryStatus("GET", 1, 503));
        assertTrue(policy.shouldRetryStatus("GET", 2, 429));
        assertFalse(policy.shouldRetryStatus("GET", 3, 503));
        assertFalse(policy.shouldRetryStatus("GET", 1, 400));
    }

    @Test
    @DisplayName("POST 未声明幂等时一律不重试（防重复下单）")
    void postWithoutIdempotentNeverRetries() {
        RetryPolicy policy = RetryPolicy.of(3, false);
        assertFalse(policy.shouldRetryStatus("POST", 1, 500));
        assertFalse(policy.shouldRetryIo("POST", 1));
    }

    @Test
    @DisplayName("POST 显式声明幂等后才可重试")
    void postWithIdempotentCanRetry() {
        RetryPolicy policy = RetryPolicy.of(3, true);
        assertTrue(policy.shouldRetryStatus("POST", 1, 500));
        assertTrue(policy.shouldRetryIo("POST", 1));
    }

    @Test
    @DisplayName("可重试集合可换成空集，等于关闭状态码重试")
    void emptyStatusSetDisablesStatusRetry() {
        RetryPolicy policy = RetryPolicy.builder().maxAttempts(3).retryStatus(Set.of()).build();
        assertFalse(policy.shouldRetryStatus("GET", 1, 503));
        assertTrue(policy.shouldRetryIo("GET", 1));
    }

    @Test
    @DisplayName("退避：200 → 400 → 800 → 1600 → 封顶 2000")
    void backoffDoublesUntilCap() {
        Backoff backoff = Backoff.DEFAULT;
        assertEquals(200L, backoff.delayMs(1));
        assertEquals(400L, backoff.delayMs(2));
        assertEquals(800L, backoff.delayMs(3));
        assertEquals(1600L, backoff.delayMs(4));
        assertEquals(2000L, backoff.delayMs(5));
        assertEquals(0L, backoff.delayMs(0));
    }

    @Test
    @DisplayName("退避可关掉（base=0），便于测试与本地调试")
    void zeroBackoffIsAllowed() {
        Backoff backoff = new Backoff(0L, 2.0d, 0L);
        assertEquals(0L, backoff.delayMs(3));
    }

    @Test
    @DisplayName("未超限不截断，超限加标记")
    void truncatorKeepsSmallBody() {
        ResponseTruncator.Result small = ResponseTruncator.of("hello", 64);
        assertFalse(small.truncated());
        assertEquals("hello", small.text());
        assertEquals(5, small.byteLength());

        ResponseTruncator.Result big = ResponseTruncator.of("x".repeat(100), 10);
        assertTrue(big.truncated());
        assertTrue(big.text().endsWith(ResponseTruncator.MARKER));
        assertEquals(100, big.byteLength());
        assertEquals(10 + ResponseTruncator.MARKER.length(), big.text().length());
    }

    @Test
    @DisplayName("截断不切断 UTF-8 多字节字符（中文 3 字节，限 4 字节只留 1 个字符）")
    void truncatorNeverSplitsUtf8Char() {
        ResponseTruncator.Result result = ResponseTruncator.of("中文内容", 4);
        assertTrue(result.truncated());
        assertEquals("中" + ResponseTruncator.MARKER, result.text());
    }

    @Test
    @DisplayName("空值安全")
    void truncatorHandlesEmpty() {
        assertFalse(ResponseTruncator.of((String) null, 10).truncated());
        assertEquals("", ResponseTruncator.of((String) null, 10).text());
        assertEquals(0, ResponseTruncator.of(new byte[0], 10).byteLength());
    }
}
