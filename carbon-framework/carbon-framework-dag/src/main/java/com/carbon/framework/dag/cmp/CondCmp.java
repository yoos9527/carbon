package com.carbon.framework.dag.cmp;

import com.carbon.framework.dag.dsl.node.NodeDef;
import com.carbon.framework.dag.runtime.DagExecutionContext;
import com.yomahub.liteflow.core.NodeBooleanComponent;

/**
 * 条件节点（对应 {@code CONDITION}）：用表达式求值决定走 true 还是 false 分支。
 *
 * @author carbon
 */
public class CondCmp extends NodeBooleanComponent {

    @Override
    public boolean processBoolean() throws Exception {
        DagExecutionContext context = getContextBean(DagExecutionContext.class);
        if (context == null) {
            throw new IllegalStateException("LiteFlow 上下文里没有 DagExecutionContext");
        }
        BooleanNodeHandler handler = context.service(BooleanNodeHandler.class);
        if (handler == null) {
            throw new IllegalStateException("引擎没有注册 BooleanNodeHandler");
        }
        NodeDef node = context.nodeDef(getNodeId());
        if (node == null) {
            throw new IllegalStateException("EL 节点 " + getNodeId() + " 没有对应的节点定义");
        }
        return handler.test(node, context);
    }
}
