package com.carbon.framework.dag.graph;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作用域内的「最近公共汇聚点」（immediate post-dominator）计算。
 *
 * <p>这是「分支必须汇聚」这条结构化规则的可执行形式：把画布收窄成 LiteFlow 能编译的
 * 结构化树形图，靠的不是看图形像不像树，而是**每个多入边节点的每条前驱路径都必须以它为
 * 最近汇聚点**。图形上看起来像菱形、实际有一条路绕过去的画布，就在这里被挡住。
 *
 * <p>实现：在反向图上跑迭代式支配点算法（DAG、节点 ≤ 500，够用），多出口用虚拟 EXIT 收敛。
 *
 * @author carbon
 */
public final class PostDominators {

    private PostDominators() {
    }

    /**
     * 计算作用域内每个节点的最近公共汇聚点。
     *
     * @param scope 作用域
     * @return 节点 id → 汇聚点 id（无汇聚点则不含该键）
     */
    public static Map<String, String> ipdom(Scope scope) {
        List<String> order = new ArrayList<>(scope.members());
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            index.put(order.get(i), i);
        }
        int size = order.size();
        int exit = size;

        BitSet universe = new BitSet(size + 1);
        universe.set(0, size + 1);

        BitSet[] dom = new BitSet[size + 1];
        for (int i = 0; i <= size; i++) {
            dom[i] = (BitSet) universe.clone();
            dom[i].clear(i);
            dom[i].set(i);
        }
        dom[exit] = new BitSet(size + 1);
        dom[exit].set(exit);

        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < size + 2) {
            changed = false;
            for (int i = 0; i < size; i++) {
                String id = order.get(i);
                List<String> successors = scope.terminals().contains(id)
                        ? List.of() : scope.outOf(id);
                BitSet next = new BitSet(size + 1);
                next.set(i);
                if (successors.isEmpty()) {
                    next.set(exit);
                } else {
                    BitSet intersection = null;
                    for (String successor : successors) {
                        Integer successorIndex = index.get(successor);
                        int target = successorIndex == null ? exit : successorIndex;
                        if (intersection == null) {
                            intersection = (BitSet) dom[target].clone();
                        } else {
                            intersection.and(dom[target]);
                        }
                    }
                    next.or(intersection);
                }
                if (!next.equals(dom[i])) {
                    dom[i] = next;
                    changed = true;
                }
            }
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            String best = null;
            int bestCardinality = -1;
            for (int candidate = dom[i].nextSetBit(0); candidate >= 0;
                 candidate = dom[i].nextSetBit(candidate + 1)) {
                if (candidate == i || candidate == exit) {
                    continue;
                }
                int cardinality = dom[candidate].cardinality();
                if (cardinality > bestCardinality) {
                    bestCardinality = cardinality;
                    best = order.get(candidate);
                }
            }
            if (best != null) {
                result.put(order.get(i), best);
            }
        }
        return result;
    }
}
