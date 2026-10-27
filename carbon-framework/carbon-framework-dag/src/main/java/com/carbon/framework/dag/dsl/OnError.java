package com.carbon.framework.dag.dsl;

/**
 * 节点失败后的处置方式。
 *
 * <p><b>故意没有 GOTO</b>：任意跳转会破坏“结构化树形图”这个编译前提，
 * 需要「失败后再做点什么」就用 {@link NodeType#CONDITION} 判 {@code vars.<nodeId>.failed}。
 *
 * @author carbon
 */
public enum OnError {

    /** 抛 {@code DagNodeException}，整条流程失败（默认）。 */
    STOP,

    /** 吞掉异常，把失败信息写进 {@code vars.<nodeId>}，流程继续，整体结果标记为 PARTIAL。 */
    CONTINUE
}
