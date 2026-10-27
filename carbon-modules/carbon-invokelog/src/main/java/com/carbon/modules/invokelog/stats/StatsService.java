package com.carbon.modules.invokelog.stats;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 看板取数（只读）。
 *
 * <p>为什么直接用 {@link JdbcTemplate} 而不是 MyBatis-Plus：这里全是聚合查询，没有实体、
 * 没有条件构造器可复用，走 Wrapper 反而是负担；而跨表计数（connector/flow/app/job）走原生 SQL
 * 就不必为了几个 COUNT 去依赖别的业务模块。SQL 里手写 {@code deleted = 0}，
 * 就不会被逻辑删除的自动拼接规则影响。
 *
 * <p>性能：{@code invoke_log} 按月分区，所有查询都带 {@code created_at >= ?}，
 * 分区裁剪后只扫当月分区；即使没有 {@code created_at} 单列索引也不会全表扫。
 *
 * @author carbon
 */
@Service
public class StatsService {

    /** 趋势窗口上限：再长就该去 BI 而不是看板，也避免一次拉几十万行聚合 */
    private static final int MAX_DAYS = 30;
    private static final int TOP_LIMIT = 5;

    private final JdbcTemplate jdbc;

    public StatsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public StatsOverview overview(int days) {
        int span = Math.min(Math.max(days, 1), MAX_DAYS);
        LocalDateTime windowStart = LocalDate.now().minusDays(span - 1L).atStartOfDay();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        return new StatsOverview(
                counts(),
                window(todayStart),
                window(windowStart),
                trend(windowStart),
                sourceMix(windowStart),
                topSlow(windowStart),
                topFailed(windowStart));
    }

    private StatsOverview.Counts counts() {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT (SELECT COUNT(*) FROM connector           WHERE deleted = 0) AS connectors,
                       (SELECT COUNT(*) FROM connector_operation WHERE deleted = 0) AS operations,
                       (SELECT COUNT(*) FROM flow                WHERE deleted = 0) AS flows,
                       /* app 表刻意没有 deleted：应用是"硬删"，迁移时由 DBA 直接摘行 */
                       (SELECT COUNT(*) FROM app)                              AS apps,
                       (SELECT COUNT(*) FROM job)                              AS jobs,
                       (SELECT COUNT(*) FROM flow_run WHERE status = 'RUNNING') AS running_runs
                """);
        return new StatsOverview.Counts(
                longOf(row.get("connectors")),
                longOf(row.get("operations")),
                longOf(row.get("flows")),
                longOf(row.get("apps")),
                longOf(row.get("jobs")),
                longOf(row.get("running_runs")));
    }

    private StatsOverview.WindowStats window(LocalDateTime from) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT COUNT(*)                                                AS total,
                       COALESCE(SUM(CASE WHEN err_code IS NULL THEN 0 ELSE 1 END), 0) AS failed,
                       COALESCE(AVG(duration_ms), 0)                           AS avg_ms
                FROM invoke_log
                WHERE created_at >= ?
                """,
                Timestamp.valueOf(from));
        long total = longOf(row.get("total"));
        long failed = longOf(row.get("failed"));
        return new StatsOverview.WindowStats(total, failed, successRate(total, failed),
                longOf(row.get("avg_ms")), p95(from));
    }

    /**
     * 95 分位耗时。
     *
     * <p>MySQL 8 有窗口函数，不必把 duration 全捞回内存排序——分区裁剪后子查询只扫一个分区。
     * 一条都没有时返回 0（窗口函数查不到行）。
     */
    private long p95(LocalDateTime from) {
        List<Long> hit = jdbc.queryForList("""
                SELECT duration_ms FROM (
                    SELECT duration_ms,
                           ROW_NUMBER() OVER (ORDER BY duration_ms) AS rn,
                           COUNT(*) OVER ()                          AS cnt
                    FROM invoke_log
                    WHERE created_at >= ? AND duration_ms IS NOT NULL
                ) t
                WHERE rn = GREATEST(CEIL(cnt * 0.95), 1)
                LIMIT 1
                """, Long.class, Timestamp.valueOf(from));
        return hit.isEmpty() ? 0L : hit.get(0);
    }

    /** 按天聚合；库里没有的那天补 0，前端画图不需要自己填洞 */
    private List<StatsOverview.TrendPoint> trend(LocalDateTime from) {
        Map<String, StatsOverview.TrendPoint> byDate = new LinkedHashMap<>();
        jdbc.query("""
                SELECT DATE_FORMAT(created_at, '%Y-%m-%d')                    AS d,
                       COUNT(*)                                               AS total,
                       COALESCE(SUM(CASE WHEN err_code IS NULL THEN 0 ELSE 1 END), 0) AS failed,
                       COALESCE(AVG(duration_ms), 0)                          AS avg_ms
                FROM invoke_log
                WHERE created_at >= ?
                GROUP BY d
                ORDER BY d
                """,
                rs -> {
                    byDate.put(rs.getString("d"), new StatsOverview.TrendPoint(
                            rs.getString("d"),
                            rs.getLong("total"),
                            rs.getLong("failed"),
                            rs.getLong("avg_ms")));
                },
                Timestamp.valueOf(from));

        List<StatsOverview.TrendPoint> points = new ArrayList<>();
        LocalDate cursor = from.toLocalDate();
        LocalDate end = LocalDate.now();
        while (!cursor.isAfter(end)) {
            String key = cursor.toString();
            points.add(byDate.getOrDefault(key, new StatsOverview.TrendPoint(key, 0, 0, 0)));
            cursor = cursor.plusDays(1);
        }
        return points;
    }

    private List<StatsOverview.SourceMix> sourceMix(LocalDateTime from) {
        return jdbc.query("""
                SELECT source                                    AS source,
                       COUNT(*)                                  AS total,
                       COALESCE(AVG(duration_ms), 0)             AS avg_ms
                FROM invoke_log
                WHERE created_at >= ?
                GROUP BY source
                ORDER BY total DESC
                """,
                (rs, i) -> new StatsOverview.SourceMix(
                        rs.getString("source"), rs.getLong("total"), rs.getLong("avg_ms")),
                Timestamp.valueOf(from));
    }

    private List<StatsOverview.TopSlow> topSlow(LocalDateTime from) {
        return jdbc.query("""
                SELECT l.connector_id                  AS connector_id,
                       l.operation_id                  AS operation_id,
                       c.name                          AS connector_name,
                       o.name                          AS operation_name,
                       COUNT(*)                        AS calls,
                       COALESCE(AVG(l.duration_ms), 0) AS avg_ms,
                       COALESCE(MAX(l.duration_ms), 0) AS max_ms
                FROM invoke_log l
                LEFT JOIN connector c           ON c.id = l.connector_id
                LEFT JOIN connector_operation o ON o.id = l.operation_id
                WHERE l.created_at >= ? AND l.duration_ms IS NOT NULL AND l.connector_id IS NOT NULL
                GROUP BY l.connector_id, l.operation_id, c.name, o.name
                ORDER BY avg_ms DESC, calls DESC
                LIMIT ?
                """,
                (rs, i) -> new StatsOverview.TopSlow(
                        nullableLong(rs.getObject("connector_id")),
                        nullableLong(rs.getObject("operation_id")),
                        rs.getString("connector_name"),
                        rs.getString("operation_name"),
                        rs.getLong("calls"),
                        rs.getLong("avg_ms"),
                        rs.getLong("max_ms")),
                Timestamp.valueOf(from), TOP_LIMIT);
    }

    private List<StatsOverview.TopFailed> topFailed(LocalDateTime from) {
        return jdbc.query("""
                SELECT err_code AS err_code, COUNT(*) AS calls
                FROM invoke_log
                WHERE created_at >= ? AND err_code IS NOT NULL
                GROUP BY err_code
                ORDER BY calls DESC
                LIMIT ?
                """,
                (rs, i) -> new StatsOverview.TopFailed(rs.getString("err_code"), rs.getLong("calls")),
                Timestamp.valueOf(from), TOP_LIMIT);
    }

    private static double successRate(long total, long failed) {
        if (total <= 0) {
            return 100.0d;
        }
        return BigDecimal.valueOf((total - failed) * 100.0d / total)
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private static Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
