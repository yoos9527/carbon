package com.carbon.modules.flow.stream;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.modules.flow.domain.entity.FlowRun;
import com.carbon.modules.flow.domain.entity.FlowStepRun;
import com.carbon.modules.flow.mapper.FlowRunMapper;
import com.carbon.modules.flow.mapper.FlowStepRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 运行进度 SSE。
 *
 * <p>两条通路合起来才完整：
 * <ol>
 *   <li><b>实时</b>：{@link RunStreamRegistry} 的内存事件（本实例执行的流程才有）</li>
 *   <li><b>兜底</b>：定时查一次 {@code flow_run.status}。覆盖「流程在别的实例跑」「运行已结束」
 *       「finish 事件丢失」「进程重启后重连」四种情况——只慢一拍，不挂死</li>
 * </ol>
 * 客户端拿到 {@code done} 就断开，再回查 {@code GET /api/flow-runs/{id}} 拿权威明细。
 *
 * @author carbon
 */
@Service
public class RunStreamService {

    private static final Logger log = LoggerFactory.getLogger(RunStreamService.class);

    /** 单个连接最长存活时间：浏览器关了但 TCP 没断时，靠它回收线程。 */
    @Value("${carbon.flow.stream.timeout-ms:300000}")
    private long timeoutMs;

    /** 兜底轮询间隔。1s 对「看进度」够用，对库也只是主键查询。 */
    @Value("${carbon.flow.stream.poll-ms:1000}")
    private long pollMs;

    private final RunStreamRegistry registry;
    private final FlowRunMapper runMapper;
    private final FlowStepRunMapper stepMapper;

    private final ScheduledExecutorService poller = Executors.newScheduledThreadPool(2, r -> {
        Thread thread = new Thread(r, "flow-stream-poll");
        thread.setDaemon(true);
        return thread;
    });

    public RunStreamService(RunStreamRegistry registry, FlowRunMapper runMapper, FlowStepRunMapper stepMapper) {
        this.registry = registry;
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
    }

    public SseEmitter open(Long runId) {
        FlowRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new ServiceException("运行记录不存在：" + runId);
        }
        String key = String.valueOf(runId);
        SseEmitter emitter = new SseEmitter(timeoutMs);

        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicReference<ScheduledFuture<?>> taskRef = new AtomicReference<>();
        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                registry.unbind(key, emitter);
                ScheduledFuture<?> task = taskRef.get();
                if (task != null) {
                    task.cancel(false);
                }
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // 先挂通道再发快照：挂载会补发缓冲事件，快照只是页面头部的状态
        registry.bind(emitter, key);
        send(emitter, RunStreamRegistry.EVENT_SNAPSHOT, run);

        ScheduledFuture<?> task = poller.scheduleWithFixedDelay(
                () -> poll(runId, emitter, closed), pollMs, pollMs, TimeUnit.MILLISECONDS);
        taskRef.set(task);
        if (closed.get()) {
            // 极端情况：bind 补到 done 已经把连接关掉了，轮询任务白建，立刻撤掉
            task.cancel(false);
        }
        return emitter;
    }

    private void poll(Long runId, SseEmitter emitter, AtomicBoolean closed) {
        try {
            FlowRun run = runMapper.selectById(runId);
            if (run == null || !"RUNNING".equals(run.getStatus())) {
                if (closed.compareAndSet(false, true)) {
                    send(emitter, RunStreamRegistry.EVENT_DONE, donePayload(runId, run));
                    emitter.complete();
                }
                return;
            }
            // 运行中的心跳：既探活客户端，也防止中间代理掐掉空闲连接
            emitter.send(SseEmitter.event().comment("ping"));
        } catch (IOException | IllegalStateException e) {
            closed.set(true);
            emitter.completeWithError(e);
        } catch (RuntimeException e) {
            log.warn("[flow-stream] 轮询失败 run={}：{}", runId, e.getMessage());
        }
    }

    private Map<String, Object> donePayload(Long runId, FlowRun run) {
        List<FlowStepRun> steps = stepMapper.selectList(new QueryWrapper<FlowStepRun>()
                .eq("flow_run_id", runId)
                .orderByAsc("exec_index"));
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("runId", runId);
        payload.put("steps", steps);
        if (run != null) {
            payload.put("status", run.getStatus());
            payload.put("error", run.getErrorMsg());
            payload.put("startedAt", run.getStartedAt());
            payload.put("finishedAt", run.getFinishedAt());
            payload.put("durationMs", durationOf(run));
        }
        return payload;
    }

    private Long durationOf(FlowRun run) {
        if (run.getStartedAt() == null || run.getFinishedAt() == null) {
            return null;
        }
        return java.time.Duration.between(
                run.getStartedAt().atZone(ZoneId.systemDefault()),
                run.getFinishedAt().atZone(ZoneId.systemDefault())).toMillis();
    }

    private void send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("[flow-stream] 发送 {} 失败：{}", event, e.getMessage());
        }
    }
}
