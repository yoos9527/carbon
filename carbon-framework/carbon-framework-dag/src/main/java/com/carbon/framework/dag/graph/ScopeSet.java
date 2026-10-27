package com.carbon.framework.dag.graph;

import java.util.List;
import java.util.Map;

/**
 * 作用域划分结果。
 *
 * @param root      顶层作用域
 * @param scopes    作用域名 → 作用域（含 {@code root}）
 * @param problems  区域划分发现的问题
 * @author carbon
 */
public record ScopeSet(Scope root, Map<String, Scope> scopes, List<RegionProblem> problems) {

    public ScopeSet {
        scopes = Map.copyOf(scopes);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    /** @param label 作用域名 @return 作用域，不存在返回顶层 */
    public Scope scope(String label) {
        return scopes.getOrDefault(label, root);
    }
}
