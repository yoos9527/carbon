package com.carbon.modules.job.quartz;

import com.carbon.common.core.exception.ServiceException;
import com.carbon.common.json.JsonUtils;
import com.carbon.modules.job.domain.entity.Job;
import com.carbon.modules.job.handler.JobHandler;
import com.carbon.modules.job.mapper.JobMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Quartz 侧唯一注册的执行类：按 {@code jobId} 回表读出任务定义，再交给 {@link JobHandler}。
 *
 * <p>为什么任务定义不塞进 JobDataMap：cron/参数随时会改，JobDataMap 里存的是**调度时刻的快照**，
 * 改完还得重新注册；回表读永远是最新的一份。JobDataMap 只放 jobId 这种不会变的小东西。
 *
 * <p><b>并发策略</b>：{@code @DisallowConcurrentExecution} 是编译期注解，无法按任务动态开关，
 * 所以在执行体内用 Redis 锁实现 {@code DISALLOW}——顺便解决了多节点部署时
 * 「同一任务在 A、B 两个实例上同时开跑」的问题（Quartz 集群只保证同一时刻一个节点触发，
 * 但触发了之后 A 节点慢、B 节点快的情况照样会重叠）。
 *
 * @author carbon
 */
@DisallowConcurrentExecution
public class FlowQuartzJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(FlowQuartzJob.class);

    /** 锁租期：比单次流程超时（默认 60s）留足余量，进程被 kill 时不至于永久锁死。 */
    private static final long LOCK_LEASE_SECONDS = 300L;

    private final JobMapper jobMapper;

    private final List<JobHandler> handlers;

    private final RedissonClient redisson;

    public FlowQuartzJob(JobMapper jobMapper, List<JobHandler> handlers, RedissonClient redisson) {
        this.jobMapper = jobMapper;
        this.handlers = handlers;
        this.redisson = redisson;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        long jobId = context.getMergedJobDataMap().getLongValue("jobId");
        Job job = jobMapper.selectById(jobId);
        if (job == null) {
            // 任务被删但触发已进队列：静默跳过，别把调度日志刷满
            log.info("[job] id={} 已不存在，跳过本次触发", jobId);
            return;
        }
        if (job.getEnabled() == null || job.getEnabled() != 1) {
            // 停用到删除之间有窗口期，同样按「跳过」处理
            log.info("[job] id={} 已停用，跳过本次触发", jobId);
            return;
        }

        RLock lock = null;
        boolean locked = true;
        if ("DISALLOW".equalsIgnoreCase(job.getConcurrency())) {
            lock = redisson.getLock("carbon:job:lock:" + jobId);
            try {
                locked = lock.tryLock(0, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!locked) {
                log.info("[job] id={} name={} 上一轮还没跑完，本次跳过（DISALLOW）", jobId, job.getName());
                return;
            }
        }

        try {
            Map<String, Object> params = parseParams(job.getParams());
            JobHandler handler = handlers.stream()
                    .filter(h -> "FLOW".equals(h.type()))
                    .findFirst()
                    .orElseThrow(() -> new ServiceException("没有可用的 JobHandler，请检查 carbon-job 装配"));
            handler.execute(jobId, job.getFlowId(), params, job.getName());
        } catch (RuntimeException e) {
            // 一定要吞掉：抛出去 Quartz 会按自己的规则重试整个 JobDetail，
            // 而「流程执行失败」已经写进 flow_run 了，再重试就是重复执行
            log.error("[job] id={} name={} 执行失败：{}", jobId, job.getName(), e.getMessage(), e);
        } finally {
            if (lock != null && locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private Map<String, Object> parseParams(String params) {
        if (params == null || params.isBlank()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> parsed = JsonUtils.parseMap(params);
        return parsed == null ? new LinkedHashMap<>() : parsed;
    }
}
