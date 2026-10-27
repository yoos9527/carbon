package com.carbon.modules.job.service;

import com.carbon.common.core.exception.ServiceException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.modules.job.domain.entity.Job;
import com.carbon.modules.job.mapper.JobMapper;
import com.carbon.modules.job.quartz.FlowQuartzJob;
import com.carbon.modules.job.support.CronSupport;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * {@code job} 表 → Quartz 的单向同步。
 *
 * <p>方向刻意只有这一个：表是真身，Quartz 是投影。反过来的话，改 cron 就得去操作 QRTZ_* 那些
 * 带 BLOB 的表，既不好查也不好改。
 *
 * <p>只依赖 {@code JobMapper} 而不依赖 {@code IJobService}：{@code JobServiceImpl} 每次写库都要
 * 反过来调本类做同步，若本类再注入它，构造器就成环（Boot 3 默认直接拒启）。启动装载也就
 * 顺势留在本类里，不要去绕 {@code IJobService}。
 *
 * <p>启动时机选 {@code ApplicationReadyEvent} 而不是 {@code @PostConstruct}：
 * Quartz 的 Scheduler 在 {@code ContextRefreshedEvent} 之前就起来了，但 Flyway 的迁移
 * （V4 建 QRTZ_* 表）必须先跑完；等到 Ready 事件时，数据库、调度器、Flyway 三件事都齐了。
 *
 * @author carbon
 */
@Component
public class JobSyncService {

    private static final Logger log = LoggerFactory.getLogger(JobSyncService.class);

    private static final String GROUP = "carbon";

    private final Scheduler scheduler;

    private final JobMapper jobMapper;

    public JobSyncService(Scheduler scheduler, JobMapper jobMapper) {
        this.scheduler = scheduler;
        this.jobMapper = jobMapper;
    }

    public static JobKey jobKey(Long jobId) {
        return JobKey.jobKey(String.valueOf(jobId), GROUP);
    }

    public static TriggerKey triggerKey(Long jobId) {
        return TriggerKey.triggerKey("trigger-" + jobId, GROUP);
    }

    /** 启动后把所有已启用的任务装载进 Quartz；表里有、Quartz 里没有的会被补上。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        List<Job> jobs = jobMapper.selectList(new LambdaQueryWrapper<Job>().eq(Job::getEnabled, 1));
        int ok = 0;
        for (Job job : jobs) {
            try {
                sync(job);
                ok++;
            } catch (RuntimeException e) {
                // 单个任务装不上不能拖垮整个启动：cron 是用户填的，脏数据天然会有。
                // 记错误日志，等用户改成正确值后下一次 update/enable 会自动补上。
                log.error("[job] 启动装载失败 id={} name={}：{}", job.getId(), job.getName(), e.getMessage());
            }
        }
        log.info("[job] 启动装载完成：{}/{} 个已启用任务进入 Quartz", ok, jobs.size());
    }

    /** 新增或修改后调用：启用则（重新）注册，停用则摘除。 */
    public void sync(Job job) {
        Long jobId = job.getId();
        try {
            if (job.getEnabled() == null || job.getEnabled() != 1) {
                unsync(jobId);
                return;
            }
            CronSupport.validate(job.getCron(), job.getTimezone());

            JobDetail detail = JobBuilder.newJob(FlowQuartzJob.class)
                    .withIdentity(jobKey(jobId))
                    .usingJobData("jobId", jobId)
                    // 不 durable：任务停用时要把 JobDetail 一起删掉，
                    // 留着会变成「没有触发器的孤儿 JobDetail」，越积越多还删不干净
                    .storeDurably(false)
                    .build();

            CronTrigger trigger = org.quartz.TriggerBuilder.newTrigger()
                    .withIdentity(triggerKey(jobId))
                    .forJob(jobKey(jobId))
                    .withSchedule(CronSupport.scheduleBuilder(job.getCron(), job.getTimezone(), job.getMisfirePolicy()))
                    .build();

            // replace=true：cron 改了就覆盖旧的触发器，不用先删后加（避免中间态丢触发）
            Set<org.quartz.Trigger> triggers = new HashSet<>();
            triggers.add(trigger);
            scheduler.scheduleJob(detail, triggers, true);
            log.info("[job] 已装载 id={} name={} cron={} tz={} 下次触发={}",
                    jobId, job.getName(), job.getCron(), job.getTimezone(),
                    CronSupport.nextFireTimes(job.getCron(), job.getTimezone(), 1));
        } catch (SchedulerException e) {
            throw new ServiceException("任务注册到 Quartz 失败：" + e.getMessage());
        }
    }

    public void unsync(Long jobId) {
        try {
            boolean removed = scheduler.deleteJob(jobKey(jobId));
            if (removed) {
                log.info("[job] 已从 Quartz 摘除 id={}", jobId);
            }
        } catch (SchedulerException e) {
            throw new ServiceException("任务从 Quartz 摘除失败：" + e.getMessage());
        }
    }

    /** 立即执行一次；不改变 cron 计算出的下次触发时间。 */
    public void triggerOnce(Job job) {
        try {
            if (!scheduler.checkExists(jobKey(job.getId()))) {
                throw ServiceException.param("任务未启用，先启用再立即执行");
            }
            scheduler.triggerJob(jobKey(job.getId()));
        } catch (SchedulerException e) {
            throw new ServiceException("触发任务失败：" + e.getMessage());
        }
    }
}
