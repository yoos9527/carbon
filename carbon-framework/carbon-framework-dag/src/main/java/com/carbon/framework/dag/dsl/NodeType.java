package com.carbon.framework.dag.dsl;

/**
 * 节点类型。
 *
 * <p>与 {@link NodeDef} 的密封子类型一一对应；新增类型必须同时改这里、{@code NodeDef} 的
 * {@code permits} 列表、{@code ElCompiler} 的 {@code switch}、{@code GraphValidator} 的结构规则。
 *
 * @author carbon
 */
public enum NodeType {

    /** 入口，有且仅有 1 个。 */
    START,

    /** 出口，至少 1 个。 */
    END,

    /** 调用连接器接口。 */
    CONNECTOR_CALL,

    /** 布尔分支。 */
    CONDITION,

    /** 计次/条件循环。 */
    LOOP,

    /** 遍历集合。 */
    FOREACH,

    /** Groovy 脚本。 */
    SCRIPT,

    /** 延时。 */
    DELAY
}
