package com.carbon.framework.dag.cmp;

import com.carbon.framework.dag.dsl.node.LoopNode;
import com.carbon.framework.dag.dsl.node.NodeDef;
import com.carbon.framework.dag.runtime.DagExecutionContext;
import com.yomahub.liteflow.core.NodeBooleanComponent;

/**
 * 循环节点（对应 {@code LOOP}）：作为 {@code WHILE(cond).DO(...)} 的条件。
 *
 * <p><b>为什么计数必须在框架侧做</b>：LiteFlow 的 {@code WHILE} 只看布尔条件，
 * 自身没有任何次数上限，条件一旦恒为真就是死循环（实测会把测试 JVM 挂死）。
 * 所以这里先把每轮计数写进执行上下文的 vars（key = {@code __loop_count_{节点id}}），
 * 到 {@code maxTimes} 直接返回 false 收口；{@code continueWhen} 交给引擎的
 * {@link BooleanNodeHandler} 之外，次数的兜底不依赖引擎实现。
 *
 * @author carbon
 */
public class LoopCmp extends NodeBooleanComponent {

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
        if (!(node instanceof LoopNode loop)) {
            throw new IllegalStateException("EL 节点 " + getNodeId() + " 不是 LOOP 节点");
        }
        String counterKey = "__loop_count_" + loop.id();
        int times = (int) context.vars().getOrDefault(counterKey, 0);
        if (times >= loop.maxTimes()) {
            return false;
        }
        context.vars().put(counterKey, times + 1);
        return handler.test(node, context);
    }
}
