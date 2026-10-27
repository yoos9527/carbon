package com.carbon.modules.gateway.gw;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * nonce 防重放。
 *
 * <p>语义是「一个 nonce 在一个时间窗内只能用一次」，实现靠 Redis 的 {@code SET NX PX}：
 * 原子性由 Redis 保证，不用先 GET 再 SET（那中间有竞态，两个并发重放都能过）。
 *
 * <p>TTL 取**时间窗的 2 倍**而不是等于时间窗：时间窗边缘的请求可能已经在路上飘了几十毫秒，
 * 如果 TTL 与窗口严格相等，会出现「时间戳还有效、但 nonce 已经过期」的缝隙，
 * 恰好让重放挤进去。多留一倍窗口的代价只是多占一点内存。
 *
 * <p>Redis 不可用时的取舍：**放行**（fail-open）还是**拒绝**（fail-closed）？
 * 这里选拒绝——网关是对外入口，防重放属于安全边界，宁可短暂不可用也不能开个洞。
 *
 * @author carbon
 */
@Component
public class NonceReplayGuard {

    private static final String KEY_PREFIX = "gw:nonce:";

    private final RedissonClient redissonClient;
    private final Duration ttl;

    public NonceReplayGuard(RedissonClient redissonClient,
                            @org.springframework.beans.factory.annotation.Value(
                                    "${carbon.gateway.nonce-ttl:10m}") Duration ttl) {
        this.redissonClient = redissonClient;
        this.ttl = ttl;
    }

    /**
     * 占用 nonce。
     *
     * @return {@code true} 表示首次出现（放行），{@code false} 表示重放（拒绝）
     */
    public boolean tryAcquire(String appKey, String nonce) {
        RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + appKey + ":" + nonce);
        return bucket.trySet("1", ttl.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }
}
