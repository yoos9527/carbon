package com.carbon;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Carbon 启动类。
 *
 * <p>本模块是唯一的可执行单元，只负责装配与启动，不承载任何业务逻辑。
 *
 * <p>{@code @EnableScheduling} 放在这里而不是各业务模块：定时任务属于「谁来驱动」的问题，
 * 归启动模块管；业务模块只声明「我有个任务」，不决定调度器存在与否——
 * 否则二方包单独测试时会被迫拉起一个调度线程池。
 *
 * @author carbon
 */
@SpringBootApplication
@EnableScheduling
public class CarbonApplication {

    /**
     * 平台时区。必须在 {@code SpringApplication.run} 之前设置：一旦有线程读到了默认时区，
     * 之后改 {@code TimeZone.setDefault} 也不会回头修正已经算过的值。
     *
     * <p>为什么必须钉住：库里同时存在两种时间来源——
     * <ul>
     *   <li>DDL 默认值 {@code CURRENT_TIMESTAMP(3)}，按 MySQL 的 {@code --default-time-zone=+08:00}
     *       写入（见 {@code script/docker/docker-compose.yml}）；
     *   <li>业务代码里的 {@code LocalDateTime.now()}（如 {@code FlowRunner} 写 {@code started_at}、
     *       {@code AsyncLogWriter} 写 {@code created_at}）。
     * </ul>
     * 两者必须同一个时区，否则同一张库里 {@code job.created_at} 是 19:01、{@code flow_run.started_at}
     * 却是 11:03，差整 8 小时——排查时看到「任务 8 小时前触发、运行记录刚刚开始」这种鬼话。
     * 容器镜像本身通常没有 tzdata，{@code TZ} 环境变量也可能没被传进来，所以钉在代码里最可靠。
     *
     * <p>只在 {@code main} 里设，不在测试或二方包里设：这是「部署形态」的决策，不该传染给
     * 复用这些包的库使用者。
     */
    private static final String PLATFORM_TIMEZONE = "Asia/Shanghai";

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone(PLATFORM_TIMEZONE));
        SpringApplication application = new SpringApplication(CarbonApplication.class);
        // 启动阶段埋点，便于用 /actuator/startup 排查慢启动
        application.setApplicationStartup(new BufferingApplicationStartup(2048));
        application.run(args);
    }
}
