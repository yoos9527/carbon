package com.carbon.framework.dag.validator;

import com.carbon.framework.dag.dsl.DagDsl;
import com.carbon.framework.dag.dsl.DagGraph;
import com.carbon.framework.dag.dsl.EdgeDef;
import com.carbon.framework.dag.dsl.NodeType;
import com.carbon.framework.dag.dsl.node.NodeDef;
import com.carbon.framework.dag.dsl.node.CallNode;
import com.carbon.framework.dag.dsl.node.CondNode;
import com.carbon.framework.dag.dsl.node.DelayNode;
import com.carbon.framework.dag.dsl.node.EndNode;
import com.carbon.framework.dag.dsl.node.ForeachNode;
import com.carbon.framework.dag.dsl.node.LoopNode;
import com.carbon.framework.dag.dsl.node.ScriptNode;
import com.carbon.framework.dag.dsl.node.StartNode;
import com.carbon.framework.dag.graph.PostDominators;
import com.carbon.framework.dag.graph.RegionProblem;
import com.carbon.framework.dag.graph.Scope;
import com.carbon.framework.dag.graph.ScopeSet;
import com.carbon.framework.dag.graph.Scopes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 画布校验器：保存前把「画得出来但编译不成结构化流程」的画布挡在门外。
 *
 * <p>校验分两块：
 * <ol>
 *   <li><b>结构校验</b>（本类自己干）：入口/出口数量、出入边、可达性、回边、交叉边、分支汇聚；</li>
 *   <li><b>外部校验</b>（{@link DagCheckers} 注入）：表达式能否解析、脚本能否编译、connector 是否存在。</li>
 * </ol>
 *
 * <p>所有错误一次性返回全量清单，不做「报一条改一条」——用户保存一次就该看到所有问题。
 *
 * @author carbon
 */
public final class GraphValidator {

    private final DagGraph graph;
    private final DagCheckers checkers;
    private final List<ValidationError> errors = new ArrayList<>();

    /**
     * 只做结构校验。
     *
     * @param dsl 编排定义
     */
    public GraphValidator(DagDsl dsl) {
        this(dsl, DagCheckers.none());
    }

    /**
     * 构造。
     *
     * @param dsl      编排定义
     * @param checkers 外部校验 SPI，null 视为不校验
     */
    public GraphValidator(DagDsl dsl, DagCheckers checkers) {
        this.graph = Objects.requireNonNull(dsl, "dsl 不能为空").index();
        this.checkers = checkers == null ? DagCheckers.none() : checkers;
    }

    /**
     * 执行校验。
     *
     * @return 错误清单（空 = 通过）
     */
    public List<ValidationError> validate() {
        errors.clear();
        checkBasics();
        if (!graph.ids().isEmpty()) {
            buildScopes().scopes().values().forEach(this::checkScope);
        }
        checkNodes();
        return List.copyOf(errors);
    }

    /**
     * @return 是否通过
     */
    public boolean valid() {
        return validate().isEmpty();
    }

    // ------------------------------------------------------------------ 基础

    private void checkBasics() {
        if (graph.ids().isEmpty()) {
            add(ValidationError.of(ValidationCode.FLOW_EMPTY,
                    "流程里没有任何节点，至少需要一个 START 和一个 END"));
            return;
        }
        if (graph.ids().size() > DagDsl.MAX_NODES) {
            add(ValidationError.of(ValidationCode.NODE_COUNT_EXCEEDED,
                    "节点数 " + graph.ids().size() + " 超过上限 " + DagDsl.MAX_NODES + "，请拆成多个流程"));
        }
        Long timeout = graph.dsl().timeoutMs();
        if (timeout != null && timeout > DagDsl.MAX_TIMEOUT_MS) {
            add(ValidationError.of(ValidationCode.TIMEOUT_EXCEEDED,
                    "整体超时 " + timeout + "ms 超过上限 " + DagDsl.MAX_TIMEOUT_MS + "ms"));
        }
        for (String duplicate : graph.duplicateIds()) {
            add(ValidationError.of(ValidationCode.DUPLICATE_NODE_ID,
                    "节点 id「" + duplicate + "」重复，画布上每个节点必须唯一", duplicate));
        }

        List<NodeDef> starts = graph.byType(NodeType.START);
        if (starts.size() != 1) {
            add(ValidationError.of(ValidationCode.START_COUNT_INVALID,
                    "必须有且仅有 1 个 START 节点，当前有 " + starts.size() + " 个"));
        }
        List<NodeDef> ends = graph.byType(NodeType.END);
        if (ends.isEmpty()) {
            add(ValidationError.of(ValidationCode.END_COUNT_INVALID, "至少需要 1 个 END 节点作为流程出口"));
        }

        for (EdgeDef edge : graph.dsl().edges()) {
            List<String> missing = new ArrayList<>(2);
            if (graph.node(edge.source()) == null) {
                missing.add(edge.source());
            }
            if (graph.node(edge.target()) == null) {
                missing.add(edge.target());
            }
            if (!missing.isEmpty()) {
                add(ValidationError.of(ValidationCode.EDGE_ENDPOINT_MISSING,
                        "边 " + edge + " 指向了不存在的节点 " + String.join("、", missing),
                        missing.toArray(String[]::new)));
            }
        }
    }

    // ------------------------------------------------------------------ 作用域

    /**
     * 划分作用域并把区域问题翻译成面向用户的错误。
     *
     * @return 作用域集合（顶层 + 各容器子图）
     */
    private ScopeSet buildScopes() {
        ScopeSet scopeSet = Scopes.build(graph);
        for (RegionProblem problem : scopeSet.problems()) {
            add(toError(problem));
        }
        return scopeSet;
    }

    private ValidationError toError(RegionProblem problem) {
        String container = problem.containerId();
        return switch (problem.kind()) {
            case BODY_TARGET_MISSING -> ValidationError.of(ValidationCode.BODY_TARGET_MISSING,
                    "「" + container + "」的子图入口/收尾节点不存在："
                            + String.join("、", problem.nodeIds()), concat(container, problem.nodeIds()));
            case BODY_END_UNREACHABLE -> ValidationError.of(ValidationCode.BODY_END_UNREACHABLE,
                    "「" + container + "」的子图从入口走不到收尾节点 " + problem.nodeIds().get(1),
                    problem.nodeIds());
            case BODY_CONTAINS_SELF -> ValidationError.of(ValidationCode.BODY_CONTAINS_SELF,
                    "「" + container + "」的子图包含它自己，会构成递归", container);
            case BODY_LEAKS_OUT -> ValidationError.of(ValidationCode.BODY_LEAKS_OUT,
                    "子图内部的边 " + problem.edge() + " 漏到了「" + container
                            + "」之外；子图的收尾用 bodyEnd 声明，出边由容器节点自己连",
                    problem.nodeIds());
            case NESTED_CONFLICT -> ValidationError.of(ValidationCode.NESTED_SCOPE_CONFLICT,
                    "「" + container + "」的子图与另一个子图重叠：" + String.join("、", problem.nodeIds()),
                    problem.nodeIds());
        };
    }

    // ------------------------------------------------------------------ 单作用域

    private void checkScope(Scope scope) {
        if (!scope.members().contains(scope.entry())) {
            add(ValidationError.of(ValidationCode.MISSING_IN_EDGE,
                    "作用域 " + scope.label() + " 的入口节点 " + scope.entry() + " 不在图中"));
            return;
        }
        checkCycle(scope);
        Set<String> reached = reachable(scope, scope.entry());
        for (String member : scope.members()) {
            if (!reached.contains(member)) {
                add(ValidationError.of(ValidationCode.ORPHAN_NODE,
                        "节点「" + member + "」从入口 " + scope.entry() + " 走不到，它不会被执行", member));
            }
        }
        checkDegrees(scope);
        checkBranches(scope);
        checkCrossing(scope);
        checkConvergence(scope);
    }

    private void checkCycle(Scope scope) {
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (String member : scope.members()) {
            inDegree.put(member, scope.inOf(member).size());
        }
        Deque<String> queue = new ArrayDeque<>();
        inDegree.forEach((id, degree) -> {
            if (degree == 0) {
                queue.add(id);
            }
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String current = queue.poll();
            visited++;
            for (String target : scope.outOf(current)) {
                if (inDegree.computeIfPresent(target, (k, v) -> v - 1) != null && inDegree.get(target) == 0) {
                    queue.add(target);
                }
            }
        }
        if (visited < scope.members().size()) {
            List<String> cycleNodes = inDegree.entrySet().stream()
                    .filter(e -> e.getValue() > 0).map(Map.Entry::getKey).toList();
            add(ValidationError.of(ValidationCode.EDGE_BACKWARD,
                    "存在回边（环）：" + String.join("、", cycleNodes)
                            + " 之间首尾相连。需要重复执行请用 LOOP / FOREACH 节点圈定子图，不要画回边",
                    cycleNodes.toArray(String[]::new)));
        }
    }

    private void checkDegrees(Scope scope) {
        for (String member : scope.members()) {
            NodeDef node = graph.node(member);
            boolean entry = member.equals(scope.entry());
            boolean terminal = scope.terminals().contains(member);
            if (!entry && scope.inOf(member).isEmpty()) {
                add(ValidationError.of(ValidationCode.MISSING_IN_EDGE,
                        "节点「" + member + "」没有入边，不会被执行", member));
            }
            if (terminal) {
                if (!scope.outOf(member).isEmpty()) {
                    add(ValidationError.of(ValidationCode.TERMINAL_HAS_OUT_EDGE,
                            "出口节点「" + member + "」不能再连出边", member));
                }
                continue;
            }
            int outDegree = scope.outOf(member).size();
            if (outDegree == 0) {
                add(ValidationError.of(ValidationCode.MISSING_OUT_EDGE,
                        "节点「" + member + "」没有出边，流程会在这里断掉", member));
                continue;
            }
            int expected = node.type() == NodeType.CONDITION ? 2 : 1;
            if (outDegree != expected) {
                add(ValidationError.of(ValidationCode.OUT_DEGREE_INVALID,
                        "「" + member + "」(" + node.type() + ") 应有 " + expected + " 条出边，实际 " + outDegree + " 条",
                        member));
            }
        }
    }

    private void checkBranches(Scope scope) {
        for (String member : scope.members()) {
            if (!(graph.node(member) instanceof CondNode cond)) {
                continue;
            }
            Set<String> edgeTargets = new LinkedHashSet<>(scope.outOf(member));
            Set<String> branchTargets = new LinkedHashSet<>();
            for (String key : List.of(CondNode.TRUE, CondNode.FALSE)) {
                String target = cond.branches().get(key);
                if (target != null && !target.isBlank()) {
                    branchTargets.add(target);
                }
            }
            if (!edgeTargets.isEmpty() && !edgeTargets.equals(branchTargets)) {
                add(ValidationError.of(ValidationCode.BRANCH_EDGE_MISMATCH,
                        "条件节点「" + member + "」的出边 " + edgeTargets + " 与 branches "
                                + branchTargets + " 不一致；编译以 branches 为准，出边错开会导致"
                                + "交叉边/汇聚点判断失真",
                        member));
            }
            for (String key : List.of(CondNode.TRUE, CondNode.FALSE)) {
                String target = cond.branches().get(key);
                if (target == null || target.isBlank()) {
                    add(ValidationError.of(ValidationCode.BRANCH_TARGET_MISSING,
                            "条件节点「" + member + "」缺少 " + key + " 分支的目标节点", member));
                } else if (!scope.members().contains(target)) {
                    add(ValidationError.of(ValidationCode.BRANCH_TARGET_MISSING,
                            "条件节点「" + member + "」的 " + key + " 分支指向 " + target + "，但它不在本层子图内",
                            member, target));
                }
            }
        }
    }

    private void checkCrossing(Scope scope) {
        Map<String, Set<String>> reach = new LinkedHashMap<>();
        for (String member : scope.members()) {
            reach.put(member, reachable(scope, member));
        }
        List<EdgeDef> edges = new ArrayList<>();
        for (String member : scope.members()) {
            for (String target : scope.outOf(member)) {
                edges.add(new EdgeDef(member, target));
            }
        }
        for (int i = 0; i < edges.size(); i++) {
            for (int j = i + 1; j < edges.size(); j++) {
                EdgeDef first = edges.get(i);
                EdgeDef second = edges.get(j);
                if (first.source().equals(second.source()) || first.target().equals(second.target())) {
                    continue;
                }
                boolean sourceOrder = reach.get(first.source()).contains(second.source());
                boolean swappedTargets = reach.get(second.target()).contains(first.target());
                boolean sequential = reach.get(first.target()).contains(second.source());
                if (sourceOrder && swappedTargets && !sequential) {
                    add(ValidationError.of(ValidationCode.EDGE_CROSSING,
                            first + " 与 " + second + " 交叉了，两条分支要汇合到同一个节点",
                            first.source(), first.target(), second.source(), second.target()));
                    return;
                }
            }
        }
    }

    private void checkConvergence(Scope scope) {
        Map<String, String> ipdom = PostDominators.ipdom(scope);
        for (String member : scope.members()) {
            if (!(graph.node(member) instanceof CondNode cond)) {
                continue;
            }
            String merge = ipdom.get(member);
            if (merge == null) {
                continue;
            }
            for (String branch : List.of(String.valueOf(cond.whenTrue()),
                    String.valueOf(cond.whenFalse()))) {
                if (merge.equals(branch)) {
                    add(ValidationError.of(ValidationCode.NO_CONVERGENCE,
                            "条件节点「" + member + "」的某个分支直接跳到了汇聚点「" + merge
                                    + "」，等于一条空分支；请给这条分支至少一个节点，或把汇聚点往后挪",
                            member, merge));
                }
            }
        }
        for (String member : scope.members()) {
            List<String> sources = scope.inOf(member);
            if (sources.size() < 2) {
                continue;
            }
            for (String source : sources) {
                String nearest = ipdom.get(source);
                if (!member.equals(nearest)) {
                    add(ValidationError.of(ValidationCode.NO_CONVERGENCE,
                            "「" + source + "」的最近汇聚点是「" + (nearest == null ? "无" : nearest)
                                    + "」而不是「" + member + "」：两条分支没有汇到同一个节点，无法编译为结构化流程",
                            source, member));
                }
            }
        }
    }

    private Set<String> reachable(Scope scope, String from) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(from);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            for (String target : scope.outOf(current)) {
                if (!visited.contains(target)) {
                    queue.add(target);
                }
            }
        }
        return visited;
    }

    // ------------------------------------------------------------------ 节点级

    private void checkNodes() {
        for (NodeDef node : graph.nodes().values()) {
            switch (node) {
                case CallNode call -> {
                    String error = checkers.checkReference(call.connector(), call.operation(), call.id());
                    if (error != null) {
                        add(ValidationError.of(ValidationCode.REFERENCE_INVALID, error, call.id()));
                    }
                }
                case CondNode cond -> addExpressionError(
                        checkers.checkExpression(cond.expr(), cond.id()), cond.id());
                case LoopNode loop -> {
                    if (loop.continueWhen() != null && !loop.continueWhen().isBlank()) {
                        addExpressionError(
                                checkers.checkExpression(loop.continueWhen(), loop.id()), loop.id());
                    }
                }
                case ScriptNode script -> {
                    String error = checkers.checkScript(script.script(), script.lang(),
                            script.timeoutMs(), script.id());
                    if (error != null) {
                        add(ValidationError.of(ValidationCode.SCRIPT_INVALID, error, script.id()));
                    }
                }
                case StartNode ignored -> {
                }
                case EndNode ignored -> {
                }
                case ForeachNode ignored -> {
                }
                case DelayNode ignored -> {
                }
            }
        }
    }

    private void addExpressionError(String error, String nodeId) {
        if (error != null) {
            add(ValidationError.of(ValidationCode.EXPRESSION_INVALID, error, nodeId));
        }
    }

    // ------------------------------------------------------------------ 工具

    private void add(ValidationError error) {
        errors.add(error);
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

    private static List<String> concat(String head, List<String> tail) {
        List<String> all = new ArrayList<>(tail.size() + 1);
        all.add(head);
        all.addAll(tail);
        return all;
    }
}
