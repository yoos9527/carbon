package com.carbon.modules.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.common.log.domain.SysOperLog;
import com.carbon.common.log.mapper.SysOperLogMapper;

import java.time.LocalDateTime;

/**
 * 操作日志查询。
 *
 * <p>只有「查」——写入由 {@code carbon-common-log} 的 {@code LogAspect} 负责，
 * 任何模块（包括本模块）都不该手写操作日志。
 *
 * <p>排序固定按 {@code created_at} 倒序，不接受前端传排序列：
 * 日志列表的唯一合理顺序是「最近的在上」，让人翻着看。
 *
 * @author carbon
 */
@org.springframework.stereotype.Service
public class OperLogQueryService {

    private final SysOperLogMapper mapper;

    public OperLogQueryService(SysOperLogMapper mapper) {
        this.mapper = mapper;
    }

    public IPage<SysOperLog> page(PageQuery pageQuery, String title, String businessType,
                                  Boolean onlyFailed, String keyword,
                                  LocalDateTime beginTime, LocalDateTime endTime) {
        return mapper.selectPage(new Page<>(pageQuery.safePageNum(), pageQuery.safePageSize()),
                Wrappers.<SysOperLog>lambdaQuery()
                        .eq(title != null && !title.isBlank(), SysOperLog::getTitle, title)
                        .eq(businessType != null && !businessType.isBlank(),
                                SysOperLog::getBusinessType, businessType)
                        .eq(Boolean.TRUE.equals(onlyFailed), SysOperLog::getStatus, 0)
                        // 关键字只搜「方法名 + 参数」，不搜 error_msg：错误信息里常有堆栈噪声
                        .and(keyword != null && !keyword.isBlank(), w -> w
                                .like(SysOperLog::getMethod, keyword)
                                .or().like(SysOperLog::getParams, keyword))
                        .ge(beginTime != null, SysOperLog::getCreatedAt, beginTime)
                        .le(endTime != null, SysOperLog::getCreatedAt, endTime)
                        .orderByDesc(SysOperLog::getCreatedAt));
    }
}
