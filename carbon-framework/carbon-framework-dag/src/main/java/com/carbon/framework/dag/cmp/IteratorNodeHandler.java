package com.carbon.framework.dag.cmp;

import com.carbon.framework.dag.dsl.node.NodeDef;
import com.carbon.framework.dag.runtime.DagExecutionContext;

import java.util.Iterator;

/**
 * FOREACH 的迭代体：把 {@code items} 解析成迭代器。
 *
 * @author carbon
 */
public interface IteratorNodeHandler {

    /**
     * 解析集合。
     *
     * @param node 节点定义
     * @param ctx  运行时句柄
     * @return 迭代器（空集合返回空迭代器，不返回 null）
     * @throws Exception 解析失败
     */
    Iterator<?> iterate(NodeDef node, DagExecutionContext ctx) throws Exception;
}
