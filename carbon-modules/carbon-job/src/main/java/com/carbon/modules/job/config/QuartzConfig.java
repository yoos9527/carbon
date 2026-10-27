package com.carbon.modules.job.config;

import com.carbon.modules.job.quartz.FlowQuartzJob;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;

/**
 * Quartz 装配：JDBC JobStore + 集群。
 *
 * <p>为什么不用 {@code spring.quartz.*} 自动配置：Boot 的 JDBC 分支只在它自己创建
 * {@code @QuartzDataSource} 时才把 DataSource 交给 Quartz；表结构交给 Flyway（V4）管、
 * {@code initialize-schema=never} 时那条路径拿不到 DataSource，启动即
 * {@code SchedulerConfigException: DataSource name not set}（2026-10-27 实测）。
 * 自己 new {@link SchedulerFactoryBean} 反而只有一处真相：Quartz 的全部参数只在本类里，
 * {@code application.yml} 不再保留 {@code spring.quartz} 段——留着也是死的（自动配置已退让）。
 *
 * <p><b>JobFactory 必须换成 SpringBeanJobFactory</b>：{@link SchedulerFactoryBean} 默认用
 * {@code AdaptableJobFactory}，它只找无参构造器，对一个走构造器注入的
 * {@link FlowQuartzJob} 直接报 {@code NoSuchMethodException: FlowQuartzJob.<init>()}，
 * 触发器随即被打成 ERROR（2026-10-27 实测）。只调 {@code SchedulerFactoryBean#setApplicationContext}
 * <b>不够</b>——它不会换 JobFactory；真正做 AUTOWIRE_CONSTRUCTOR 实例化的是
 * {@code SpringBeanJobFactory}。
 *
 * @author carbon
 */
@Configuration
public class QuartzConfig {

    @Bean
    public SchedulerFactoryBean schedulerFactoryBean(DataSource dataSource, ApplicationContext applicationContext) {
        SchedulerFactoryBean factory = new SchedulerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setApplicationContext(applicationContext);
        // 让 Job 实例走 Spring 容器创建（构造器注入 + AUTOWIRE_CONSTRUCTOR），
        // 而不是 AdaptableJobFactory 的无参反射
        SpringBeanJobFactory jobFactory = new SpringBeanJobFactory();
        jobFactory.setApplicationContext(applicationContext);
        factory.setJobFactory(jobFactory);
        factory.setQuartzProperties(properties());
        factory.setAutoStartup(true);
        // 停机时等正在跑的流程收尾（与 server.shutdown=graceful 对齐）
        factory.setWaitForJobsToCompleteOnShutdown(true);
        // 允许覆盖 QRTZ_* 里的旧定义：升级时 JobDetail 的类名/参数可能变
        factory.setOverwriteExistingJobs(true);
        return factory;
    }

    /**
     * 不单独声明 {@code Scheduler} Bean：{@link SchedulerFactoryBean} 本身就是
     * {@code FactoryBean<Scheduler>}，容器里已经有 Scheduler 类型的单例；再声明一个同名产物
     * 会让按类型注入出现两个候选（只能靠参数名才解析得对），没必要冒这个险。
     */
    private Properties properties() {
        Properties p = new Properties();
        p.setProperty("org.quartz.scheduler.instanceName", "carbon-scheduler");
        // AUTO：集群里每个实例自增 id，不用手工给每台机器编号
        p.setProperty("org.quartz.scheduler.instanceId", "AUTO");
        // jobStore.class 故意不设：SchedulerFactoryBean 会用 putIfAbsent 把
        // org.springframework.scheduling.quartz.LocalDataSourceJobStore 填进去（它从
        // SchedulerFactoryBean 的 ThreadLocal 里取 DataSource）；一旦我们自己写了 JobStoreTX，
        // putIfAbsent 就不生效，启动报 SchedulerConfigException: DataSource name not set。
        p.setProperty("org.quartz.jobStore.driverDelegateClass",
                "org.quartz.impl.jdbcjobstore.StdJDBCDelegate");
        p.setProperty("org.quartz.jobStore.tablePrefix", "QRTZ_");
        p.setProperty("org.quartz.jobStore.isClustered", "true");
        // 15s 一次心跳：再小会把 QRTZ_SCHEDULER_STATE 打爆，再大故障发现就太慢
        p.setProperty("org.quartz.jobStore.clusterCheckinInterval", "15000");
        // 错过触发超过 60s 才算 misfire，之后由 misfire_policy 决定跳过还是补一次
        p.setProperty("org.quartz.jobStore.misfireThreshold", "60000");
        p.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        // 10 条线程：调度本身很轻（真正的耗时在流程内部），但同实例上的并发任务别太挤
        p.setProperty("org.quartz.threadPool.threadCount", "10");
        p.setProperty("org.quartz.threadPool.threadPriority", "5");
        return p;
    }
}
