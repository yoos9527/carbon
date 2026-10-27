package com.carbon.framework.dag.dsl.node;


/**
 * 延时节点：固定 {@code delayMs}，或 {@code randomMinMs..randomMaxMs} 区间随机。
 *
 * @param id           节点 id
 * @param delayMs      固定延时毫秒（与随机区间二选一）
 * @param randomMinMs  随机下限
 * @param randomMaxMs  随机上限
 * @author carbon
 */
public record DelayNode(String id, Long delayMs, Long randomMinMs, Long randomMaxMs) implements NodeDef {

    public DelayNode {
        Nodes.requireId(id, "DELAY");
        boolean fixed = delayMs != null && delayMs > 0;
        boolean random = randomMinMs != null && randomMaxMs != null && randomMaxMs >= randomMinMs;
        if (!fixed && !random) {
            throw new IllegalArgumentException("DELAY 必须给 delayMs 或 randomMinMs/randomMaxMs（node=" + id + "）");
        }
    }

    /** @return 本次应延时的毫秒数 */
    public long resolveMillis() {
        if (delayMs != null && delayMs > 0) {
            return delayMs;
        }
        long min = Math.max(0, randomMinMs == null ? 0 : randomMinMs);
        long max = Math.max(min, randomMaxMs == null ? min : randomMaxMs);
        return min == max ? min : min + (long) (Math.random() * (max - min + 1));
    }
}
