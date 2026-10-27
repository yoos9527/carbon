package com.carbon.modules.invokelog.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.common.core.domain.R;
import com.carbon.common.core.domain.TableDataInfo;
import com.carbon.modules.invokelog.domain.entity.InvokeLog;
import com.carbon.modules.invokelog.partition.LogPartitionManager;
import com.carbon.modules.invokelog.config.LogProperties;
import com.carbon.modules.invokelog.writer.AsyncLogWriter;
import com.carbon.modules.invokelog.service.InvokeLogQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用日志查询。
 *
 * @author carbon
 */
@RestController
@RequestMapping("/api/logs")
public class InvokeLogController {

    private final InvokeLogQueryService queryService;
    private final AsyncLogWriter logWriter;
    private final LogPartitionManager partitionManager;
    private final LogProperties logProperties;

    public InvokeLogController(InvokeLogQueryService queryService,
                               AsyncLogWriter logWriter,
                               LogPartitionManager partitionManager,
                               LogProperties logProperties) {
        this.queryService = queryService;
        this.logWriter = logWriter;
        this.partitionManager = partitionManager;
        this.logProperties = logProperties;
    }

    @GetMapping
    public R<TableDataInfo<InvokeLog>> page(PageQuery pageQuery,
                                            @RequestParam(required = false) Long connectorId,
                                            @RequestParam(required = false) Long operationId,
                                            @RequestParam(required = false) String source,
                                            @RequestParam(required = false) String traceId,
                                            @RequestParam(required = false) Boolean onlyFailed,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                            LocalDateTime beginTime,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                            LocalDateTime endTime) {
        IPage<InvokeLog> page = queryService.page(pageQuery, connectorId, operationId, source,
                traceId, onlyFailed, beginTime, endTime);
        return R.ok(TableDataInfo.of(page.getTotal(), page.getRecords()));
    }

    @GetMapping("/{traceId}")
    public R<List<InvokeLog>> byTraceId(@PathVariable String traceId) {
        return R.ok(queryService.byTraceId(traceId));
    }

    /**
     * 写入器状态。
     *
     * <p>暴露 {@code dropped}/{@code failed} 的理由：日志丢了必须**能被发现**。
     * 没有这两个计数，「为什么少了一条记录」这个问题永远查不清。
     */
    @GetMapping("/writer/status")
    public R<Map<String, Object>> writerStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("queueSize", logWriter.queueSize());
        status.put("written", logWriter.writtenCount());
        status.put("failed", logWriter.failedCount());
        status.put("dropped", logWriter.droppedCount());
        return R.ok(status);
    }

    /**
     * 分区现状与最近一次维护结果。
     *
     * <p>存在的理由：分区维护是个**静默任务**——“日志能不能按月滚滚删”这件事，
     * 靠看日志文件不可靠（它一年只在每月 1 号前后说一句话）。运维需要一个随时能查的口子：
     * {@code partitions} 里有没有下个月，{@code lastReport.ran} 是不是一直是 false。
     */
    @GetMapping("/partitions")
    public R<Map<String, Object>> partitions() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", logProperties.getPartition().isEnabled());
        data.put("aheadMonths", logProperties.getPartition().getAheadMonths());
        data.put("retentionDays", logProperties.getRetentionDays());
        data.put("partitions", partitionManager.partitions());
        data.put("lastReport", partitionManager.lastReport());
        return R.ok(data);
    }

    /**
     * 手动触发一次完整维护（建分区 + 清理）。
     *
     * <p>存在的理由：分区维护是每日任务，但**改完配置或修完历史数据后不该等到凌晨**才生效，
     * 也不该为了一次验证去登数据库跑 DDL。真正并发时由 MySQL 命名锁互斥，点两次也不会重入。
     */
    @PostMapping("/partitions/maintain")
    public R<LogPartitionManager.MaintenanceReport> maintainPartitions() {
        return R.ok(partitionManager.maintain(true));
    }
}
