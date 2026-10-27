package com.carbon.modules.invokelog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * `platform.log.*`：调用日志的写入与保留参数。
 *
 * <p>键名与设计文档 §11 一致（`platform.log.retention-days` 等）。之前这些值在
 * {@code AsyncLogWriter} 里是硬编码常量——文档写了旋钮、代码里拧不动，运维改配置没反应，
 * 这类「假配置项」比没有配置项更容易误导人，所以统一收到这里。
 *
 * <p>{@code retention-days} 的口径是**天**而不是「保留 N 个分区」：分区是月粒度，
 * 但保留期必须能按天讲清楚，否则 28 天的月与 31 天的月行为不一样。
 *
 * @author carbon
 */
@Component
@ConfigurationProperties(prefix = "platform.log")
public class LogProperties {

    /** 保留天数；≤0 表示永不清理（仍维护分区） */
    private int retentionDays = 30;

    private int asyncQueueCapacity = 10_000;

    private int asyncBatchSize = 200;

    private long asyncFlushIntervalMs = 1_000L;

    private final Partition partition = new Partition();

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public int getAsyncQueueCapacity() {
        return asyncQueueCapacity;
    }

    public void setAsyncQueueCapacity(int asyncQueueCapacity) {
        this.asyncQueueCapacity = asyncQueueCapacity;
    }

    public int getAsyncBatchSize() {
        return asyncBatchSize;
    }

    public void setAsyncBatchSize(int asyncBatchSize) {
        this.asyncBatchSize = asyncBatchSize;
    }

    public long getAsyncFlushIntervalMs() {
        return asyncFlushIntervalMs;
    }

    public void setAsyncFlushIntervalMs(long asyncFlushIntervalMs) {
        this.asyncFlushIntervalMs = asyncFlushIntervalMs;
    }

    public Partition getPartition() {
        return partition;
    }

    /** 分区维护参数（实现见 {@code partition/LogPartitionManager}） */
    public static class Partition {

        /** 总开关。关掉后只剩写入（全落 p_max），不再建分区也不再清理 */
        private boolean enabled = true;

        /** 提前建好未来几个月的分区（含当月） */
        private int aheadMonths = 2;

        /** 单批 DELETE 行数：清理老数据时避免一条语句锁表几分钟 */
        private int cleanupBatchSize = 5_000;

        /** 单次清理最多跑几批，防止首跑时把数据库长时间占住 */
        private int cleanupMaxBatches = 20;

        /** 每日维护时间。默认凌晨 3:10，避开业务高峰与整点备份 */
        private String cron = "0 10 3 * * ?";

        /** 启动时是否立即建一次分区（新库当天就该有当月分区，而不是等到凌晨） */
        private boolean maintainOnStartup = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getAheadMonths() {
            return aheadMonths;
        }

        public void setAheadMonths(int aheadMonths) {
            this.aheadMonths = aheadMonths;
        }

        public int getCleanupBatchSize() {
            return cleanupBatchSize;
        }

        public void setCleanupBatchSize(int cleanupBatchSize) {
            this.cleanupBatchSize = cleanupBatchSize;
        }

        public int getCleanupMaxBatches() {
            return cleanupMaxBatches;
        }

        public void setCleanupMaxBatches(int cleanupMaxBatches) {
            this.cleanupMaxBatches = cleanupMaxBatches;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public boolean isMaintainOnStartup() {
            return maintainOnStartup;
        }

        public void setMaintainOnStartup(boolean maintainOnStartup) {
            this.maintainOnStartup = maintainOnStartup;
        }
    }

    /** 清理截止日；{@code retentionDays <= 0} 返回 null 表示不清理 */
    public LocalDate cutoffDate() {
        return retentionDays <= 0 ? null : LocalDate.now().minusDays(retentionDays);
    }
}
