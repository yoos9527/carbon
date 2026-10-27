package com.carbon.framework.dag.compiler;

import com.carbon.framework.dag.dsl.DagDsl;
import com.carbon.framework.dag.dsl.DagGraph;
import com.carbon.framework.dag.dsl.node.CallNode;
import com.carbon.framework.dag.dsl.node.CondNode;
import com.carbon.framework.dag.dsl.node.DelayNode;
import com.carbon.framework.dag.dsl.node.EndNode;
import com.carbon.framework.dag.dsl.node.ForeachNode;
import com.carbon.framework.dag.dsl.node.LoopNode;
import com.carbon.framework.dag.dsl.node.NodeDef;
import com.carbon.framework.dag.dsl.node.ScriptNode;
import com.carbon.framework.dag.dsl.node.StartNode;
import com.carbon.framework.dag.graph.PostDominators;
import com.carbon.framework.dag.graph.Scope;
import com.carbon.framework.dag.graph.ScopeSet;
import com.carbon.framework.dag.graph.Scopes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把画布 DSL 编译成 LiteFlow EL 表达式。
 *
 * <p>为什么是「编译到 EL」而不是「自己写个执行器」：LiteFlow 已经把我们需要的
 * 超时、重试、WHEN 并发、步骤记录（{@code executeSteps}）都实现好了，我们的增量价值在
 * 画布与组件实现上。代价是必须把画布收窄成**结构化**形状，这个收窄由
 * {@code GraphValidator} 在保存时完成，本类只负责翻译，翻译不过就抛
 * {@link DagCompileException}（属于程序错误，不是用户错误）。
 *
 * <p>翻译规则：
 * <ul>
 *   <li>线性段 → {@code THEN(a, b, c)}，单节点直接写节点名；</li>
 *   <li>CONDITION → {@code IF(cond, THEN(...), THEN(...))}，两分支的最近汇聚点作为后续起点；</li>
 *   <li>LOOP → {@code WHILE(cond).DO(THEN(body))}，容器自身的出边作为后续；</li>
 *   <li>FOREACH → {@code ITERATOR(item).DO(THEN(body))}；</li>
 *   <li>END → 叶子节点，段到此结束。</li>
 * </ul>
 *
 * <p>节点 id 会被加前缀（{@code prefix + "_" + 原 id 净化}）：LiteFlow 的节点注册表是
 * **全局**的，两个流程都用 {@code n1} 且类型不同就会互相覆盖，所以必须隔离。
 *
 * @author carbon
 */
public final class ElCompiler {

    private final DagGraph graph;
    private final ScopeSet scopeSet;
    private final Map<String, Scope> scopeOf = new HashMap<>();
    private final Map<String, Map<String, String>> ipdomByScope = new HashMap<>();
    private final Map<String, String> elIdOf = new LinkedHashMap<>();
    private final Map<String, NodeDef> nodeDefs = new LinkedHashMap<>();
    private final String prefix;

    private ElCompiler(DagDsl dsl, String prefix) {
        this.graph = dsl.index();
        this.scopeSet = Scopes.build(graph);
        this.prefix = prefix == null || prefix.isBlank() ? "f" : prefix;
        for (Scope scope : scopeSet.scopes().values()) {
            for (String member : scope.members()) {
                scopeOf.put(member, scope);
            }
        }
        for (NodeDef node : graph.nodes().values()) {
            String elId = this.prefix + "_" + sanitize(node.id());
            elIdOf.put(node.id(), elId);
            nodeDefs.put(elId, node);
        }
    }

    /**
     * 编译。
     *
     * @param dsl    编排定义（应已通过 {@code GraphValidator}）
     * @param prefix 节点 id 前缀，建议用流程 key，保证全局唯一
     * @return 编译产物
     * @throws DagCompileException 画布不成形（校验器漏网）
     */
    public static CompiledFlow compile(DagDsl dsl, String prefix) {
        return new ElCompiler(dsl, prefix).run();
    }

    private CompiledFlow run() {
        if (graph.ids().isEmpty()) {
            throw new DagCompileException("空流程图无法编译");
        }
        Scope root = scopeSet.root();
        List<String> segments = walk(root.entry(), root, Set.of());
        return new CompiledFlow(wrap(segments), elIdOf, nodeDefs);
    }

    /**
     * 从 {@code from} 开始走一个作用域，直到 {@code stop} 命中的节点（不含）或走到叶子。
     *
     * @param from  起点节点 id
     * @param scope 作用域
     * @param stop  停止节点（不含），空表示走到底
     * @return EL 片段列表
     */
    private List<String> walk(String from, Scope scope, Set<String> stop) {
        List<String> segments = new ArrayList<>();
        Set<String> guard = new HashSet<>();
        String current = from;
        while (current != null && !stop.contains(current)) {
            if (!guard.add(current)) {
                throw new DagCompileException("节点 " + current + " 在同一段里被重复展开，画布可能存在隐藏环");
            }
            NodeDef node = graph.node(current);
            if (node == null) {
                throw new DagCompileException("节点 " + current + " 不存在");
            }
            String elId = elIdOf.get(node.id());
            switch (node) {
                case StartNode ignored -> {
                    segments.add(elId);
                    current = next(scope, node.id());
                }
                case EndNode ignored -> {
                    segments.add(elId);
                    return segments;
                }
                case CallNode ignored -> {
                    segments.add(elId);
                    current = next(scope, node.id());
                }
                case ScriptNode ignored -> {
                    segments.add(elId);
                    current = next(scope, node.id());
                }
                case DelayNode ignored -> {
                    segments.add(elId);
                    current = next(scope, node.id());
                }
                case CondNode cond -> {
                    String merge = mergeOf(scope, cond.id());
                    Set<String> branchStop = merge == null ? Set.of() : Set.of(merge);
                    List<String> whenTrue = walk(requireTarget(cond.whenTrue(), cond.id()),
                            scope, branchStop);
                    List<String> whenFalse = walk(requireTarget(cond.whenFalse(), cond.id()),
                            scope, branchStop);
                    segments.add("IF(" + elId + ", " + wrap(whenTrue) + ", " + wrap(whenFalse) + ")");
                    current = merge;
                }
                case LoopNode loop -> {
                    List<String> body = walk(requireTarget(loop.body(), loop.id()),
                            scopeSet.scope(loop.id()), Set.of());
                    segments.add("WHILE(" + elId + ").DO(" + wrap(body) + ")");
                    current = next(scope, loop.id());
                }
                case ForeachNode foreach -> {
                    List<String> body = walk(requireTarget(foreach.body(), foreach.id()),
                            scopeSet.scope(foreach.id()), Set.of());
                    segments.add("ITERATOR(" + elId + ").DO(" + wrap(body) + ")");
                    current = next(scope, foreach.id());
                }
            }
        }
        return segments;
    }

    private String mergeOf(Scope scope, String nodeId) {
        Map<String, String> ipdom = ipdomByScope.computeIfAbsent(scope.label(),
                label -> PostDominators.ipdom(scope));
        return ipdom.get(nodeId);
    }

    private String next(Scope scope, String nodeId) {
        List<String> targets = scope.outOf(nodeId);
        if (targets.isEmpty()) {
            return null;
        }
        if (targets.size() > 1) {
            throw new DagCompileException("节点 " + nodeId + " 有 " + targets.size()
                    + " 条出边，只有 CONDITION 允许多出边");
        }
        return targets.get(0);
    }

    private static String requireTarget(String target, String nodeId) {
        if (target == null || target.isBlank()) {
            throw new DagCompileException("节点 " + nodeId + " 缺少子图/分支目标");
        }
        return target;
    }

    private static String wrap(List<String> segments) {
        if (segments.isEmpty()) {
            throw new DagCompileException("出现了空的执行段：分支或子图里没有节点");
        }
        return segments.size() == 1 ? segments.get(0) : "THEN(" + String.join(", ", segments) + ")";
    }

    /**
     * 把 DSL 节点 id 净化成 LiteFlow 认得的标识符。
     *
     * @param raw 原始 id（可能是 UUID）
     * @return {@code [A-Za-z0-9_]} 组成的标识符，首字符不是字母时补 {@code n}
     */
    private static String sanitize(String raw) {
        StringBuilder builder = new StringBuilder(raw.length());
        for (char ch : raw.toCharArray()) {
            builder.append(Character.isLetterOrDigit(ch) ? ch : '_');
        }
        if (builder.isEmpty()) {
            builder.append('n');
        }
        if (!Character.isLetter(builder.charAt(0))) {
            builder.insert(0, 'n');
        }
        return builder.toString();
    }
}
