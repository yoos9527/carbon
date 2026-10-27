package com.carbon.modules.job.service;

import com.carbon.common.core.domain.TableDataInfo;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.modules.job.domain.bo.JobBo;
import com.carbon.modules.job.domain.vo.JobVo;
import com.carbon.modules.job.domain.entity.Job;

/**
 * 任务管理。所有写操作都会顺带同步 Quartz（见 {@link JobSyncService}）。
 *
 * @author carbon
 */
public interface IJobService {

    String[] SORTABLE = {"id", "name", "flow_id", "cron", "enabled", "created_at", "updated_at"};

    TableDataInfo<JobVo> page(PageQuery query, Long flowId);

    JobVo byId(Long id);

    Long create(JobBo bo);

    void update(Long id, JobBo bo);

    void delete(Long id);

    /** 启用并把定义推进 Quartz。 */
    void enable(Long id);

    /** 停用并从 Quartz 摘掉。 */
    void disable(Long id);

    /** 立即执行一次，不改动 cron 的下次触发时间。 */
    void triggerNow(Long id);

    Job requireById(Long id);
}
