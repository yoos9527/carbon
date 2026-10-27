package com.carbon.framework.dag;

import com.carbon.framework.dag.compiler.CompiledFlow;
import com.carbon.framework.dag.compiler.ElCompiler;
import com.carbon.framework.dag.compiler.DagCompileException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DSL → LiteFlow EL 编译")
class ElCompilerTest {

    @Test
    @DisplayName("线性流程编译成 THEN(...)")
    void compilesLinear() {
        CompiledFlow flow = ElCompiler.compile(DagJson.parse(DagJson.linear()), "f");
        assertThat(flow.el()).isEqualTo("THEN(f_n1, f_n2, f_n3)");
        assertThat(flow.elIdOf()).containsEntry("n2", "f_n2");
        assertThat(flow.dslIdOf("f_n2")).isEqualTo("n2");
    }

    @Test
    @DisplayName("条件编译成 IF，后续从汇聚点继续")
    void compilesCondition() {
        CompiledFlow flow = ElCompiler.compile(DagJson.parse(DagJson.branchAndLoop()), "f");
        assertThat(flow.el()).isEqualTo(
                "THEN(f_n1, f_n2, IF(f_n3, f_n4, f_n5), WHILE(f_n6).DO(f_n8), f_n7)");
    }

    @Test
    @DisplayName("遍历编译成 ITERATOR(...).DO(...)")
    void compilesForeach() {
        CompiledFlow flow = ElCompiler.compile(DagJson.parse(DagJson.foreach()), "f");
        assertThat(flow.el()).isEqualTo("THEN(f_n1, ITERATOR(f_n2).DO(f_n3), f_n4)");
    }

    @Test
    @DisplayName("节点 id 会被净化：连字符变下划线、数字开头补 n")
    void sanitizesNodeIds() {
        String json = """
                {"nodes":[{"type":"START","id":"a-1"},{"type":"END","id":"2b"}],
                 "edges":[{"source":"a-1","target":"2b"}]}
                """;
        CompiledFlow flow = ElCompiler.compile(DagJson.parse(json), "flow_1");
        assertThat(flow.el()).isEqualTo("THEN(flow_1_a_1, flow_1_n2b)");
        assertThat(flow.elIdOf()).containsEntry("a-1", "flow_1_a_1");
    }

    @Test
    @DisplayName("空分支编译直接失败（校验器本该先挡住）")
    void rejectsEmptyBranch() {
        String json = """
                {"nodes":[{"type":"START","id":"n1"},
                          {"type":"CONDITION","id":"n2","expr":"true","branches":{"true":"n3","false":"n4"}},
                          {"type":"CONNECTOR_CALL","id":"n3","connector":"c","operation":"o"},
                          {"type":"CONNECTOR_CALL","id":"n4","connector":"c","operation":"o"},
                          {"type":"END","id":"n5"}],
                 "edges":[{"source":"n1","target":"n2"},{"source":"n2","target":"n3"},
                          {"source":"n2","target":"n4"},{"source":"n3","target":"n5"},
                          {"source":"n4","target":"n5"}]}
                """;
        assertThat(ElCompiler.compile(DagJson.parse(json), "f").el())
                .isEqualTo("THEN(f_n1, IF(f_n2, f_n3, f_n4), f_n5)");
        assertThatThrownBy(() -> ElCompiler.compile(DagJson.parse("{\"nodes\":[],\"edges\":[]}"), "f"))
                .isInstanceOf(DagCompileException.class);
    }
}
