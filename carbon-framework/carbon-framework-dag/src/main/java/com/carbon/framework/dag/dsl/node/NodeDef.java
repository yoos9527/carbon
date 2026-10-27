package com.carbon.framework.dag.dsl.node;

import com.carbon.framework.dag.dsl.NodeType;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 节点定义（密封接口）。
 *
 * <p>为什么用 {@code sealed + record}：节点类型的“全集”写进类型系统，而不是台账。
 * 好处是消费端 {@code switch} 不写 {@code default} 也能穷尽，新增节点类型时**编译期**就会
 * 在缺分支的地方报错——这是 JDK 21 在本项目里最实在的一笔收益。
 *
 * @author carbon
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StartNode.class, name = "START"),
        @JsonSubTypes.Type(value = EndNode.class, name = "END"),
        @JsonSubTypes.Type(value = CallNode.class, name = "CONNECTOR_CALL"),
        @JsonSubTypes.Type(value = CondNode.class, name = "CONDITION"),
        @JsonSubTypes.Type(value = LoopNode.class, name = "LOOP"),
        @JsonSubTypes.Type(value = ForeachNode.class, name = "FOREACH"),
        @JsonSubTypes.Type(value = ScriptNode.class, name = "SCRIPT"),
        @JsonSubTypes.Type(value = DelayNode.class, name = "DELAY")
})
public sealed interface NodeDef permits StartNode, EndNode, CallNode, CondNode,
        LoopNode, ForeachNode, ScriptNode, DelayNode {

    /** @return 节点 id（画布内唯一） */
    String id();

    /** @return 节点类型 */
    default NodeType type() {
        return switch (this) {
            case StartNode ignored -> NodeType.START;
            case EndNode ignored -> NodeType.END;
            case CallNode ignored -> NodeType.CONNECTOR_CALL;
            case CondNode ignored -> NodeType.CONDITION;
            case LoopNode ignored -> NodeType.LOOP;
            case ForeachNode ignored -> NodeType.FOREACH;
            case ScriptNode ignored -> NodeType.SCRIPT;
            case DelayNode ignored -> NodeType.DELAY;
        };
    }

    /** @return 是否为容器节点（LOOP / FOREACH 用 body..bodyEnd 圈定子图） */
    default boolean container() {
        return this instanceof LoopNode || this instanceof ForeachNode;
    }
}
