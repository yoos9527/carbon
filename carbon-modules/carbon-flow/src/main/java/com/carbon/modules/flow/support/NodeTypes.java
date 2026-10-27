package com.carbon.modules.flow.support;

import com.carbon.framework.dag.dsl.node.NodeDef;

/**
 * DSL 节点 → {@code flow_step_run.node_type} 的取值。
 *
 * <p>用的是「类名去 Node 后缀大写」，历史数据（{@code StepMapper} 落的那批）就是这个形状，
 * 换算法会让老记录的 node_type 和新记录对不上，看板按类型聚合时就分家了。
 *
 * @author carbon
 */
public final class NodeTypes {

    private NodeTypes() {
    }

    public static String of(NodeDef node) {
        if (node == null) {
            return "UNKNOWN";
        }
        return node.getClass().getSimpleName().replace("Node", "").toUpperCase();
    }
}
