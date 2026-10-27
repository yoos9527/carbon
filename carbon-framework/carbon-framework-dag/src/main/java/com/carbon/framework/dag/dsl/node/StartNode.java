package com.carbon.framework.dag.dsl.node;


/**
 * 入口节点：全图恰好 1 个，只有出边。
 *
 * @param id 节点 id
 * @author carbon
 */
public record StartNode(String id) implements NodeDef {

    public StartNode {
        Nodes.requireId(id, "START");
    }
}
