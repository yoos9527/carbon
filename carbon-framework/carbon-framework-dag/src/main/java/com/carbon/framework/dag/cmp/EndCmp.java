package com.carbon.framework.dag.cmp;

/**
 * 出口节点，只记一条步骤，不做业务（对应节点类型 {@code END}）。
 *
 * <p>框架侧只做转发：具体执行逻辑由 {@link NodeHandler} 提供，实现放在 {@code carbon-engine}。
 *
 * @author carbon
 */
public class EndCmp extends AbstractDagCmp {

    @Override
    public void process() throws Exception {
        handler().execute(node(), ctx());
    }
}
