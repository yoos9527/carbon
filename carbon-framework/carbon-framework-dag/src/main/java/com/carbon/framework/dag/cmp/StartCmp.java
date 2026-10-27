package com.carbon.framework.dag.cmp;

/**
 * 入口节点，只记一条步骤，不做业务（对应节点类型 {@code START}）。
 *
 * <p>框架侧只做转发：具体执行逻辑由 {@link NodeHandler} 提供，实现放在 {@code carbon-engine}。
 *
 * @author carbon
 */
public class StartCmp extends AbstractDagCmp {

    @Override
    public void process() throws Exception {
        handler().execute(node(), ctx());
    }
}
