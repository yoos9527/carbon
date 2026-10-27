package com.carbon.framework.dag.cmp;

/**
 * 连接器调用，真正的三方请求由引擎的 NodeHandler 完成（对应节点类型 {@code CONNECTOR_CALL}）。
 *
 * <p>框架侧只做转发：具体执行逻辑由 {@link NodeHandler} 提供，实现放在 {@code carbon-engine}。
 *
 * @author carbon
 */
public class CallCmp extends AbstractDagCmp {

    @Override
    public void process() throws Exception {
        handler().execute(node(), ctx());
    }
}
