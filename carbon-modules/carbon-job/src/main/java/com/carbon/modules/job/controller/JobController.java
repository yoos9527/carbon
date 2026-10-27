package com.carbon.modules.job.controller;

import com.carbon.common.core.domain.PageQuery;
import com.carbon.common.core.domain.R;
import com.carbon.common.core.domain.TableDataInfo;
import com.carbon.common.web.domain.BaseController;
import com.carbon.modules.job.domain.bo.JobBo;
import com.carbon.modules.job.domain.vo.JobVo;
import com.carbon.modules.job.service.IJobService;
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
import com.carbon.common.log.BusinessType;
import com.carbon.common.log.Log;
import com.carbon.common.idempotent.RepeatSubmit;

/**
 * 定时任务管理。
 *
 * @author carbon
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController extends BaseController {

    private final IJobService jobService;

    public JobController(IJobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping
    public R<TableDataInfo<JobVo>> page(PageQuery pageQuery,
                                        @RequestParam(required = false) Long flowId) {
        // 先把排序列过一遍白名单（BaseController 会在非法列上直接抛 PARAM_INVALID）
        toPage(pageQuery, java.util.Set.of(IJobService.SORTABLE));
        return R.ok(jobService.page(pageQuery, flowId));
    }

    @GetMapping("/{id}")
    public R<JobVo> byId(@PathVariable Long id) {
        return R.ok(jobService.byId(id));
    }

    @RepeatSubmit
    @Log(title = "任务", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> create(@Valid @RequestBody JobBo bo) {
        return R.ok(jobService.create(bo));
    }

    @Log(title = "任务", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody JobBo bo) {
        jobService.update(id, bo);
        return R.ok();
    }

    @Log(title = "任务", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        jobService.delete(id);
        return R.ok();
    }

    @RepeatSubmit
    @Log(title = "任务", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/enable")
    public R<Void> enable(@PathVariable Long id) {
        jobService.enable(id);
        return R.ok();
    }

    @RepeatSubmit
    @Log(title = "任务", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/disable")
    public R<Void> disable(@PathVariable Long id) {
        jobService.disable(id);
        return R.ok();
    }

    /** 立即执行一次（不改 cron 的下次触发时间）。 */
    @RepeatSubmit
    @Log(title = "任务", businessType = BusinessType.EXECUTE)
    @PostMapping("/{id}/trigger")
    public R<Void> trigger(@PathVariable Long id) {
        jobService.triggerNow(id);
        return R.ok();
    }
}
