package com.carbon.modules.job.handler;

import java.util.Map;

/**
 * 定时任务的执行体。
 *
 * <p>Quartz 侧只有一个 {@code FlowQuartzJob}，它按 {@code job.flow_id} 找到对应的
 * {@link JobHandler} 再执行——这样以后要加「定时调连接器」「定时清理日志」这类任务，
 * 只需新增一个 handler，不用改 Quartz 注册逻辑，也不用再建一套 JobStore。
 *
 * <p>实现类必须是无状态的 Spring Bean：Quartz 在集群里可能在任何节点、任何线程上触发它。
 *
 * @author carbon
 */
public interface JobHandler {

    /** 本 handler 支持的任务类型，与 {@code job} 表里的分类字段对应。 */
    String type();

    /**
     * 执行一次。
     *
     * @param jobId    任务 id（用于打日志、抢锁）
     * @param flowId   目标流程 id
     * @param params   任务参数，作为流程的 input
     * @param triggerBy 触发者标识（任务名），会写进 {@code flow_run.trigger_by}
     */
    void execute(Long jobId, Long flowId, Map<String, Object> params, String triggerBy);
}
