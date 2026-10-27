package com.carbon.framework.dag.dsl.node;


/**
 * 遍历节点：对 {@code items} 指向的集合逐项执行子图，每轮把当前元素写入 {@code vars.<itemVar>}。
 *
 * @param id          节点 id
 * @param items       集合取值路径（如 {@code vars.orders}）
 * @param itemVar     当前元素的变量名
 * @param concurrency 并发度 1..10
 * @param body        子图入口
 * @param bodyEnd     子图收尾节点
 * @author carbon
 */
public record ForeachNode(String id, String items, String itemVar, int concurrency,
                          String body, String bodyEnd) implements NodeDef {

    /** 并发度上限。 */
    public static final int MAX_CONCURRENCY = 10;

    public ForeachNode {
        Nodes.requireId(id, "FOREACH");
        Nodes.requireText(items, "FOREACH.items");
        Nodes.requireText(body, "FOREACH.body");
        Nodes.requireText(bodyEnd, "FOREACH.bodyEnd");
        itemVar = itemVar == null || itemVar.isBlank() ? "item" : itemVar;
        concurrency = concurrency < 1 ? 1 : Math.min(concurrency, MAX_CONCURRENCY);
    }
}
