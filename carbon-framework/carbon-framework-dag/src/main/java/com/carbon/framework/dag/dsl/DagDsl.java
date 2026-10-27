package com.carbon.framework.dag.dsl;

import com.carbon.framework.dag.dsl.node.NodeDef;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * 编排定义（画布的直接产物）。
 *
 * <p>反序列化入口只允许两处：读 {@code flow_version.dsl} 的 {@code FlowLoader}、读请求体的
 * {@code FlowSaveService}。其余代码只接触 {@link NodeDef} 密封类型，不碰 {@code JsonNode}。
 *
 * @param version   DSL 版本，从 1 开始
 * @param nodes     节点列表
 * @param edges     边列表
 * @param timeoutMs 整体超时毫秒（空则用平台默认 60000）
 * @author carbon
 */
public record DagDsl(int version, List<NodeDef> nodes, List<EdgeDef> edges, Long timeoutMs) {

    /** 节点数上限。 */
    public static final int MAX_NODES = 500;

    /** 整体超时上限。 */
    public static final long MAX_TIMEOUT_MS = 600_000L;

    public DagDsl {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        version = version <= 0 ? 1 : version;
    }

    /**
     * 建索引视图。
     *
     * @return 不可变的图索引，校验与编译都基于它，避免各写一遍邻接表
     */
    @JsonIgnore
    public DagGraph index() {
        return new DagGraph(this);
    }

    /** @return 生效的整体超时毫秒 */
    @JsonIgnore
    public long effectiveTimeoutMs() {
        return timeoutMs == null || timeoutMs <= 0 ? 60_000L : Math.min(timeoutMs, MAX_TIMEOUT_MS);
    }
}
