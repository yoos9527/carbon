package com.carbon.modules.flow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步试跑用的线程池。
 *
 * <p>刻意<b>不用</b> {@code @Async} 默认池：默认池是全局共享的，一条慢流程能把别的模块
 * 的异步任务饿死；队列容量也必须显式——无界队列会让「跑不动的流程」把内存吃光。
 *
 * <p>拒绝策略用 Abort：宁可当场告诉用户「队列满了」，也不要静默排队到天荒地老。
 * 拒绝时调用方会把那条 RUNNING 记录收成 FAILED，不留幽灵。
 *
 * @author carbon
 */
@Configuration
public class FlowAsyncConfig {

    public static final String EXECUTOR = "flowRunExecutor";

    @Bean(name = EXECUTOR, destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor flowRunExecutor(
            @Value("${carbon.flow.async.core-size:4}") int coreSize,
            @Value("${carbon.flow.async.max-size:8}") int maxSize,
            @Value("${carbon.flow.async.queue-capacity:200}") int queueCapacity,
            @Value("${carbon.flow.async.keep-alive-seconds:60}") int keepAliveSeconds) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix("flow-run-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 停机时等手头的流程跑完：编排跑一半被 kill，三方侧可能已经下单
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
