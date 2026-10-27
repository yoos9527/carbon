package com.carbon.framework.dag.dsl.node;


/**
 * 出口节点：可以有多个（各分支各自终止），没有出边。
 *
 * @param id 节点 id
 * @author carbon
 */
public record EndNode(String id) implements NodeDef {

    public EndNode {
        Nodes.requireId(id, "END");
    }
}
