package com.carbon.modules.system.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.common.core.domain.R;
import com.carbon.common.core.domain.TableDataInfo;
import com.carbon.common.log.domain.SysOperLog;
import com.carbon.common.web.domain.BaseController;
import com.carbon.modules.system.service.OperLogQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 操作日志查询接口。
 *
 * <p>为什么在 {@code carbon-system} 而不是 {@code carbon-common-log}：
 * common 模块只提供「能力」（注解 + 切面 + 实体），对外接口属于业务域。
 * 这条边界以前没守住——{@code carbon-common-*} 里出现 {@code @RestController}
 * 会让「common 可以随便升级」的假设失效。
 *
 * @author carbon
 */
@RestController
@RequestMapping("/api/oper-logs")
public class OperLogController extends BaseController {

    private final OperLogQueryService queryService;

    public OperLogController(OperLogQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public R<TableDataInfo<SysOperLog>> page(
            PageQuery pageQuery,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String businessType,
            @RequestParam(required = false) Boolean onlyFailed,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime beginTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime endTime) {
        IPage<SysOperLog> page = queryService.page(pageQuery, title, businessType, onlyFailed,
                keyword, beginTime, endTime);
        return R.ok(toTable(page));
    }
}
