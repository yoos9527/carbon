package com.carbon.modules.flow.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.common.core.domain.R;
import com.carbon.common.core.domain.TableDataInfo;
import com.carbon.common.web.domain.BaseController;
import com.carbon.framework.dag.validator.ValidationError;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.modules.flow.config.FlowAsyncConfig;
import com.carbon.modules.flow.domain.bo.FlowBo;
import com.carbon.modules.flow.domain.bo.FlowRollbackBo;
import com.carbon.modules.flow.domain.entity.Flow;
import com.carbon.modules.flow.domain.entity.FlowRun;
import com.carbon.modules.flow.domain.entity.FlowVersion;
import com.carbon.modules.flow.executor.FlowRunResult;
import com.carbon.modules.flow.executor.FlowRunner;
import com.carbon.modules.flow.service.IFlowService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import com.carbon.common.log.BusinessType;
import com.carbon.common.log.Log;
import com.carbon.common.idempotent.RepeatSubmit;

/**
 * 流程定义接口。
 *
 * <p>发布与运行是两个动作，刻意分开：{@code POST /publish} 生成不可变版本快照，
 * {@code POST /{id}/run} 跑的是**当前已发布版本**而不是草稿。
 *
 * @author carbon
 */
@RestController
@RequestMapping("/api/flows")
public class FlowController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(FlowController.class);

    private final IFlowService flowService;
    private final FlowRunner flowRunner;
    private final ThreadPoolTaskExecutor flowRunExecutor;

    public FlowController(IFlowService flowService, FlowRunner flowRunner,
                          @Qualifier(FlowAsyncConfig.EXECUTOR) ThreadPoolTaskExecutor flowRunExecutor) {
        this.flowService = flowService;
        this.flowRunner = flowRunner;
        this.flowRunExecutor = flowRunExecutor;
    }

    @GetMapping
    public R<TableDataInfo<Flow>> page(PageQuery pageQuery,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) Integer status) {
        toPage(pageQuery, java.util.Set.of(IFlowService.SORTABLE));
        IPage<Flow> page = flowService.page(pageQuery, keyword, status);
        return R.ok(toTable(page));
    }

    @GetMapping("/{id}")
    public R<Flow> detail(@PathVariable Long id) {
        return R.ok(flowService.detail(id));
    }

    @RepeatSubmit
    @Log(title = "流程", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Flow> create(@Valid @RequestBody FlowBo bo) {
        return R.ok(flowService.create(bo));
    }

    @Log(title = "流程", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}")
    public R<Flow> update(@PathVariable Long id, @Valid @RequestBody FlowBo bo) {
        return R.ok(flowService.update(id, bo));
    }

    @Log(title = "流程", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        flowService.delete(id);
        return R.ok();
    }

    /**
     * 校验草稿。返回全部错误——画布上用户一次改多处，只回一条要来回五次。
     */
    @PostMapping("/validate")
    public R<List<ValidationError>> validate(@RequestBody Map<String, String> body) {
        return R.ok(flowService.validate(body.get("dsl")));
    }

    /**
     * 发布当前草稿。
     */
    @RepeatSubmit
    @Log(title = "流程", businessType = BusinessType.OTHER)
    @PostMapping("/{id}/publish")
    public R<FlowVersion> publish(@PathVariable Long id,
                                  @RequestParam(required = false, defaultValue = "anonymous") String operator) {
        return R.ok(flowService.publish(id, operator));
    }

    @GetMapping("/{id}/versions")
    public R<List<FlowVersion>> versions(@PathVariable Long id) {
        return R.ok(flowService.versions(id));
    }

    /**
     * 回滚到历史版本（指针回退 + 草稿同步）。
     */
    @RepeatSubmit
    @Log(title = "流程", businessType = BusinessType.OTHER)
    @PostMapping("/{id}/rollback")
    public R<FlowVersion> rollback(@PathVariable Long id,
                                   @Valid @RequestBody FlowRollbackBo bo,
                                   @RequestParam(required = false, defaultValue = "anonymous") String operator) {
        return R.ok(flowService.rollback(id, bo.getVersion(), operator));
    }

    /**
     * 手工试跑当前已发布版本（同步）：跑完才返回，响应里带结果摘要。
     *
     * <p>同步适合「一两秒能跑完、结果直接看」的流程；慢流程用 {@code /run-async}，
     * 否则一个 30 秒的流程会把浏览器连接和 Tomcat 线程一起占住。
     */
    @Log(title = "流程", businessType = BusinessType.EXECUTE)
    @PostMapping("/{id}/run")
    public R<FlowRunResult> run(@PathVariable Long id,
                                @RequestBody(required = false) Map<String, Object> input) {
        return R.ok(flowRunner.run(id, input == null ? Map.of() : input, "manual", "console"));
    }

    /**
     * 手工试跑（异步）：立刻返回 RUNNING 的运行记录，前端拿 {@code run.id} 去连 SSE 看进度。
     *
     * <p>队列满时先把那条记录收成 FAILED 再报错——不能留下一条永远 RUNNING 的幽灵。
     */
    @RepeatSubmit
    @Log(title = "流程", businessType = BusinessType.EXECUTE)
    @PostMapping("/{id}/run-async")
    public R<FlowRun> runAsync(@PathVariable Long id,
                               @RequestBody(required = false) Map<String, Object> input) {
        FlowRunner.Prepared prepared = flowRunner.prepare(
                id, input == null ? Map.of() : input, "manual", "console");
        try {
            flowRunExecutor.execute(() -> {
                try {
                    flowRunner.execute(prepared);
                } catch (RuntimeException e) {
                    // execute 内部已把运行记录收成 FAILED，这里只留日志
                    log.warn("[flow] 异步执行失败 run={}：{}", prepared.run().getId(), e.getMessage());
                }
            });
        } catch (TaskRejectedException e) {
            flowRunner.abort(prepared, "执行队列已满，请稍后重试");
            throw new ServiceException("执行队列已满，请稍后重试");
        }
        return R.ok(prepared.run());
    }
}
