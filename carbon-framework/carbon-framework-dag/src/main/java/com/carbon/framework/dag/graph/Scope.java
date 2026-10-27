package com.carbon.framework.dag.graph;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个校验/编译作用域。
 *
 * <p>顶层作用域（入口 = START、出口 = 各 END）与每个容器（LOOP/FOREACH）的 body 子图
 * 各自是一个作用域——子图不是靠边接入主图的，只靠容器上的 {@code body}/{@code bodyEnd} 声明，
 * 所以「入边/出边/可达性/汇聚点」这些规则必须分作用域算，否则合法画布会被误判。
 *
 * @param label     作用域名（顶层为 {@code "root"}，子图为容器节点 id）
 * @param entry     作用域入口节点
 * @param terminals 作用域出口节点
 * @param members   作用域内节点
 * @param out       作用域内的出边邻接表
 * @param in        作用域内的入边邻接表
 * @author carbon
 */
public record Scope(String label, String entry, Set<String> terminals, Set<String> members,
                    Map<String, List<String>> out, Map<String, List<String>> in) {

    /** @param id 节点 id @return 作用域内出边目标 */
    public List<String> outOf(String id) {
        return out.getOrDefault(id, List.of());
    }

    /** @param id 节点 id @return 作用域内入边来源 */
    public List<String> inOf(String id) {
        return in.getOrDefault(id, List.of());
    }

    /** @param id 节点 id @return 是否为容器子图作用域 */
    public boolean nested() {
        return !"root".equals(label);
    }
}
