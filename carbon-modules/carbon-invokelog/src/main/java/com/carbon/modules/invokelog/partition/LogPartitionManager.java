package com.carbon.modules.invokelog.partition;

import com.carbon.modules.invokelog.config.LogProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code invoke_log} 的分区维护与保留清理。
 *
 * <p><b>为什么必须有这个类</b>：建表时只给了 {@code p_init} 与 {@code p_max(MAXVALUE)}。
 * 有 {@code p_max} 兜底，写入永远不报错——代价是所有日志都堆在 {@code p_max} 里，分区形同虚设。
 * 等到真按天增长到几百 GB 再想按月切，就只能 {@code REORGANIZE} 一张巨表（长时间锁表、磁盘
 * 需求翻倍）。所以维护必须在数据还少的时候就开始跑。
 *
 * <p><b>三个刻意的实现选择</b>：
 * <ol>
 *   <li><b>集群锁用 MySQL 命名锁，不用 Redis 锁</b>：{@code GET_LOCK} 与 DDL 在**同一条连接**
 *       上，连接断了锁就没了，不存在「Redisson 租期到了但 DDL 还在跑」的窗口；也不用给这个
 *       叶子模块加一个 Redis 依赖。DDL 本来就是 MySQL 方言，锁跟着方言走最省心。</li>
 *   <li><b>每一步都单独 try/catch</b>：一次维护里可能建 3 个月分区 + 删 2 个老分区，其中任何
 *       一步失败（比如别人手工建过同名分区）都不该让其余步骤一起放弃，更不该让调度线程死掉。</li>
 *   <li><b>清理分两段</b>：整月过期的先 {@code DROP PARTITION}（秒级、不产生 undo），
 *       剩下的边界月用 {@code DELETE ... LIMIT} 分批删（限批数，避免首跑把库占住）。</li>
 * </ol>
 *
 * <p>时间口径：月边界一律 {@code 月初 00:00}，用 {@code RANGE COLUMNS(created_at)} 的字符串
 * 比较，不涉及任何时区换算；「今天是几号」取自 JVM 默认时区，已在 {@code CarbonApplication}
 * 钉成 {@code Asia/Shanghai}。
 *
 * @author carbon
 */
@Component
public class LogPartitionManager {

    private static final Logger log = LoggerFactory.getLogger(LogPartitionManager.class);

    /**
     * 合法分区名：{@code p_init} / {@code p_max} / {@code pYYYYMM}。
     *
     * <p>名字会被拼进 DDL（{@code ALTER TABLE … DROP PARTITION <name>}），所以必须白名单校验；
     * 注意下划线——写成 {@code p(init|max|\d{6})} 会把内置的 {@code p_init}/{@code p_max}
     * 全部判为非法，启动时表现为「不是分区表」。
     */
    private static final Pattern PARTITION_NAME = Pattern.compile("p(_init|_max|\\d{6})");

    private static final String TABLE = "invoke_log";
    private static final String LOCK_NAME = "carbon:invoke_log:partition";
    private static final DateTimeFormatter BOUNDARY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter NAME_MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final DataSource dataSource;
    private final LogProperties properties;

    /** 最近一次维护结果，供 {@code GET /api/logs/partitions} 查看（进程内，重启归零） */
    private volatile MaintenanceReport lastReport = MaintenanceReport.never();

    public LogPartitionManager(DataSource dataSource, LogProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    /* ------------------------------------------------------------------ 触发 */

    /**
     * 每日维护。{@code @Scheduled} 在没有 {@code @EnableScheduling} 的环境里不会跑，
     * 所以启动时另有一次（见 {@link #maintainOnStartup()}）——两者都拿同一把锁，不会打架。
     */
    @Scheduled(cron = "${platform.log.partition.cron:0 10 3 * * ?}")
    public void scheduled() {
        maintain(true);
    }

    /**
     * 启动即建分区：新部署当天就该有当月分区，而不是等到凌晨 3:10。
     *
     * <p>放到 daemon 线程里做，不阻塞 {@code ApplicationReadyEvent}：DDL 再快也是数据库往返，
     * 不能让「服务是否就绪」取决于它。启动这次**只建分区、不清理**——清理可能删几十万行，
     * 那是凌晨的活。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void maintainOnStartup() {
        if (!cfg().isEnabled() || !cfg().isMaintainOnStartup()) {
            return;
        }
        Thread thread = new Thread(() -> maintain(false), "invoke-log-partition-init");
        thread.setDaemon(true);
        thread.start();
    }

    /* ------------------------------------------------------------------ 主体 */

    /**
     * 执行一次维护：建够未来的分区，可选地清理过期数据。
     *
     * @param withCleanup 是否执行保留清理；启动那次传 false
     */
    public MaintenanceReport maintain(boolean withCleanup) {
        if (!cfg().isEnabled()) {
            return finish(MaintenanceReport.skipped("partition-maintenance-disabled"));
        }
        try (Connection conn = dataSource.getConnection()) {
            if (!tryLock(conn)) {
                // 另一个实例正在跑：这是正常现象，不是错误
                return finish(MaintenanceReport.skipped("locked-by-another-instance"));
            }
            try {
                List<PartitionInfo> before = partitions(conn);
                if (before.isEmpty()) {
                    // 手工建过 invoke_log（没带分区子句）就走到这里：此时不能 REORGANIZE，
                    // 也不该偷偷 ALTER 成缺失的分区形态，交给运维按 script/sql/carbon.sql 处理
                    log.warn("{} 不是分区表，跳过分区维护（请按 script/sql/carbon.sql 重建或手工 ALTER）", TABLE);
                    return finish(MaintenanceReport.skipped("not-a-partitioned-table"));
                }
                List<String> created = ensureAhead(conn);
                List<String> dropped = new ArrayList<>();
                long deleted = 0;
                if (withCleanup) {
                    LocalDate cutoff = properties.cutoffDate();
                    if (cutoff != null) {
                        dropped.addAll(dropExpired(conn, cutoff));
                        deleted = deleteOldRows(conn, cutoff);
                    }
                }
                MaintenanceReport report = new MaintenanceReport(true, null, List.copyOf(created),
                        List.copyOf(dropped), deleted, partitions(conn));
                if (!created.isEmpty() || !dropped.isEmpty() || deleted > 0) {
                    log.info("invoke_log 分区维护完成：新建 {}，删除分区 {}，清理行 {}，当前分区 {}",
                            created, dropped, deleted, report.partitionNames());
                }
                return finish(report);
            } finally {
                unlock(conn);
            }
        } catch (SQLException e) {
            log.warn("invoke_log 分区维护失败：{}", e.toString());
            return finish(MaintenanceReport.failed(e.toString()));
        } catch (RuntimeException e) {
            log.warn("invoke_log 分区维护异常", e);
            return finish(MaintenanceReport.failed(e.toString()));
        }
    }

    /** 当前分区视图（只读，供接口展示） */
    public List<PartitionInfo> partitions() {
        try (Connection conn = dataSource.getConnection()) {
            return partitions(conn);
        } catch (SQLException e) {
            throw new IllegalStateException("读取 " + TABLE + " 分区失败：" + e.getMessage(), e);
        }
    }

    public MaintenanceReport lastReport() {
        return lastReport;
    }

    /* ------------------------------------------------------------------ 建分区 */

    /**
     * 保证当月 + 未来 {@code aheadMonths} 个月都有分区。
     *
     * <p>每次 {@code REORGANIZE PARTITION p_max INTO (<新月>, p_max)}：{@code p_max} 必须始终
     * 保留为最后一个 {@code MAXVALUE} 分区，否则超边界的写入会直接报
     * {@code Table has no partition for value}。
     */
    private List<String> ensureAhead(Connection conn) {
        List<String> created = new ArrayList<>();
        Map<String, PartitionInfo> existing = byName(partitions(conn));
        YearMonth start = YearMonth.from(LocalDate.now());
        for (int i = 0; i <= cfg().getAheadMonths(); i++) {
            YearMonth month = start.plusMonths(i);
            String name = partitionName(month);
            if (existing.containsKey(name)) {
                continue;
            }
            if (!existing.containsKey("p_max")) {
                log.warn("{} 缺少 p_max 兜底分区，停止建分区（需人工修复）", TABLE);
                break;
            }
            String upper = month.plusMonths(1).atDay(1).format(BOUNDARY);
            String ddl = "ALTER TABLE " + TABLE + " REORGANIZE PARTITION p_max INTO ("
                    + "PARTITION " + name + " VALUES LESS THAN ('" + upper + "'),"
                    + " PARTITION p_max VALUES LESS THAN (MAXVALUE))";
            if (execute(conn, ddl)) {
                created.add(name);
                existing.put(name, new PartitionInfo(name, upper));
            }
        }
        return created;
    }

    /* ------------------------------------------------------------------ 清理 */

    /**
     * 删掉整月都早于截止日的分区。
     *
     * <p>判据是「分区上界 ≤ 截止日」而不是「分区名早于截止月」：上界是权威的，名字只是标签。
     * 当前月永远不会被删——它的上界是下月初，必然大于截止日。
     */
    private List<String> dropExpired(Connection conn, LocalDate cutoff) {
        List<String> dropped = new ArrayList<>();
        for (PartitionInfo p : partitions(conn)) {
            if ("p_max".equals(p.name())) {
                continue;
            }
            LocalDate upper = p.upperBoundDate();
            if (upper == null || upper.isAfter(cutoff)) {
                continue;
            }
            String ddl = "ALTER TABLE " + TABLE + " DROP PARTITION " + p.name();
            if (execute(conn, ddl)) {
                dropped.add(p.name());
            }
        }
        return dropped;
    }

    /**
     * 边界月里的残余老行。分批删、限批数：首跑（历史数据全在 {@code p_max}）时也不至于
     * 一条 SQL 删掉上百万行、把 undo log 和主从延迟一起打爆。
     */
    private long deleteOldRows(Connection conn, LocalDate cutoff) {
        long total = 0;
        int batch = Math.max(1, cfg().getCleanupBatchSize());
        int maxBatches = Math.max(0, cfg().getCleanupMaxBatches());
        String sql = "DELETE FROM " + TABLE + " WHERE created_at < ? LIMIT " + batch;
        for (int i = 0; i < maxBatches; i++) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, cutoff.atStartOfDay());
                int n = ps.executeUpdate();
                total += n;
                if (n < batch) {
                    break;
                }
            } catch (SQLException e) {
                // 单批失败不重试：可能是锁等待超时，下一轮（次日）会接着删
                log.warn("清理过期调用日志失败（已删 {} 行）：{}", total, e.toString());
                break;
            }
        }
        return total;
    }

    /* ------------------------------------------------------------------ 元数据 */

    private List<PartitionInfo> partitions(Connection conn) {
        String sql = "SELECT PARTITION_NAME, PARTITION_DESCRIPTION FROM information_schema.PARTITIONS"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND PARTITION_NAME IS NOT NULL"
                + " ORDER BY PARTITION_ORDINAL_POSITION";
        List<PartitionInfo> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (name == null || !PARTITION_NAME.matcher(name).matches()) {
                        log.warn("跳过无法识别的分区名：{}", name);
                        continue;
                    }
                    list.add(new PartitionInfo(name, unquote(rs.getString(2))));
                }
            }
        } catch (SQLException e) {
            log.warn("读取分区元数据失败：{}", e.toString());
        }
        return list;
    }

    private static Map<String, PartitionInfo> byName(List<PartitionInfo> list) {
        Map<String, PartitionInfo> map = new LinkedHashMap<>();
        list.forEach(p -> map.put(p.name(), p));
        return map;
    }

    /** {@code RANGE COLUMNS} 的 description 形如 {@code '2026-11-01'}，去掉引号 */
    private static String unquote(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.length() >= 2 && s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String partitionName(YearMonth month) {
        return "p" + month.format(NAME_MONTH);
    }

    /* ------------------------------------------------------------------ 基础设施 */

    private LogProperties.Partition cfg() {
        return properties.getPartition();
    }

    private boolean tryLock(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            ps.setString(1, LOCK_NAME);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (SQLException e) {
            log.warn("获取分区维护锁失败：{}", e.toString());
            return false;
        }
    }

    private void unlock(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            ps.setString(1, LOCK_NAME);
            ps.executeQuery();
        } catch (SQLException e) {
            // 连接关闭时 MySQL 会自动释放，这里失败无需处理
            log.debug("释放分区维护锁失败：{}", e.toString());
        }
    }

    private boolean execute(Connection conn, String ddl) {
        try (Statement st = conn.createStatement()) {
            st.execute(ddl);
            log.debug("DDL 成功：{}", ddl);
            return true;
        } catch (SQLException e) {
            log.warn("DDL 失败：{} cause={}", ddl, e.toString());
            return false;
        }
    }

    private MaintenanceReport finish(MaintenanceReport report) {
        this.lastReport = report;
        return report;
    }

    /* ------------------------------------------------------------------ 结果模型 */

    /** 一个分区：名字 + 上界（{@code p_max} 的上界是 {@code null}） */
    public record PartitionInfo(String name, String upperBound) {

        LocalDate upperBoundDate() {
            try {
                return upperBound == null || "MAXVALUE".equalsIgnoreCase(upperBound)
                        ? null : LocalDate.parse(upperBound, BOUNDARY);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /**
     * 一次维护的结果。
     *
     * <p>{@code ran=false} 不是错误：可能是开关关着，也可能是别的实例正在跑——
     * 用 {@code skippedReason} 区分，别把正常等待当故障告警。
     */
    public record MaintenanceReport(boolean ran,
                                    String skippedReason,
                                    List<String> created,
                                    List<String> dropped,
                                    long deletedRows,
                                    List<PartitionInfo> partitions) {

        static MaintenanceReport never() {
            return new MaintenanceReport(false, "not-run-yet", List.of(), List.of(), 0, List.of());
        }

        static MaintenanceReport skipped(String reason) {
            return new MaintenanceReport(false, reason, List.of(), List.of(), 0, List.of());
        }

        static MaintenanceReport failed(String reason) {
            return new MaintenanceReport(false, "failed: " + reason, List.of(), List.of(), 0, List.of());
        }

        public List<String> partitionNames() {
            return partitions.stream().map(PartitionInfo::name).toList();
        }
    }
}
