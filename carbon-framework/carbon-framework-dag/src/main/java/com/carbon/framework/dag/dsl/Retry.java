package com.carbon.framework.dag.dsl;

/**
 * 节点级重试。
 *
 * <p>LiteFlow 的 {@code .retry(n)} 只有次数没有退避，{@code backoffMs} 由组件内部 sleep 实现；
 * 默认 {@code maxAttempts=1} 表示<b>不重试</b>——编排里重复下单是生产事故，
 * 想重试必须显式声明，且非幂等接口还要求 connector 侧标 {@code idempotent=true}。
 *
 * @param maxAttempts 总尝试次数（≥1，1 表示不重试）
 * @param backoffMs   两次尝试之间的固定等待毫秒
 * @author carbon
 */
public record Retry(int maxAttempts, long backoffMs) {

    /** 默认不重试。 */
    public static final Retry NONE = new Retry(1, 0L);

    /** 上限，防止把流程超时拖垮。 */
    public static final int MAX_ATTEMPTS_LIMIT = 10;

    public Retry {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("retry.maxAttempts 必须 ≥ 1");
        }
        if (backoffMs < 0) {
            throw new IllegalArgumentException("retry.backoffMs 不能为负");
        }
    }

    /** @return 是否需要重试 */
    public boolean enabled() {
        return maxAttempts > 1;
    }

    /** @return 等价于 null 安全的默认值 */
    public static Retry orDefault(Retry retry) {
        return retry == null ? NONE : retry;
    }
}
