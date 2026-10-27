package com.carbon.modules.flow.executor;

import com.carbon.framework.dag.dsl.node.NodeDef;
import com.carbon.framework.dag.runtime.DagExecutionContext;

import java.util.Map;

/**
 * LiteFlow 的上下文 Bean。
 *
 * <p>存在的唯一理由是「LiteFlow 要一个能用无参构造 new 出来的类」：
 * {@code execute2Resp(chainId, param, FlowCtxBean.class)} 之后，组件里
 * {@code getContextBean(DagExecutionContext.class)} 拿到的就是它。
 *
 * <p>它不存任何状态，全部委托给 {@link FlowState#current()}——状态必须随运行线程走，
 * 不能挂在这个被 LiteFlow 自己创建、我们无法初始化的对象上。
 *
 * @author carbon
 */
public class FlowCtxBean implements DagExecutionContext {

    @Override
    public String flowRunId() {
        return FlowState.current().flowRunId();
    }

    @Override
    public NodeDef nodeDef(String elId) {
        return FlowState.current().nodeDef(elId);
    }

    @Override
    public Map<String, Object> vars() {
        return FlowState.current().vars();
    }

    @Override
    public void bindItem(String itemVar, Object item) {
        FlowState.current().bindItem(itemVar, item);
    }

    @Override
    public <T> T service(Class<T> type) {
        return FlowState.current().service(type);
    }
}
