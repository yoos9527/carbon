package com.carbon.modules.flow.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.common.core.domain.R;
import com.carbon.common.core.domain.TableDataInfo;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.common.web.domain.BaseController;
import com.carbon.modules.flow.domain.entity.FlowRun;
import com.carbon.modules.flow.domain.entity.FlowStepRun;
import com.carbon.modules.flow.mapper.FlowRunMapper;
import com.carbon.modules.flow.mapper.FlowStepRunMapper;
import com.carbon.modules.flow.stream.RunStreamService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 编排运行记录查询。
 *
 * <p>只读：运行记录是审计数据，不允许改也不允许删；保留策略交给分区 DROP。
 *
 * @author carbon
 */
@RestController
@RequestMapping("/api/flow-runs")
public class FlowRunController extends BaseController {

    private final FlowRunMapper runMapper;
    private final FlowStepRunMapper stepMapper;
    private final RunStreamService streamService;

    public FlowRunController(FlowRunMapper runMapper, FlowStepRunMapper stepMapper,
                             RunStreamService streamService) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.streamService = streamService;
    }

    @GetMapping
    public R<TableDataInfo<FlowRun>> page(PageQuery pageQuery,
                                          @RequestParam(required = false) Long flowId,
                                          @RequestParam(required = false) String status) {
        QueryWrapper<FlowRun> wrapper = new QueryWrapper<>();
        if (flowId != null) {
            wrapper.eq("flow_id", flowId);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("started_at");
        IPage<FlowRun> page = runMapper.selectPage(
                new Page<>(pageQuery.safePageNum(), pageQuery.safePageSize()), wrapper);
        return R.ok(toTable(page));
    }

    /**
     * 运行详情 + 节点明细。
     *
     * <p>节点明细按 {@code exec_index} 排序返回（不是按时间戳：同毫秒内 LOOP 展开的顺序只能靠它）。
     */
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        FlowRun run = runMapper.selectById(id);
        if (run == null) {
            throw new ServiceException("运行记录不存在：" + id);
        }
        List<FlowStepRun> steps = stepMapper.selectList(new QueryWrapper<FlowStepRun>()
                .eq("flow_run_id", id)
                .orderByAsc("exec_index"));
        return R.ok(Map.of("run", run, "steps", steps));
    }

    /**
     * 运行进度 SSE。
     *
     * <p>事件顺序：{@code snapshot}（运行记录当前状态）→ 若干 {@code step}（节点进/出）→ {@code done}。
     * 已结束的运行连上来会立刻收到补发的全部事件，然后连接被关掉，不会挂着。
     */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long id) {
        return streamService.open(id);
    }
}
