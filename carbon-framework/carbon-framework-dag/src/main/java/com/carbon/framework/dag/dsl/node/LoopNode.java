package com.carbon.framework.dag.dsl.node;


/**
 * 循环节点：重复执行 {@code body} 到 {@code bodyEnd} 圈定的子图。
 *
 * <p>子图收尾由 {@code bodyEnd} 声明，**不画回边**——回边会破坏“结构化树形图”这个编译前提。
 *
 * @param id           节点 id
 * @param maxTimes     最多执行次数（≥1）
 * @param continueWhen 继续条件（可空，空表示仅受次数约束）
 * @param body         子图入口
 * @param bodyEnd      子图收尾节点
 * @author carbon
 */
public record LoopNode(String id, int maxTimes, String continueWhen,
                       String body, String bodyEnd) implements NodeDef {

    public LoopNode {
        Nodes.requireId(id, "LOOP");
        Nodes.requireText(body, "LOOP.body");
        Nodes.requireText(bodyEnd, "LOOP.bodyEnd");
        if (maxTimes < 1) {
            throw new IllegalArgumentException("LOOP.maxTimes 必须 ≥ 1（node=" + id + "）");
        }
    }
}
