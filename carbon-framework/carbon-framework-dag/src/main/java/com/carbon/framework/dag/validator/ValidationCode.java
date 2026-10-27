package com.carbon.framework.dag.validator;

/**
 * 校验错误码。
 *
 * <p>码值稳定、可直接落到前端表单上做字段级提示；<b>文案会改，码不会</b>。
 *
 * @author carbon
 */
public enum ValidationCode {

    /** 流程没有任何节点。 */
    FLOW_EMPTY,
    /** 节点数超过 500。 */
    NODE_COUNT_EXCEEDED,
    /** 整体超时超过 600000ms。 */
    TIMEOUT_EXCEEDED,
    /** 节点 id 重复。 */
    DUPLICATE_NODE_ID,
    /** START 数量不为 1。 */
    START_COUNT_INVALID,
    /** 没有任何 END。 */
    END_COUNT_INVALID,
    /** 边的端点不存在。 */
    EDGE_ENDPOINT_MISSING,
    /** 非入口节点缺少入边。 */
    MISSING_IN_EDGE,
    /** 非出口节点缺少出边。 */
    MISSING_OUT_EDGE,
    /** 从入口走不到。 */
    ORPHAN_NODE,
    /** 出口节点（END / bodyEnd）带了出边。 */
    TERMINAL_HAS_OUT_EDGE,
    /** 出边条数不符合节点类型的约定。 */
    OUT_DEGREE_INVALID,
    /** 存在环（回边）。 */
    EDGE_BACKWARD,
    /** 两条边交叉。 */
    EDGE_CROSSING,
    /** 多入边节点不是其前驱的最近公共汇聚点。 */
    NO_CONVERGENCE,
    /** CONDITION 分支目标缺失。 */
    BRANCH_TARGET_MISSING,
    /** CONDITION 的出边与 branches 不一致（编译以 branches 为准，出边只喂图形分析，错开会静默编错） */
    BRANCH_EDGE_MISMATCH,
    /** LOOP/FOREACH 的 body 或 bodyEnd 缺失。 */
    BODY_TARGET_MISSING,
    /** 子图内节点到不了 bodyEnd。 */
    BODY_END_UNREACHABLE,
    /** 子图有边漏到容器之外。 */
    BODY_LEAKS_OUT,
    /** 容器子图包含自身。 */
    BODY_CONTAINS_SELF,
    /** 子图与所属作用域重叠。 */
    NESTED_SCOPE_CONFLICT,
    /** 表达式语法错误。 */
    EXPRESSION_INVALID,
    /** 脚本编译失败。 */
    SCRIPT_INVALID,
    /** 引用的 connector/operation 不存在或已停用。 */
    REFERENCE_INVALID
}
