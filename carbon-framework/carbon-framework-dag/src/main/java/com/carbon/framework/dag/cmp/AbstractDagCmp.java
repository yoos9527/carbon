package com.carbon.framework.dag.cmp;

import com.carbon.framework.dag.dsl.node.NodeDef;
import com.carbon.framework.dag.runtime.DagExecutionContext;
import com.yomahub.liteflow.core.NodeComponent;

/**
 * 组件基类：只做三件事——拿上下文、反查节点定义、按类型取执行体。
 *
 * <p>LiteFlow 的组件是无状态单例，所以<b>不能</b>把本次执行的节点定义存在字段上；
 * 每个组件都通过 {@code getNodeId()} 去上下文里反查，这是多实例并发执行正确的前提。
 *
 * @author carbon
 */
public abstract class AbstractDagCmp extends NodeComponent {

    /**
     * @return 运行时句柄
     */
    protected final DagExecutionContext ctx() {
        DagExecutionContext context = getContextBean(DagExecutionContext.class);
        if (context == null) {
            throw new IllegalStateException("LiteFlow 上下文里没有 DagExecutionContext，"
                    + "调用方必须把引擎实现的上下文作为上下文 Bean 传入");
        }
        return context;
    }

    /**
     * @return 本组件对应的节点定义
     */
    protected final NodeDef node() {
        String elId = getNodeId();
        NodeDef node = ctx().nodeDef(elId);
        if (node == null) {
            throw new IllegalStateException("EL 节点 " + elId + " 没有对应的节点定义，"
                    + "通常说明该流程未通过 ChainRegistry 注册");
        }
        return node;
    }

    /**
     * @return 普通节点执行体
     */
    protected final NodeHandler handler() {
        NodeHandler handler = ctx().service(NodeHandler.class);
        if (handler == null) {
            throw new IllegalStateException("引擎没有注册 NodeHandler");
        }
        return handler;
    }

    /**
     * @return 布尔求值体
     */
    protected final BooleanNodeHandler booleanHandler() {
        BooleanNodeHandler handler = ctx().service(BooleanNodeHandler.class);
        if (handler == null) {
            throw new IllegalStateException("引擎没有注册 BooleanNodeHandler");
        }
        return handler;
    }

    /**
     * @return 迭代体
     */
    protected final IteratorNodeHandler iteratorHandler() {
        IteratorNodeHandler handler = ctx().service(IteratorNodeHandler.class);
        if (handler == null) {
            throw new IllegalStateException("引擎没有注册 IteratorNodeHandler");
        }
        return handler;
    }
}
