package com.carbon.modules.flow.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 运行态实时播报的进程内总线：{@code runId → 事件缓冲 + 订阅者}。
 *
 * <p>为什么要缓冲：异步跑的流程可能比「HTTP 返回 runId → 浏览器连 SSE」还快。
 * 只推给当下在线的订阅者，客户端就会漏掉开头几步。所以事件先进环形缓冲区，
 * 新订阅者先补发历史、再挂到「现在」上。
 *
 * <p><b>已知边界</b>：这是单进程内存结构。多实例部署时，SSE 连到 A 实例而流程在 B 实例执行，
 * 实时事件就看不到——此时靠 {@code RunStreamService} 的数据库轮询兜底（只慢一拍，不丢结果）。
 * 真要多实例实时推送，得换成 Redis Pub/Sub，那是另一件事。
 *
 * @author carbon
 */
@Component
public class RunStreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(RunStreamRegistry.class);

    public static final String EVENT_SNAPSHOT = "snapshot";
    public static final String EVENT_STEP = "step";
    public static final String EVENT_DONE = "done";

    /** 运行结束后再保留两分钟：页面刷新、断线重连都能补到完整事件。 */
    private static final Duration RETAIN_AFTER_FINISH = Duration.ofMinutes(2);
    /** 单次运行最多缓冲多少事件：LOOP 跑 10 万次也不该把堆吃满。 */
    private static final int MAX_BUFFERED_STEPS = 2000;

    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "flow-stream-janitor");
        thread.setDaemon(true);
        return thread;
    });

    public void step(StepEvent event) {
        Channel channel = channels.computeIfAbsent(event.runId(), k -> new Channel());
        synchronized (channel) {
            if (channel.done) {
                return;
            }
            if (channel.steps.size() < MAX_BUFFERED_STEPS) {
                channel.steps.add(event);
            }
            for (SseEmitter emitter : channel.subscribers) {
                trySend(emitter, EVENT_STEP, event);
            }
        }
    }

    /**
     * 运行结束。{@code payload} 建议是带明细的完整结果——订阅者收到就不必再回查一次。
     */
    public void finish(String runId, Object payload) {
        Channel channel = channels.computeIfAbsent(runId, k -> new Channel());
        synchronized (channel) {
            if (channel.done) {
                return;
            }
            channel.done = true;
            channel.payload = payload;
            for (SseEmitter emitter : channel.subscribers) {
                trySend(emitter, EVENT_DONE, payload);
                emitter.complete();
            }
            channel.subscribers.clear();
        }
        janitor.schedule(() -> channels.remove(runId),
                RETAIN_AFTER_FINISH.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 挂载一个订阅者：先把缓冲里的事件补发一遍（含「已经结束」），再决定是否挂在实时通道上。
     *
     * <p>补发与挂载在同一把锁里，中间不会漏事件也不会重复。
     */
    public void bind(SseEmitter emitter, String runId) {
        Channel channel = channels.computeIfAbsent(runId, k -> new Channel());
        synchronized (channel) {
            List<StepEvent> pending = new ArrayList<>(channel.steps);
            for (StepEvent event : pending) {
                trySend(emitter, EVENT_STEP, event);
            }
            if (channel.done) {
                trySend(emitter, EVENT_DONE, channel.payload);
                emitter.complete();
                return;
            }
            channel.subscribers.add(emitter);
        }
    }

    public void unbind(String runId, SseEmitter emitter) {
        Channel channel = channels.get(runId);
        if (channel == null) {
            return;
        }
        synchronized (channel) {
            channel.subscribers.remove(emitter);
        }
    }

    private void trySend(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event)
                    .data(payload, org.springframework.http.MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // 客户端断开是常态（关页面、切网），不是错误；订阅者列表由 unbind 兜底清理
            log.debug("[flow-stream] 推送失败，忽略：{}", e.getMessage());
        }
    }

    private static final class Channel {

        private final List<StepEvent> steps = new CopyOnWriteArrayList<>();
        private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
        private boolean done;
        private Object payload;

    }
}
