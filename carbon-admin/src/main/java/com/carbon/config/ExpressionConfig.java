package com.carbon.config;

import com.carbon.framework.expression.AviatorEvaluatorAdapter;
import com.carbon.framework.expression.ExpressionEvaluator;
import com.carbon.framework.expression.TemplateRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 表达式内核与模板渲染壳的装配。
 *
 * <p>为什么装配点放在 {@code carbon-admin} 而不是 {@code carbon-framework-expression}：
 * 那两个模块的合格线是「零 Spring 依赖」（见 {@code docs/09-工程结构.md} §9.9），
 * 加上 {@code @Configuration} 就再也不能脱离 Spring 单测了。
 * 组合根放在可执行单元里，二方包保持纯粹。
 *
 * <p>{@code carbon.render.evaluator} 可切 {@code aviator}（默认）与 {@code simple}（兜底）。
 * 切换的意义在于排障：怀疑 Aviator 某条表达式行为异常时，切到 simple 能立刻判定是不是内核问题。
 *
 * @author carbon
 */
@Configuration
public class ExpressionConfig {

    /**
     * @param evaluator 内核名：{@code aviator} 或 {@code simple}
     */
    @Bean
    public ExpressionEvaluator expressionEvaluator(
            @Value("${carbon.render.evaluator:aviator}") String evaluator) {
        if ("simple".equalsIgnoreCase(evaluator)) {
            return new com.carbon.framework.expression.SimpleExpressionEvaluator();
        }
        return new AviatorEvaluatorAdapter();
    }

    /**
     * 模板缓存上限取 {@code carbon.render.placeholder-cache-size}，默认 2000：
     * 一个连接器接口大约 10 个模板，2000 覆盖 200 个接口的热路径，超出按 LRU 淘汰。
     */
    @Bean
    public TemplateRenderer templateRenderer(ObjectMapper objectMapper,
                                             ExpressionEvaluator expressionEvaluator,
                                             @Value("${carbon.render.placeholder-cache-size:2000}") int cacheSize) {
        return new TemplateRenderer(objectMapper, expressionEvaluator, cacheSize);
    }
}
