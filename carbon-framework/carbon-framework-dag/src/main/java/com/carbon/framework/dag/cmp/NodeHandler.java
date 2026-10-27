package com.carbon.framework.dag.cmp;

import com.carbon.framework.dag.dsl.node.NodeDef;
import com.carbon.framework.dag.runtime.DagExecutionContext;

/**
 * 普通节点（START/END/CONNECTOR_CALL/SCRIPT/DELAY）的执行体。
 *
 * <p>由 {@code carbon-engine} 实现并通过 {@link DagExecutionContext#service(Class)} 提供。
 * 抛异常即视为节点失败，LiteFlow 会按 EL 上的 {@code onError}/重试策略处理。
 *
 * @author carbon
 */
public interface NodeHandler {

    /**
     * 执行节点。
     *
     * @param node 节点定义
     * @param ctx  运行时句柄
     * @throws Exception 执行失败
     */
    void execute(NodeDef node, DagExecutionContext ctx) throws Exception;
}
