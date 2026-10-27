package com.carbon.modules.flow.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.common.core.domain.R;
import com.carbon.common.core.domain.TableDataInfo;
import com.carbon.common.web.domain.BaseController;
import com.carbon.framework.dag.validator.ValidationError;
import com.carbon.modules.flow.domain.bo.FlowBo;
import com.carbon.modules.flow.domain.entity.Flow;
import com.carbon.modules.flow.domain.entity.FlowVersion;
import com.carbon.modules.flow.executor.FlowRunResult;
import com.carbon.modules.flow.executor.FlowRunner;
import com.carbon.modules.flow.service.IFlowService;
import jakarta.validation.Valid;
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

    private final IFlowService flowService;
    private final FlowRunner flowRunner;

    public FlowController(IFlowService flowService, FlowRunner flowRunner) {
        this.flowService = flowService;
        this.flowRunner = flowRunner;
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

    @PostMapping
    public R<Flow> create(@Valid @RequestBody FlowBo bo) {
        return R.ok(flowService.create(bo));
    }

    @PutMapping("/{id}")
    public R<Flow> update(@PathVariable Long id, @Valid @RequestBody FlowBo bo) {
        return R.ok(flowService.update(id, bo));
    }

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
     * 手工试跑当前已发布版本。
     */
    @PostMapping("/{id}/run")
    public R<FlowRunResult> run(@PathVariable Long id,
                                @RequestBody(required = false) Map<String, Object> input) {
        return R.ok(flowRunner.run(id, input == null ? Map.of() : input, "manual", "console"));
    }
}
