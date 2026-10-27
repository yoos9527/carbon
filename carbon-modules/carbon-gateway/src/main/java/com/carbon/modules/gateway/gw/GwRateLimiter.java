package com.carbon.modules.gateway.gw;

import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 应用级 QPS 限流。
 *
 * <p>用的是最朴素的**固定窗口计数**，不是令牌桶。理由：网关只需要挡住「脚本跑飞了」
 * 这种数量级的流量，固定窗口在窗口边界最多放过 2 倍上限，对一个内部平台的火山式突刺
 * 完全够用；而令牌桶要么牺牲精度（提前预取令牌）、要么每个请求多一次 Lua 往返。
 * 真要精确限流，应该上网关层（Nginx/APISIX），不该在业务进程里做。
 *
 * <p>key 里带 epoch 秒而不是靠 INCR 的 TTL 自增：后者在窗口内每来一个请求就刷新一次
 * TTL，最终这个 key 永不过期，属于经典的内存泄漏写法。
 *
 * @author carbon
 */
@Component
public class GwRateLimiter {

    private static final String KEY_PREFIX = "gw:rl:";

    private final RedissonClient redissonClient;

    public GwRateLimiter(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 尝试消耗一个配额。
     *
     * @param qps 每秒上限，{@code <= 0} 视为不限流
     * @return {@code true} 放行
     */
    public boolean tryAcquire(String appKey, int qps) {
        if (qps <= 0) {
            return true;
        }
        long second = Instant.now().getEpochSecond();
        RAtomicLong counter = redissonClient.getAtomicLong(KEY_PREFIX + appKey + ":" + second);
        long current = counter.incrementAndGet();
        if (current == 1L) {
            counter.expire(Duration.ofSeconds(2));
        }
        return current <= qps;
    }
}
