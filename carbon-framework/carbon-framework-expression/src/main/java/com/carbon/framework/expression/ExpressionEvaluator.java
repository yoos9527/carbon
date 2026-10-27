package com.carbon.framework.expression;

import java.util.Map;

/**
 * 表达式求值器契约。
 *
 * <p>只负责「单个表达式 + 变量池 → Java 值」，不知道模板边界、不拼报文、不做编码。
 * 默认实现是 {@code AviatorExpressionEvaluator}，兜底实现是 {@code SimpleExpressionEvaluator}。
 *
 * @author carbon
 */
public interface ExpressionEvaluator {

    /**
     * 求值。
     *
     * @param expr 表达式（不含 {@code {{}}} 包装）
     * @param vars 变量池
     * @return 结果；变量缺失时为 {@code null}
     */
    Object eval(String expr, Map<String, Object> vars);

    /**
     * 求值并断言为布尔（DAG 的 CONDITION 节点使用）。
     *
     * @param expr 表达式
     * @param vars 变量池
     * @return 布尔结果
     * @throws RenderException 结果不是布尔值（按 {@code PARAM_INVALID} 处理）
     */
    default boolean test(String expr, Map<String, Object> vars) {
        Object value = eval(expr, vars);
        if (value instanceof Boolean b) {
            return b;
        }
        throw RenderException.paramInvalid("条件表达式不是布尔值: " + expr
                + "，实际类型=" + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    /**
     * 实现名称，便于日志与选型排查。
     *
     * @return 实现名
     */
    String name();
}
