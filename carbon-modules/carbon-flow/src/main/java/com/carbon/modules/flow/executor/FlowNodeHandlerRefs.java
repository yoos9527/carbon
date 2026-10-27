package com.carbon.modules.flow.executor;

import com.carbon.framework.dag.cmp.BooleanNodeHandler;
import com.carbon.framework.dag.cmp.IteratorNodeHandler;
import com.carbon.framework.dag.cmp.NodeHandler;
import com.carbon.modules.flow.handler.FlowBooleanHandler;
import com.carbon.modules.flow.handler.FlowIteratorHandler;
import com.carbon.modules.flow.handler.FlowNodeHandler;
import org.springframework.stereotype.Component;

/**
 * 三个执行体的持有者。
 *
 * <p>为什么不直接在 {@code FlowRunner} 里注入三个类：执行期只需要往 {@link FlowState} 里塞
 * {@code NodeHandler}/{@code BooleanNodeHandler}/{@code IteratorNodeHandler} 三个 SPI，
 * 多一层壳让「谁被注册进运行时上下文」在一处可见，不在 runner 里散落三行 register。
 *
 * @author carbon
 */
@Component
public class FlowNodeHandlerRefs {

    private final NodeHandler node;
    private final BooleanNodeHandler bool;
    private final IteratorNodeHandler iterator;

    public FlowNodeHandlerRefs(FlowNodeHandler node, FlowBooleanHandler bool, FlowIteratorHandler iterator) {
        this.node = node;
        this.bool = bool;
        this.iterator = iterator;
    }

    public NodeHandler node() {
        return node;
    }

    public BooleanNodeHandler bool() {
        return bool;
    }

    public IteratorNodeHandler iterator() {
        return iterator;
    }
}
