package com.carbon.framework.dag;

import com.carbon.framework.dag.dsl.DagDsl;
import com.carbon.framework.dag.dsl.NodeType;
import com.carbon.framework.dag.dsl.node.CallNode;
import com.carbon.framework.dag.dsl.node.CondNode;
import com.carbon.framework.dag.dsl.node.NodeDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DSL 反序列化：type 字段落到 sealed 子类型")
class DagDslJsonTest {

    @Test
    @DisplayName("分支+循环流程解析出正确的子类型")
    void parsesSealedSubtypes() {
        DagDsl dsl = DagJson.parse(DagJson.branchAndLoop());

        assertThat(dsl.nodes()).hasSize(8);
        assertThat(dsl.edges()).hasSize(7);
        assertThat(dsl.effectiveTimeoutMs()).isEqualTo(60_000L);

        List<NodeDef> nodes = dsl.nodes();
        assertThat(nodes.get(0).type()).isEqualTo(NodeType.START);
        assertThat(nodes.get(2)).isInstanceOf(CondNode.class);
        CondNode cond = (CondNode) nodes.get(2);
        assertThat(cond.whenTrue()).isEqualTo("n4");
        assertThat(cond.whenFalse()).isEqualTo("n5");

        CallNode call = (CallNode) nodes.get(3);
        assertThat(call.connector()).isEqualTo("demo");
        assertThat(call.operation()).isEqualTo("ok");
        assertThat(call.onError()).isEqualTo(com.carbon.framework.dag.dsl.OnError.STOP);
        assertThat(call.retry().enabled()).isFalse();
    }

    @Test
    @DisplayName("缺失 input/output 时回落到空 Map")
    void defaultsEmptyMaps() {
        DagDsl dsl = DagJson.parse("""
                {"nodes":[{"type":"CONNECTOR_CALL","id":"x","connector":"c","operation":"o"}],
                 "edges":[]}
                """);
        CallNode call = (CallNode) dsl.nodes().get(0);
        assertThat(call.input()).isEmpty();
        assertThat(call.output()).isEmpty();
    }

    @Test
    @DisplayName("序列化再反序列化保持等价")
    void roundTrips() {
        DagDsl original = DagJson.parse(DagJson.foreach());
        DagDsl again = DagJson.parse(DagJson.write(original));

        assertThat(again.nodes()).hasSize(original.nodes().size());
        assertThat(again.edges()).isEqualTo(original.edges());
        assertThat(again.nodes().get(1).type()).isEqualTo(NodeType.FOREACH);
    }
}
