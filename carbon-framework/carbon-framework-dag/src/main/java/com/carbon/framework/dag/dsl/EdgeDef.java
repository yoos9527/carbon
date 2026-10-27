package com.carbon.framework.dag.dsl;

/**
 * 有向边。
 *
 * <p>结构化约束（无回边、无交叉边、分支必须汇聚）由 {@code GraphValidator} 在保存时校验，
 * 这里只负责承载数据。
 *
 * @param source 起点节点 id
 * @param target 终点节点 id
 * @author carbon
 */
public record EdgeDef(String source, String target) {

    public EdgeDef {
        if (source == null || source.isBlank() || target == null || target.isBlank()) {
            throw new IllegalArgumentException("边缺少 source 或 target");
        }
    }

    @Override
    public String toString() {
        return source + " → " + target;
    }
}
