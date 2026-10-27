package com.carbon.framework.dag.dsl.node;


import java.util.Map;

/**
 * 条件分支节点，出边 2 条，由 {@code branches} 指定。
 *
 * <p>{@code expr} 用 {@code carbon-framework-expression} 的求值核（默认 Aviator），
 * 可读 {@code vars} / {@code input}，必须返回布尔值。
 *
 * @param id       节点 id
 * @param expr     布尔表达式
 * @param branches {@code true}/{@code false} → 目标节点 id
 * @author carbon
 */
public record CondNode(String id, String expr, Map<String, String> branches) implements NodeDef {

    /** 真分支的键。 */
    public static final String TRUE = "true";

    /** 假分支的键。 */
    public static final String FALSE = "false";

    public CondNode {
        Nodes.requireId(id, "CONDITION");
        Nodes.requireText(expr, "CONDITION.expr");
        branches = branches == null ? Map.of() : Map.copyOf(branches);
    }

    /** @return 真分支目标，可为 null */
    public String whenTrue() {
        return branches.get(TRUE);
    }

    /** @return 假分支目标，可为 null */
    public String whenFalse() {
        return branches.get(FALSE);
    }
}
