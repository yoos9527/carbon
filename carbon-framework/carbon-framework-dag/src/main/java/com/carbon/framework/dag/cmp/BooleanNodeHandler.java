package com.carbon.framework.dag.cmp;

import com.carbon.framework.dag.dsl.node.NodeDef;
import com.carbon.framework.dag.runtime.DagExecutionContext;

/**
 * 布尔节点的求值体：CONDITION 的分支表达式、LOOP 的继续条件。
 *
 * @author carbon
 */
public interface BooleanNodeHandler {

    /**
     * 求值。
     *
     * @param node 节点定义
     * @param ctx  运行时句柄
     * @return 布尔结果
     * @throws Exception 求值失败
     */
    boolean test(NodeDef node, DagExecutionContext ctx) throws Exception;
}
