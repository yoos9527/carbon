package com.carbon.framework.dag.graph;

import com.carbon.framework.dag.dsl.DagGraph;
import com.carbon.framework.dag.dsl.EdgeDef;
import com.carbon.framework.dag.dsl.node.NodeDef;
import com.carbon.framework.dag.dsl.NodeType;
import com.carbon.framework.dag.dsl.node.ForeachNode;
import com.carbon.framework.dag.dsl.node.LoopNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把画布切成作用域：顶层一份 + 每个容器（LOOP/FOREACH）的 body 子图各一份。
 *
 * <p>校验器与编译器共用这一份划分——如果两边各写一遍「哪些节点属于哪个子图」，
 * 迟早会出现「校验说没问题、编译出来少一个节点」这种最难查的故障。
 *
 * @author carbon
 */
public final class Scopes {

    private Scopes() {
    }

    /**
     * 划分作用域。
     *
     * @param graph 图索引
     * @return 作用域集合与区域问题
     */
    public static ScopeSet build(DagGraph graph) {
        List<RegionProblem> problems = new ArrayList<>();
        Map<String, Set<String>> regions = new LinkedHashMap<>();
        for (NodeDef node : graph.nodes().values()) {
            if (!node.container()) {
                continue;
            }
            String body = bodyOf(node);
            String bodyEnd = bodyEndOf(node);
            if (graph.node(body) == null || graph.node(bodyEnd) == null) {
                List<String> missing = new ArrayList<>(2);
                if (graph.node(body) == null) {
                    missing.add(body);
                }
                if (graph.node(bodyEnd) == null) {
                    missing.add(bodyEnd);
                }
                problems.add(RegionProblem.of(RegionProblem.Kind.BODY_TARGET_MISSING, node.id(),
                        missing.toArray(String[]::new)));
                continue;
            }
            Set<String> region = walkRegion(graph, body, bodyEnd);
            regions.put(node.id(), region);
            if (!region.contains(bodyEnd)) {
                problems.add(RegionProblem.of(RegionProblem.Kind.BODY_END_UNREACHABLE, node.id(),
                        body, bodyEnd));
            }
            if (region.contains(node.id())) {
                problems.add(RegionProblem.of(RegionProblem.Kind.BODY_CONTAINS_SELF, node.id(), node.id()));
            }
            for (String member : region) {
                for (String target : graph.targets(member)) {
                    if (!region.contains(target)) {
                        problems.add(new RegionProblem(RegionProblem.Kind.BODY_LEAKS_OUT, node.id(),
                                List.of(member, target), member + " → " + target));
                    }
                }
            }
        }
        problems.addAll(checkNested(regions));

        Set<String> rootMembers = new LinkedHashSet<>(graph.ids());
        regions.values().forEach(rootMembers::removeAll);

        Map<String, Scope> scopes = new LinkedHashMap<>();
        Scope root;
        if (rootMembers.isEmpty()) {
            root = new Scope("root", graph.ids().isEmpty() ? "" : graph.ids().iterator().next(),
                    Set.of(), Set.of(), Map.of(), Map.of());
        } else {
            String rootEntry = graph.byType(NodeType.START).stream().map(NodeDef::id)
                    .filter(rootMembers::contains).findFirst().orElse(rootMembers.iterator().next());
            Set<String> rootTerminals = new LinkedHashSet<>();
            for (NodeDef end : graph.byType(NodeType.END)) {
                if (rootMembers.contains(end.id())) {
                    rootTerminals.add(end.id());
                }
            }
            root = buildScope(graph, "root", rootEntry, rootTerminals, rootMembers);
        }
        scopes.put("root", root);
        for (Map.Entry<String, Set<String>> entry : regions.entrySet()) {
            NodeDef container = graph.node(entry.getKey());
            scopes.put(entry.getKey(), buildScope(graph, entry.getKey(), bodyOf(container),
                    Set.of(bodyEndOf(container)), entry.getValue()));
        }
        return new ScopeSet(root, scopes, problems);
    }

    private static List<RegionProblem> checkNested(Map<String, Set<String>> regions) {
        List<RegionProblem> problems = new ArrayList<>();
        for (Map.Entry<String, Set<String>> outer : regions.entrySet()) {
            for (Map.Entry<String, Set<String>> inner : regions.entrySet()) {
                if (outer.getKey().equals(inner.getKey()) || !outer.getValue().contains(inner.getKey())) {
                    continue;
                }
                Set<String> overlap = new LinkedHashSet<>(inner.getValue());
                overlap.retainAll(outer.getValue());
                if (!overlap.isEmpty()) {
                    problems.add(new RegionProblem(RegionProblem.Kind.NESTED_CONFLICT, inner.getKey(),
                            List.copyOf(overlap), null));
                }
            }
        }
        return problems;
    }

    private static Scope buildScope(DagGraph graph, String label, String entry,
                                    Set<String> terminals, Set<String> members) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Map<String, List<String>> in = new LinkedHashMap<>();
        for (String member : members) {
            out.put(member, new ArrayList<>());
            in.put(member, new ArrayList<>());
        }
        for (EdgeDef edge : graph.dsl().edges()) {
            if (members.contains(edge.source()) && members.contains(edge.target())) {
                out.get(edge.source()).add(edge.target());
                in.get(edge.target()).add(edge.source());
            }
        }
        out.replaceAll((k, v) -> List.copyOf(v));
        in.replaceAll((k, v) -> List.copyOf(v));
        return new Scope(label, entry, Set.copyOf(terminals), Set.copyOf(members), out, in);
    }

    private static Set<String> walkRegion(DagGraph graph, String body, String bodyEnd) {
        Set<String> region = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(body);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!region.add(current) || current.equals(bodyEnd)) {
                continue;
            }
            for (String target : graph.targets(current)) {
                if (graph.node(target) != null) {
                    queue.add(target);
                }
            }
        }
        return region;
    }

    private static String bodyOf(NodeDef node) {
        return switch (node) {
            case LoopNode loop -> loop.body();
            case ForeachNode foreach -> foreach.body();
            default -> null;
        };
    }

    private static String bodyEndOf(NodeDef node) {
        return switch (node) {
            case LoopNode loop -> loop.bodyEnd();
            case ForeachNode foreach -> foreach.bodyEnd();
            default -> null;
        };
    }
}
