package com.carbon.framework.dag.compiler;

import com.carbon.framework.dag.dsl.node.NodeDef;

import java.util.Map;

/**
 * 编译产物。
 *
 * @param el       LiteFlow EL 表达式
 * @param elIdOf   DSL 节点 id → EL 节点 id
 * @param nodeDefs EL 节点 id → 节点定义（注册组件与回写步骤都用它）
 * @author carbon
 */
public record CompiledFlow(String el, Map<String, String> elIdOf, Map<String, NodeDef> nodeDefs) {

    public CompiledFlow {
        elIdOf = elIdOf == null ? Map.of() : Map.copyOf(elIdOf);
        nodeDefs = nodeDefs == null ? Map.of() : Map.copyOf(nodeDefs);
    }

    /**
     * EL 节点 id 反查 DSL 节点 id。
     *
     * @param elId EL 节点 id
     * @return DSL 节点 id，未知返回 null
     */
    public String dslIdOf(String elId) {
        for (Map.Entry<String, String> entry : elIdOf.entrySet()) {
            if (entry.getValue().equals(elId)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
