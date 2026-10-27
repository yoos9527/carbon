/**
 * 缓存与分布式协同：{@link com.carbon.common.redis.RedisUtils}（带 TTL 的 KV），
 * 其余能力（分布式锁、限流、幂等锁）直接用 Redisson。
 *
 * <p>依赖由 {@code redisson-spring-boot-starter} 提供，本模块不含自动配置——
 * 连接参数统一写在各环境的 {@code spring.data.redis.*} 里，不在代码里再发明一套。
 */
package com.carbon.common.redis;
