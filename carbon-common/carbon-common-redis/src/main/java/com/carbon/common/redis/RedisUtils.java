package com.carbon.common.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis 的最小可用封装。
 *
 * <p>刻意<b>只包这几个方法</b>：平台真正用到的 Redis 语义只有「带 TTL 的 setIfAbsent」
 * （限流计数、nonce 去重、提交去重）与普通读写。Redisson 的分布式锁、布隆过滤器、
 * 限流器都在各自调用点直接用 {@code RedissonClient}——把它们也包一层，
 * 只会让「这行到底用了 Redis 什么能力」变得难查。
 *
 * <p>为什么用 {@code StringRedisTemplate} 而不是 Redisson 的 {@code RBucket}：
 * 这两个模块（幂等、限流兜底）要的是最简单、最容易在 redis-cli 里肉眼验证的形态——
 * {@code GET key} 就能看到值，不需要知道 Redisson 的序列化格式。
 *
 * <p>Redis 不可用时<b>不吞异常</b>：调用方（如 {@code @RepeatSubmit}）需要知道
 * 「这次去重没生效」并自行决定是放行还是拒绝。分布式协同组件的降级策略
 * 应该由业务决定，而不是在工具层偷偷放行。
 *
 * @author carbon
 */
@Component
public class RedisUtils {

    private final StringRedisTemplate redis;

    public RedisUtils(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * SET key value NX PX ttl。
     *
     * @return true 表示这次抢占了 key（首次），false 表示 key 已存在（重复）
     */
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        Boolean ok = redis.opsForValue().setIfAbsent(key, value, ttl);
        return Boolean.TRUE.equals(ok);
    }

    public void set(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key));
    }

    /**
     * 删除 key。
     *
     * @return true 表示确实删掉了一个 key
     */
    public boolean delete(String key) {
        return Boolean.TRUE.equals(redis.delete(key));
    }

    /** 自增并保证首次创建时带上 TTL（限流计数用；TTL 只在 key 不存在时设置） */
    public long increment(String key, Duration ttlOnCreate) {
        Long value = redis.opsForValue().increment(key);
        long count = value == null ? 0L : value;
        if (count == 1L) {
            redis.expire(key, ttlOnCreate);
        }
        return count;
    }

    public boolean isAvailable() {
        try {
            return redis.getConnectionFactory() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
