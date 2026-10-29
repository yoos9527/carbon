package com.carbon.modules.invokelog.stats;

import java.util.List;

/**
 * 看板聚合结果（一次请求拿全，前端不再逐块拼 N 个接口）。
 *
 * <p>看板天然要跨连接器、编排、日志、调度四块数据，而 {@code carbon-invokelog} 是叶子模块
 * （不依赖 {@code carbon-connector}/{@code carbon-flow}，见设计文档 §9.2）。这里不靠模块依赖解决，
 * 而是把这些「计数/取名」直接写进 SQL：{@code SELECT COUNT(*) FROM endpoint} 只依赖数据库，
 * 不依赖 Java 类型，叶子约束与看板需求因此不冲突。
 *
 * @author carbon
 */
public record StatsOverview(
        Counts counts,
        WindowStats today,
        WindowStats window,
        List<TrendPoint> trend,
        List<SourceMix> sourceMix,
        List<TopSlow> topSlow,
        List<TopFailed> topFailed) {

    /** 上层实体数量（都是「当前还有效」的数量，已排除软删除） */
    public record Counts(
            long connectors,
            long operations,
            long flows,
            long apps,
            long jobs,
            long runningRuns) {
    }

    /**
     * 一段时间的调用质量。
     *
     * @param total       调用次数
     * @param failed      失败次数（errCode 非空即算失败：三方 4xx/5xx、超时、脚本报错都落在这里）
     * @param successRate 成功率，已按百分比四舍五入（97.5 表示 97.5%）
     * @param avgMs       平均耗时
     * @param p95Ms       95 分位耗时
     */
    public record WindowStats(
            long total,
            long failed,
            double successRate,
            long avgMs,
            long p95Ms) {
    }

    /** 每天一个点；没有调用的日期补 0，图表才能连成线 */
    public record TrendPoint(String date, long total, long failed, long avgMs) {
    }

    /** 调用来源占比：CONSOLE 试调 / GW 网关 / FLOW 编排 */
    public record SourceMix(String source, long total, long avgMs) {
    }

    /** 慢接口 TOP（按平均耗时降序）。名字直接 join 出来，省得前端再查一遍连接器 */
    public record TopSlow(
            Long connectorId,
            Long operationId,
            String connectorName,
            String operationName,
            long calls,
            long avgMs,
            long maxMs) {
    }

    /** 错误码 TOP，前端点了可以跳日志页按 errCode 过滤 */
    public record TopFailed(String errCode, long calls) {
    }
}
