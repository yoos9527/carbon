package com.carbon.framework.http;

/**
 * 退避序列：{@code baseMs × multiplier^n}，上限 {@code maxMs}。
 *
 * <p>默认 {@code 200ms × 2^n}，上限 2s——三方服务偶尔抖动时不该让调用方等太久，
 * 编排里的「等一分钟再试」是 `DELAY` 节点的职责，不是重试的职责。
 *
 * @param baseMs     首次退避基数（毫秒）
 * @param multiplier 倍数
 * @param maxMs      单次退避上限（毫秒）
 * @author carbon
 */
public record Backoff(long baseMs, double multiplier, long maxMs) {

    /** 默认退避：200ms 起，翻倍，封顶 2s。 */
    public static final Backoff DEFAULT = new Backoff(200L, 2.0d, 2000L);

    /**
     * 紧凑构造：入参兜底（防御负数与 0 倍）。
     */
    public Backoff {
        baseMs = Math.max(0L, baseMs);
        multiplier = multiplier <= 0 ? 1.0d : multiplier;
        maxMs = Math.max(baseMs, maxMs);
    }

    /**
     * 第 {@code attempt} 次重试前的等待时间（{@code attempt} 从 1 开始）。
     *
     * @param attempt 重试序号（1 = 第一次重试）
     * @return 毫秒
     */
    public long delayMs(int attempt) {
        if (attempt <= 0) {
            return 0L;
        }
        double raw = baseMs * Math.pow(multiplier, attempt - 1);
        if (raw >= maxMs) {
            return maxMs;
        }
        return (long) raw;
    }

    /**
     * 在第 {@code attempt} 次重试前睡眠。
     *
     * @param attempt 重试序号
     * @throws InterruptedException 被中断时（调用方应放弃重试并向上抛）
     */
    public void sleepBefore(int attempt) throws InterruptedException {
        long delay = delayMs(attempt);
        if (delay > 0) {
            Thread.sleep(delay);
        }
    }
}
