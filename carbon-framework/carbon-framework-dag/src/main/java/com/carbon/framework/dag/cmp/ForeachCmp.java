package com.carbon.framework.dag.cmp;

import com.carbon.framework.dag.dsl.node.ForeachNode;
import com.carbon.framework.dag.dsl.node.NodeDef;
import com.carbon.framework.dag.runtime.DagExecutionContext;
import com.yomahub.liteflow.core.NodeIteratorComponent;

import java.util.Iterator;

/**
 * 遍历节点（对应 {@code FOREACH}）：作为 {@code ITERATOR(item).DO(...)} 的迭代源。
 *
 * <p>每次取下一个元素时顺手写进变量池（{@code vars.<itemVar>}），子图里的组件就能读到。
 * LiteFlow 保证同一节点的 {@code processIterator} 与子图执行在同一线程内推进。
 *
 * @author carbon
 */
public class ForeachCmp extends NodeIteratorComponent {

    @Override
    public Iterator<?> processIterator() throws Exception {
        DagExecutionContext context = getContextBean(DagExecutionContext.class);
        if (context == null) {
            throw new IllegalStateException("LiteFlow 上下文里没有 DagExecutionContext");
        }
        IteratorNodeHandler handler = context.service(IteratorNodeHandler.class);
        if (handler == null) {
            throw new IllegalStateException("引擎没有注册 IteratorNodeHandler");
        }
        NodeDef node = context.nodeDef(getNodeId());
        if (!(node instanceof ForeachNode foreach)) {
            throw new IllegalStateException("EL 节点 " + getNodeId() + " 不是 FOREACH 节点");
        }
        Iterator<?> source = handler.iterate(foreach, context);
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return source.hasNext();
            }

            @Override
            public Object next() {
                Object item = source.next();
                context.bindItem(foreach.itemVar(), item);
                return item;
            }
        };
    }
}
