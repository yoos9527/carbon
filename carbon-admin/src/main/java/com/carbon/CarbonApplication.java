package com.carbon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;

/**
 * Carbon 启动类。
 *
 * <p>本模块是唯一的可执行单元，只负责装配与启动，不承载任何业务逻辑。
 *
 * @author carbon
 */
@SpringBootApplication
public class CarbonApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(CarbonApplication.class);
        // 启动阶段埋点，便于用 /actuator/startup 排查慢启动
        application.setApplicationStartup(new BufferingApplicationStartup(2048));
        application.run(args);
    }
}
