package com.carbon;

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

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(CarbonApplication.class);
        // 启动阶段埋点，便于用 /actuator/startup 排查慢启动
        application.setApplicationStartup(new BufferingApplicationStartup(2048));
        application.run(args);
    }
}
