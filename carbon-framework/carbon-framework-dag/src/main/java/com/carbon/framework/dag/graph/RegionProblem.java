package com.carbon.framework.dag.graph;

import java.util.List;

/**
 * 子图区域划分时发现的结构问题（只描述事实，不负责翻译成用户文案）。
 *
 * @param kind        问题类型
 * @param containerId 相关容器节点
 * @param nodeIds     相关节点
 * @param edge        漏出容器的边（仅 {@link Kind#BODY_LEAKS_OUT} 有值）
 * @author carbon
 */
public record RegionProblem(Kind kind, String containerId, List<String> nodeIds, String edge) {

    /** 问题类型。 */
    public enum Kind {
        /** body / bodyEnd 指向不存在的节点。 */
        BODY_TARGET_MISSING,
        /** 从 body 走不到 bodyEnd。 */
        BODY_END_UNREACHABLE,
        /** 子图包含容器自身。 */
        BODY_CONTAINS_SELF,
        /** 子图有边指向容器之外。 */
        BODY_LEAKS_OUT,
        /** 嵌套子图之间互相重叠。 */
        NESTED_CONFLICT
    }

    public RegionProblem {
        nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds);
    }

    /**
     * 构造。
     *
     * @param kind        类型
     * @param containerId 容器节点
     * @param nodeIds     相关节点
     * @return 问题
     */
    public static RegionProblem of(Kind kind, String containerId, String... nodeIds) {
        return new RegionProblem(kind, containerId, List.of(nodeIds), null);
    }
}
