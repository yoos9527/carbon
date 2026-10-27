package com.carbon.framework.dag.cmp;

/**
 * 延时节点（对应节点类型 {@code DELAY}）。
 *
 * <p>框架侧只做转发：具体执行逻辑由 {@link NodeHandler} 提供，实现放在 {@code carbon-engine}。
 *
 * @author carbon
 */
public class DelayCmp extends AbstractDagCmp {

    @Override
    public void process() throws Exception {
        handler().execute(node(), ctx());
    }
}
