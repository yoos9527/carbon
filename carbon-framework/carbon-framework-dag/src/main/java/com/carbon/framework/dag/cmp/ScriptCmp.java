package com.carbon.framework.dag.cmp;

/**
 * 脚本节点，沙箱在 carbon-framework-script（对应节点类型 {@code SCRIPT}）。
 *
 * <p>框架侧只做转发：具体执行逻辑由 {@link NodeHandler} 提供，实现放在 {@code carbon-engine}。
 *
 * @author carbon
 */
public class ScriptCmp extends AbstractDagCmp {

    @Override
    public void process() throws Exception {
        handler().execute(node(), ctx());
    }
}
