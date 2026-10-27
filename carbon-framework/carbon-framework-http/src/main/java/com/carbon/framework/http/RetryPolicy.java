package com.carbon.framework.http;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 重试策略。
 *
 * <p><b>默认不重试</b>（{@link #none()}）。这是刻意的：连接器被编排调用时，
 * 一次盲目重试可能等于重复下单。只有连接器上显式打开、且明确标记幂等时才重试。
 *
 * @author carbon
 */
public final class RetryPolicy {

    /** 默认可重试的状态码：限流 + 网关/服务端暂时性故障。 */
    public static final Set<Integer> DEFAULT_RETRY_STATUS = Set.of(429, 500, 502, 503, 504);

    /** 天然幂等的方法（无需额外声明即可重试）。 */
    private static final Set<String> IDEMPOTENT_METHODS = Set.of("GET", "HEAD", "PUT", "DELETE", "OPTIONS");

    private final int maxAttempts;
    private final Backoff backoff;
    private final boolean idempotent;
    private final Set<Integer> retryStatus;
    private final boolean retryOnIo;

    private RetryPolicy(int maxAttempts, Backoff backoff, boolean idempotent,
                        Set<Integer> retryStatus, boolean retryOnIo) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoff = backoff == null ? Backoff.DEFAULT : backoff;
        this.idempotent = idempotent;
        this.retryStatus = Collections.unmodifiableSet(new LinkedHashSet<>(retryStatus));
        this.retryOnIo = retryOnIo;
    }

    /**
     * 不重试（默认）。
     *
     * @return 策略
     */
    public static RetryPolicy none() {
        return new RetryPolicy(1, Backoff.DEFAULT, false, Set.of(), false);
    }

    /**
     * 最多尝试 {@code maxAttempts} 次（含首次）。
     *
     * @param maxAttempts 总尝试次数，1 表示不重试
     * @param idempotent  是否幂等（非幂等方法据此决定能否重试）
     * @return 策略
     */
    public static RetryPolicy of(int maxAttempts, boolean idempotent) {
        return new RetryPolicy(maxAttempts, Backoff.DEFAULT, idempotent, DEFAULT_RETRY_STATUS, true);
    }

    /**
     * Builder。
     *
     * @return Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Backoff getBackoff() {
        return backoff;
    }

    public boolean isIdempotent() {
        return idempotent;
    }

    public Set<Integer> getRetryStatus() {
        return retryStatus;
    }

    /**
     * 方法本身是否幂等（与策略无关的 HTTP 语义）。
     *
     * @param method HTTP 方法
     * @return 是否幂等
     */
    public static boolean isIdempotentMethod(String method) {
        return method != null && IDEMPOTENT_METHODS.contains(method.toUpperCase(Locale.ROOT));
    }

    /**
     * 已尝试 {@code attemptsDone} 次后，还剩次数吗？
     *
     * @param attemptsDone 已完成次数（含首次）
     * @return 是否可再试
     */
    public boolean allowsMore(int attemptsDone) {
        return attemptsDone < maxAttempts;
    }

    /**
     * 该方法在此策略下是否允许重试（非幂等且未声明幂等 → 一律不重试）。
     *
     * @param method HTTP 方法
     * @return 是否允许
     */
    public boolean permitsRetryFor(String method) {
        return isIdempotentMethod(method) || idempotent;
    }

    /**
     * 该状态码是否值得再试一次。
     *
     * @param method      HTTP 方法
     * @param attemptsDone 已完成次数（含首次）
     * @param status      响应状态码
     * @return 是否重试
     */
    public boolean shouldRetryStatus(String method, int attemptsDone, int status) {
        return permitsRetryFor(method)
                && allowsMore(attemptsDone)
                && retryStatus.contains(status);
    }

    /**
     * IO/超时类失败是否值得再试一次。
     *
     * @param method       HTTP 方法
     * @param attemptsDone 已完成次数（含首次）
     * @return 是否重试
     */
    public boolean shouldRetryIo(String method, int attemptsDone) {
        return retryOnIo && permitsRetryFor(method) && allowsMore(attemptsDone);
    }

    @Override
    public String toString() {
        return "RetryPolicy{maxAttempts=" + maxAttempts + ", idempotent=" + idempotent
                + ", retryStatus=" + retryStatus + ", retryOnIo=" + retryOnIo
                + ", backoff=" + backoff + '}';
    }

    /** Builder。 */
    public static final class Builder {

        private int maxAttempts = 1;
        private Backoff backoff = Backoff.DEFAULT;
        private boolean idempotent;
        private Set<Integer> retryStatus = DEFAULT_RETRY_STATUS;
        private boolean retryOnIo = true;

        /**
         * 总尝试次数（含首次）。
         *
         * @param maxAttempts 次数，小于 1 按 1 处理
         * @return this
         */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * 退避序列。
         *
         * @param backoff 退避
         * @return this
         */
        public Builder backoff(Backoff backoff) {
            this.backoff = backoff;
            return this;
        }

        /**
         * 声明幂等（POST/PATCH 想重试必须显式打开）。
         *
         * @param idempotent 是否幂等
         * @return this
         */
        public Builder idempotent(boolean idempotent) {
            this.idempotent = idempotent;
            return this;
        }

        /**
         * 可重试状态码集合。
         *
         * @param statuses 状态码
         * @return this
         */
        public Builder retryStatus(Set<Integer> statuses) {
            this.retryStatus = statuses == null ? Set.of() : statuses;
            return this;
        }

        /**
         * IO/超时是否重试。
         *
         * @param retryOnIo 是否重试
         * @return this
         */
        public Builder retryOnIo(boolean retryOnIo) {
            this.retryOnIo = retryOnIo;
            return this;
        }

        /**
         * 构建。
         *
         * @return 策略
         */
        public RetryPolicy build() {
            return new RetryPolicy(maxAttempts, backoff, idempotent, retryStatus, retryOnIo);
        }
    }
}
