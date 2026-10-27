package com.carbon.modules.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.modules.system.domain.vo.OperLogVo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 操作日志查询。
 *
 * <p>只有读：写入由 {@code carbon-common-log} 的切面负责，业务代码不该手工插日志
 * （手工插的那部分永远会漏，而且没人维护）。
 *
 * @author carbon
 */
public interface IOperLogService {

    /** 列表页排序白名单（驼峰属性名） */
    List<String> SORTABLE = List.of("createdAt", "durationMs", "title", "businessType");

    IPage<OperLogVo> page(PageQuery pageQuery, String title, String businessType, String keyword,
                          Boolean onlyFailed, LocalDateTime beginTime, LocalDateTime endTime);
}
