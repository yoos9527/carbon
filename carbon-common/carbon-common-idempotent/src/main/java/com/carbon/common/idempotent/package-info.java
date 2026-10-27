/**
 * 提交去重：{@code @RepeatSubmit} + {@code RepeatSubmitAspect}（基于 Redis 的短窗口去重）。
 *
 * <p>只解决「同一个操作被连打两次」，不做严格的分布式幂等——
 * 严格幂等要靠业务唯一键，那是业务语义，平台不替业务决定。实现细节见
 * {@link com.carbon.common.idempotent.RepeatSubmitAspect}。
 */
package com.carbon.common.idempotent;
