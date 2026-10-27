package com.carbon.modules.flow.config;

import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.property.LiteflowConfig;
import com.yomahub.liteflow.property.LiteflowConfigGetter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LiteFlow 装配。
 *
 * <p>没有引 {@code liteflow-spring-boot-starter}：本项目不用它的「规则文件/数据库规则源」，
 * 链路全部由 {@code ChainRegistry} 在发布时编程式注册。用 starter 反而会把
 * {@code rule-source} 变成必填配置，启动时报 `config error`。
 *
 * <p>三个开关是必需的：
 * <ul>
 *   <li>{@code supportMultipleType=true}：允许同一流程里混用普通/布尔/迭代组件；</li>
 *   <li>{@code printBanner=false}：别在启动日志里打 ASCII 艺术字；</li>
 *   <li>{@code printExecutionLog=false}：执行日志我们自己落 {@code flow_step_run}，
 *       让 LiteFlow 再打一遍就是双份噪音。</li>
 * </ul>
 *
 * @author carbon
 */
@Configuration
public class LiteFlowConfigs {

    /**
     * 配置必须是 **Spring Bean**。
     *
     * <p>踩过的坑：只在 {@link LiteflowConfigGetter} 里塞一个静态实例不够——Spring 环境下
     * {@code FlowExecutor.doExecute()} 的懒初始化会去容器里找 {@code LiteflowConfig} Bean，
     * 找不到就抛 {@code ConfigErrorException: config error, please check liteflow config property}，
     * 而且是在**第一次执行流程时**才抛，启动期完全看不出来。
     */
    @Bean
    public LiteflowConfig liteflowConfig() {
        LiteflowConfig config = new LiteflowConfig();
        config.setPrintBanner(false);
        config.setPrintExecutionLog(false);
        config.setSupportMultipleType(true);
        LiteflowConfigGetter.setLiteflowConfig(config);
        return config;
    }

    @Bean
    public FlowExecutor liteFlowExecutor(LiteflowConfig config) {
        // 必须走带配置的构造函数：无参构造函数 + 静态 Getter 在 Spring 环境里**不够**
        // （懒初始化时它只看自己字段，静态 Getter 那套是非 Spring 用法）
        FlowExecutor executor = new FlowExecutor(config);
        // 启动期就初始化：配置错、规则源错要在启动时炸，不要等到用户点「运行」
        executor.init(true);
        return executor;
    }
}
