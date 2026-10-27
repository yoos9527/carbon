package com.carbon.modules.invokelog.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.modules.invokelog.domain.entity.InvokeLog;
import com.carbon.modules.invokelog.mapper.InvokeLogMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 调用日志查询。
 *
 * <p>故意**不提供**「按 id 删除」和「编辑」：审计表的读接口可以多，写接口一个都不能有。
 * 保留策略靠分区 DROP，不靠 DELETE——{@code invoke_log} 一天可能上百万行，
 * 一条 {@code DELETE ... WHERE created_at < ?} 会让 InnoDB 长时间持有锁并撑爆 undo。
 *
 * @author carbon
 */
@Service
public class InvokeLogQueryService {

    private final InvokeLogMapper logMapper;

    public InvokeLogQueryService(InvokeLogMapper logMapper) {
        this.logMapper = logMapper;
    }

    public IPage<InvokeLog> page(PageQuery pageQuery, Long connectorId, Long operationId,
                                 String source, String traceId, Boolean onlyFailed,
                                 LocalDateTime beginTime, LocalDateTime endTime) {
        return logMapper.selectPage(new Page<>(pageQuery.safePageNum(), pageQuery.safePageSize()),
                Wrappers.<InvokeLog>lambdaQuery()
                        .eq(connectorId != null, InvokeLog::getConnectorId, connectorId)
                        .eq(operationId != null, InvokeLog::getOperationId, operationId)
                        .eq(source != null && !source.isBlank(), InvokeLog::getSource, source)
                        .eq(traceId != null && !traceId.isBlank(), InvokeLog::getTraceId, traceId)
                        // 只失败 = err_code 非空；成功日志不写 err_code（不存冗余的 success 列）
                        .isNotNull(Boolean.TRUE.equals(onlyFailed), InvokeLog::getErrCode)
                        .ge(beginTime != null, InvokeLog::getCreatedAt, beginTime)
                        .le(endTime != null, InvokeLog::getCreatedAt, endTime)
                        .orderByDesc(InvokeLog::getCreatedAt));
    }

    /**
     * 按 traceId 取整条链路（编排场景下会有多条：父流程 + 每个节点）。
     */
    public List<InvokeLog> byTraceId(String traceId) {
        return logMapper.selectList(Wrappers.<InvokeLog>lambdaQuery()
                .eq(InvokeLog::getTraceId, traceId)
                .orderByAsc(InvokeLog::getCreatedAt));
    }
}
