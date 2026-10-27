package com.carbon.framework.dag.dsl;

import com.carbon.framework.dag.dsl.node.NodeDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link DagDsl} 的不可变索引视图：节点表 + 正反向邻接表。
 *
 * <p>校验器和编译器都靠它，避免各写一遍邻接表逻辑；重复 id 不在这里抛异常，
 * 而是收集到 {@link #duplicateIds()} 交给校验器统一报错——保存时用户要的是一条清单，
 * 不是一个异常栈。
 *
 * @author carbon
 */
public final class DagGraph {

    private final DagDsl dsl;
    private final Map<String, NodeDef> nodes = new LinkedHashMap<>();
    private final List<String> duplicateIds = new ArrayList<>();
    private final Map<String, List<EdgeDef>> outgoing = new LinkedHashMap<>();
    private final Map<String, List<EdgeDef>> incoming = new LinkedHashMap<>();

    DagGraph(DagDsl dsl) {
        this.dsl = dsl;
        for (NodeDef node : dsl.nodes()) {
            if (nodes.putIfAbsent(node.id(), node) != null) {
                duplicateIds.add(node.id());
            }
            outgoing.putIfAbsent(node.id(), new ArrayList<>());
            incoming.putIfAbsent(node.id(), new ArrayList<>());
        }
        for (EdgeDef edge : dsl.edges()) {
            outgoing.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(edge.target(), k -> new ArrayList<>()).add(edge);
        }
    }

    /** @return 原始 DSL */
    public DagDsl dsl() {
        return dsl;
    }

    /** @return 去重后的节点表（保持声明顺序，不可变） */
    public Map<String, NodeDef> nodes() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
    }

    /** @return 节点 id 列表（保持声明顺序） */
    public Set<String> ids() {
        return new LinkedHashSet<>(nodes.keySet());
    }

    /** @return 重复声明的节点 id */
    public List<String> duplicateIds() {
        return List.copyOf(duplicateIds);
    }

    /** @param id 节点 id @return 节点，不存在返回 null */
    public NodeDef node(String id) {
        return nodes.get(id);
    }

    /** @param id 节点 id @return 出边目标（按边声明顺序） */
    public List<String> targets(String id) {
        return outgoing.getOrDefault(id, List.of()).stream().map(EdgeDef::target).toList();
    }

    /** @param id 节点 id @return 入边来源（按边声明顺序） */
    public List<String> sources(String id) {
        return incoming.getOrDefault(id, List.of()).stream().map(EdgeDef::source).toList();
    }

    /** @param id 节点 id @return 出边条数 */
    public int outDegree(String id) {
        return outgoing.getOrDefault(id, List.of()).size();
    }

    /** @param id 节点 id @return 入边条数 */
    public int inDegree(String id) {
        return incoming.getOrDefault(id, List.of()).size();
    }

    /** @param type 节点类型 @return 该类型的节点列表 */
    public List<NodeDef> byType(NodeType type) {
        return nodes.values().stream().filter(n -> n.type() == type).toList();
    }
}
