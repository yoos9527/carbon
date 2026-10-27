package com.carbon.framework.dag.validator;

/**
 * 外部校验 SPI。
 *
 * <p>{@code carbon-framework-dag} 是 L0 模块（零 carbon 依赖、不碰 Spring、不碰数据库），
 * 所以「表达式能不能解析」「脚本能不能编译」「connector 存不存在」这三件事不能自己干，
 * 由 {@code carbon-flow} / {@code carbon-engine} 实现并注入。
 *
 * <p>返回值语义：{@code null} 表示通过，非 {@code null} 为面向用户的错误文案。
 *
 * @author carbon
 */
public interface DagCheckers {

    /**
     * 表达式语法检查。
     *
     * @param expr   表达式原文
     * @param nodeId 所属节点
     * @return null 表示通过
     */
    default String checkExpression(String expr, String nodeId) {
        return null;
    }

    /**
     * 脚本编译检查。
     *
     * @param script    脚本原文
     * @param lang      语言
     * @param timeoutMs 节点级超时（可空）
     * @param nodeId    所属节点
     * @return null 表示通过
     */
    default String checkScript(String script, String lang, Long timeoutMs, String nodeId) {
        return null;
    }

    /**
     * 连接器接口引用检查。
     *
     * @param connector 连接器 key
     * @param operation 接口 key
     * @param nodeId    所属节点
     * @return null 表示通过
     */
    default String checkReference(String connector, String operation, String nodeId) {
        return null;
    }

    /**
     * 什么都不查的实现（纯结构校验场景/单测）。
     *
     * @return 空实现
     */
    static DagCheckers none() {
        return new DagCheckers() {
        };
    }
}
