package com.carbon.modules.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.common.log.domain.SysOperLog;
import com.carbon.common.log.mapper.SysOperLogMapper;
import com.carbon.modules.system.domain.vo.OperLogVo;
import com.carbon.modules.system.service.IOperLogService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 操作日志查询实现。
 *
 * <p>{@code keyword} 同时匹配 {@code method} 与 {@code params}：排查时手里往往只有一个
 * 模糊印象（「改过 openapi 那个连接器」），能一次搜到比让人记住模块名现实。
 *
 * @author carbon
 */
@Service
public class OperLogServiceImpl implements IOperLogService {

    private static final Set<String> SORTABLE = Set.copyOf(IOperLogService.SORTABLE);

    private final SysOperLogMapper mapper;

    public OperLogServiceImpl(SysOperLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public IPage<OperLogVo> page(PageQuery pageQuery, String title, String businessType, String keyword,
                                 Boolean onlyFailed, LocalDateTime beginTime, LocalDateTime endTime) {
        LambdaQueryWrapper<SysOperLog> wrapper = new LambdaQueryWrapper<SysOperLog>()
                .like(hasText(title), SysOperLog::getTitle, title)
                .eq(hasText(businessType), SysOperLog::getBusinessType, businessType)
                .eq(Boolean.TRUE.equals(onlyFailed), SysOperLog::getStatus, 0)
                .ge(beginTime != null, SysOperLog::getCreatedAt, beginTime)
                .le(endTime != null, SysOperLog::getCreatedAt, endTime);
        if (hasText(keyword)) {
            wrapper.and(w -> w.like(SysOperLog::getMethod, keyword).or().like(SysOperLog::getParams, keyword));
        }
        String orderBy = pageQuery.getOrderByColumn();
        if (hasText(orderBy) && !SORTABLE.contains(orderBy)) {
            throw ServiceException.param("不支持的排序列：" + orderBy + "，可选：" + SORTABLE);
        }
        // 默认按时间倒序：看日志的诉求永远是「最近发生了什么」
        wrapper.orderByDesc(SysOperLog::getCreatedAt).orderByDesc(SysOperLog::getId);

        PageQuery query = pageQuery;
        IPage<SysOperLog> page = mapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                        query.safePageNum(), query.safePageSize()),
                wrapper);
        return page.convert(OperLogVo::of);
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
