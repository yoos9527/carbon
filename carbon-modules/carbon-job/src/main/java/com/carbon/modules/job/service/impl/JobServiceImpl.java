package com.carbon.modules.job.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.common.core.domain.TableDataInfo;
import com.carbon.common.json.JsonUtils;
import com.carbon.modules.job.domain.bo.JobBo;
import com.carbon.modules.job.domain.entity.Job;
import com.carbon.modules.job.domain.vo.JobVo;
import com.carbon.modules.job.mapper.JobMapper;
import com.carbon.modules.job.service.IJobService;
import com.carbon.modules.job.service.JobSyncService;
import com.carbon.modules.job.support.CronSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author carbon
 */
@Service
public class JobServiceImpl implements IJobService {

    private static final Logger log = LoggerFactory.getLogger(JobServiceImpl.class);

    private final JobMapper jobMapper;

    private final JobSyncService syncService;

    public JobServiceImpl(JobMapper jobMapper, JobSyncService syncService) {
        this.jobMapper = jobMapper;
        this.syncService = syncService;
    }

    @Override
    public TableDataInfo<JobVo> page(PageQuery query, Long flowId) {
        LambdaQueryWrapper<Job> wrapper = new LambdaQueryWrapper<Job>()
                .eq(flowId != null, Job::getFlowId, flowId)
                .orderByDesc(Job::getId);
        Page<Job> page = new Page<>(query.safePageNum(), query.safePageSize());
        String column = query.getOrderByColumn();
        if (column != null && !column.isBlank()) {
            // 排序列已在 Controller 过过白名单，这里只负责拼 SQL 片段
            page.addOrder(query.ascending()
                    ? com.baomidou.mybatisplus.core.metadata.OrderItem.asc(column)
                    : com.baomidou.mybatisplus.core.metadata.OrderItem.desc(column));
        }
        IPage<Job> result = jobMapper.selectPage(page, wrapper);
        List<JobVo> rows = new ArrayList<>(result.getRecords().size());
        for (Job job : result.getRecords()) {
            rows.add(toVo(job));
        }
        return TableDataInfo.of(result.getTotal(), rows);
    }

    @Override
    public JobVo byId(Long id) {
        return toVo(requireById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(JobBo bo) {
        CronSupport.validate(bo.getCron(), bo.getTimezone());
        Job job = new Job();
        job.setFlowId(bo.getFlowId());
        job.setName(bo.getName());
        job.setCron(bo.getCron());
        job.setTimezone(defaultIfBlank(bo.getTimezone(), "Asia/Shanghai"));
        job.setMisfirePolicy(defaultIfBlank(bo.getMisfirePolicy(), "DO_NOTHING"));
        job.setConcurrency(defaultIfBlank(bo.getConcurrency(), "DISALLOW"));
        job.setParams(toJson(bo.getParams()));
        // 新建默认停用：cron 写错在生产上是事故，让「启用」这一步显式发生
        job.setEnabled(bo.getEnabled() == null ? 0 : bo.getEnabled());
        jobMapper.insert(job);
        syncService.sync(job);
        return job.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, JobBo bo) {
        Job job = requireById(id);
        CronSupport.validate(bo.getCron(), bo.getTimezone());
        job.setFlowId(bo.getFlowId());
        job.setName(bo.getName());
        job.setCron(bo.getCron());
        job.setTimezone(defaultIfBlank(bo.getTimezone(), "Asia/Shanghai"));
        job.setMisfirePolicy(defaultIfBlank(bo.getMisfirePolicy(), "DO_NOTHING"));
        job.setConcurrency(defaultIfBlank(bo.getConcurrency(), "DISALLOW"));
        job.setParams(toJson(bo.getParams()));
        if (bo.getEnabled() != null) {
            job.setEnabled(bo.getEnabled());
        }
        jobMapper.updateById(job);
        syncService.sync(job);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireById(id);
        // 先摘 Quartz 再删库：反过来的话 Quartz 侧会留一个指向已删任务的触发器，
        // 每次触发都报「任务不存在」
        syncService.unsync(id);
        jobMapper.deleteById(id);
    }

    @Override
    public void enable(Long id) {
        Job job = requireById(id);
        CronSupport.validate(job.getCron(), job.getTimezone());
        job.setEnabled(1);
        jobMapper.updateById(job);
        syncService.sync(job);
    }

    @Override
    public void disable(Long id) {
        Job job = requireById(id);
        job.setEnabled(0);
        jobMapper.updateById(job);
        syncService.unsync(id);
    }

    @Override
    public void triggerNow(Long id) {
        syncService.triggerOnce(requireById(id));
    }

    @Override
    public Job requireById(Long id) {
        Job job = jobMapper.selectById(id);
        if (job == null) {
            throw ServiceException.param("任务不存在：" + id);
        }
        return job;
    }

    private JobVo toVo(Job job) {
        JobVo vo = new JobVo();
        vo.setId(job.getId());
        vo.setFlowId(job.getFlowId());
        vo.setName(job.getName());
        vo.setCron(job.getCron());
        vo.setTimezone(job.getTimezone());
        vo.setMisfirePolicy(job.getMisfirePolicy());
        vo.setConcurrency(job.getConcurrency());
        vo.setParams(parseJson(job.getParams()));
        vo.setEnabled(job.getEnabled());
        vo.setCreatedAt(job.getCreatedAt());
        vo.setUpdatedAt(job.getUpdatedAt());
        if (job.getEnabled() != null && job.getEnabled() == 1) {
            vo.setNextFireTimes(CronSupport.nextFireTimes(job.getCron(), job.getTimezone(), 3));
        } else {
            vo.setNextFireTimes(List.of());
        }
        return vo;
    }

    private String toJson(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        return JsonUtils.toJson(params);
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> parsed = JsonUtils.parseMap(json);
        return parsed == null ? Map.of() : parsed;
    }

    private String defaultIfBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
