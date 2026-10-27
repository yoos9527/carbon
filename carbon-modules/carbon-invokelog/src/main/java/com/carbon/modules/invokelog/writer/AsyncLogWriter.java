package com.carbon.modules.invokelog.writer;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.carbon.common.json.JsonUtils;
import com.carbon.modules.invokelog.api.InvokeLogEvent;
import com.carbon.modules.invokelog.config.LogProperties;
import com.carbon.modules.invokelog.api.InvokeLogSink;
import com.carbon.modules.invokelog.domain.entity.InvokeLog;
import com.carbon.modules.invokelog.mapper.InvokeLogMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步日志写入器。
 *
 * <p>三件事决定了它的形状：
 * <ol>
 *   <li><b>调用线程绝不落库</b>：三方调用的 P99 会被数据库抖动直接污染，所以引擎线程只做
 *       {@code offer}，满了就丢弃并计数——丢日志是可接受的运维事故，拖慢业务不是。</li>
 *   <li><b>攒批</b>：一条 insert 一次往返，QPS 上百时数据库光在网络往返上就忙不过来；
 *       攒到 {@code platform.log.async-batch-size} 条或满 {@code platform.log.async-flush-interval-ms} 就写一批。</li>
 *   <li><b>写入失败不重试</b>：重试会把队头堵住，进而把后面所有日志一起拖垮。
 *       失败计数暴露出去，让运维用 {@code dropped} 这个指标判断要不要扩容/调批次。</li>
 * </ol>
 *
 * @author carbon
 */
@Component
public class AsyncLogWriter implements InvokeLogSink {

    private static final Logger log = LoggerFactory.getLogger(AsyncLogWriter.class);

    private final BlockingQueue<InvokeLogEvent> queue;
    private final InvokeLogMapper logMapper;
    private final int batchSize;

    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public AsyncLogWriter(InvokeLogMapper logMapper, LogProperties properties) {
        this.logMapper = logMapper;
        // 容量/批次来自 platform.log.*（默认 10k 条 / 200 条一批），按单条日志均值 2KB
        // 估算 10k 条约占 20MB 堆。写成配置是因为「队列该多大」取决于部署内存与峰值 QPS，
        // 不是一个能拍死的常数。
        this.queue = new ArrayBlockingQueue<>(Math.max(1, properties.getAsyncQueueCapacity()));
        this.batchSize = Math.max(1, properties.getAsyncBatchSize());
    }

    @Override
    public void record(InvokeLogEvent event) {
        if (!queue.offer(event)) {
            long total = dropped.incrementAndGet();
            // 每丢 1000 条打一次，避免队列打满时日志本身把磁盘打满
            if (total % 1000 == 1) {
                log.warn("调用日志队列已满，累计丢弃 {} 条（队列容量 {}）", total, queue.size() + queue.remainingCapacity());
            }
        }
    }

    /**
     * 定时刷盘。{@code fixedDelay} 而不是 {@code fixedRate}：上一批慢了就让它慢，
     * 不要用并发的多个批次把数据库压得更死。
     */
    @Scheduled(fixedDelayString = "${platform.log.async-flush-interval-ms:1000}")
    public void flush() {
        if (queue.isEmpty()) {
            return;
        }
        List<InvokeLogEvent> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        if (batch.isEmpty()) {
            return;
        }
        try {
            for (InvokeLogEvent event : batch) {
                logMapper.insert(toEntity(event));
            }
            written.addAndGet(batch.size());
        } catch (RuntimeException e) {
            failed.addAndGet(batch.size());
            log.warn("写调用日志失败 {} 条：{}", batch.size(), e.toString());
        }
    }

    /**
     * 停机前把队列里剩下的日志尽量写完。
     *
     * <p>上限 5 秒：停机不能让发布流水线卡在一条日志上。
     */
    @PreDestroy
    public void shutdown() {
        int remaining = queue.size();
        while (!queue.isEmpty() && remaining-- > 0) {
            flush();
        }
        log.info("调用日志写入器关闭：已写 {}，失败 {}，丢弃 {}", written.get(), failed.get(), dropped.get());
    }

    public long droppedCount() {
        return dropped.get();
    }

    public long writtenCount() {
        return written.get();
    }

    public long failedCount() {
        return failed.get();
    }

    public int queueSize() {
        return queue.size();
    }

    private InvokeLog toEntity(InvokeLogEvent event) {
        InvokeLog entity = new InvokeLog();
        entity.setId(IdWorker.getId());
        entity.setTraceId(event.traceId());
        entity.setSource(event.source());
        entity.setAppId(event.appId());
        entity.setFlowRunId(event.flowRunId());
        entity.setConnectorId(event.connectorId());
        entity.setOperationId(event.operationId());
        entity.setMethod(event.method());
        entity.setRealUrl(truncate(event.realUrl(), 1024));
        entity.setReqHeadersMasked(event.reqHeaders() == null || event.reqHeaders().isEmpty()
                ? null : JsonUtils.toJson(event.reqHeaders()));
        entity.setReqBody(event.reqBody());
        entity.setRespStatus(event.respStatus());
        entity.setRespBody(event.respBody());
        entity.setDurationMs((int) Math.min(event.durationMs(), Integer.MAX_VALUE));
        entity.setErrCode(event.errCode());
        entity.setErrMsg(truncate(event.errMsg(), 1000));
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
