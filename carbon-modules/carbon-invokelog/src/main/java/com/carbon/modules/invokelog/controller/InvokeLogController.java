package com.carbon.modules.invokelog.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.common.core.domain.R;
import com.carbon.common.core.domain.TableDataInfo;
import com.carbon.modules.invokelog.domain.entity.InvokeLog;
import com.carbon.modules.invokelog.writer.AsyncLogWriter;
import com.carbon.modules.invokelog.service.InvokeLogQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    public InvokeLogController(InvokeLogQueryService queryService, AsyncLogWriter logWriter) {
        this.queryService = queryService;
        this.logWriter = logWriter;
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
}
