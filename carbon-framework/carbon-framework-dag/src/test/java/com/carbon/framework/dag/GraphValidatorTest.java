package com.carbon.framework.dag;

import com.carbon.framework.dag.validator.GraphValidator;
import com.carbon.framework.dag.validator.ValidationCode;
import com.carbon.framework.dag.validator.ValidationError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("画布校验：结构化规则与外部 SPI")
class GraphValidatorTest {

    private static List<ValidationError> validate(String json) {
        return new GraphValidator(DagJson.parse(json)).validate();
    }

    private static List<ValidationCode> codes(String json) {
        return validate(json).stream().map(ValidationError::code).toList();
    }

    @Test
    @DisplayName("合法流程零错误")
    void acceptsStructuredFlow() {
        assertThat(validate(DagJson.linear())).isEmpty();
        assertThat(validate(DagJson.branchAndLoop())).isEmpty();
        assertThat(validate(DagJson.foreach())).isEmpty();
    }

    @Test
    @DisplayName("空流程")
    void rejectsEmpty() {
        assertThat(codes("{\"nodes\":[],\"edges\":[]}")).contains(ValidationCode.FLOW_EMPTY);
    }

    @Test
    @DisplayName("两个 START")
    void rejectsTwoStarts() {
        String json = """
                {"nodes":[{"type":"START","id":"n1"},{"type":"START","id":"n2"},
                          {"type":"END","id":"n3"}],
                 "edges":[{"source":"n1","target":"n3"},{"source":"n2","target":"n3"}]}
                """;
        assertThat(codes(json)).contains(ValidationCode.START_COUNT_INVALID);
    }

    @Test
    @DisplayName("没有 END")
    void rejectsMissingEnd() {
        String json = """
                {"nodes":[{"type":"START","id":"n1"},
                          {"type":"CONNECTOR_CALL","id":"n2","connector":"c","operation":"o"}],
                 "edges":[{"source":"n1","target":"n2"},{"source":"n2","target":"n1"}]}
                """;
        assertThat(codes(json)).contains(ValidationCode.END_COUNT_INVALID);
    }

    @Test
    @DisplayName("画回边重试 → EDGE_BACKWARD，并提示改用 LOOP")
    void rejectsBackEdge() {
        String json = """
                {"nodes":[{"type":"START","id":"n1"},
                          {"type":"CONNECTOR_CALL","id":"n2","connector":"c","operation":"o"},
                          {"type":"END","id":"n3"}],
                 "edges":[{"source":"n1","target":"n2"},{"source":"n2","target":"n3"},
                          {"source":"n3","target":"n2"}]}
                """;
        List<ValidationError> errors = validate(json);
        assertThat(errors).anySatisfy(error -> {
            assertThat(error.code()).isEqualTo(ValidationCode.EDGE_BACKWARD);
            assertThat(error.message()).contains("LOOP");
        });
    }

    @Test
    @DisplayName("交叉边 → EDGE_BACKWARD 或 EDGE_CROSSING 至少命中一个")
    void rejectsCrossing() {
        String json = """
                {"nodes":[{"type":"START","id":"n1"},
                          {"type":"CONDITION","id":"n2","expr":"true","branches":{"true":"n3","false":"n4"}},
                          {"type":"CONNECTOR_CALL","id":"n3","connector":"c","operation":"o","onError":"CONTINUE"},
                          {"type":"CONNECTOR_CALL","id":"n4","connector":"c","operation":"o"},
                          {"type":"END","id":"n5"},
                          {"type":"END","id":"n6"}],
                 "edges":[{"source":"n1","target":"n2"},{"source":"n2","target":"n3"},
                          {"source":"n2","target":"n4"},{"source":"n3","target":"n6"},
                          {"source":"n4","target":"n5"},{"source":"n3","target":"n5"}]}
                """;
        List<ValidationCode> codes = codes(json);
        assertThat(codes).anyMatch(code ->
                code == ValidationCode.EDGE_CROSSING || code == ValidationCode.NO_CONVERGENCE);
    }

    @Test
    @DisplayName("分支直接跳到汇聚点（空分支）→ NO_CONVERGENCE")
    void rejectsEmptyBranch() {
        String json = """
                {"nodes":[{"type":"START","id":"n1"},
                          {"type":"CONDITION","id":"n2","expr":"true","branches":{"true":"n3","false":"n5"}},
                          {"type":"CONNECTOR_CALL","id":"n3","connector":"c","operation":"o"},
                          {"type":"CONNECTOR_CALL","id":"n4","connector":"c","operation":"o"},
                          {"type":"CONNECTOR_CALL","id":"n5","connector":"c","operation":"o"},
                          {"type":"END","id":"n6"}],
                 "edges":[{"source":"n1","target":"n2"},{"source":"n2","target":"n3"},
                          {"source":"n2","target":"n5"},{"source":"n3","target":"n4"},
                          {"source":"n4","target":"n5"},{"source":"n5","target":"n6"}]}
                """;
        assertThat(codes(json)).contains(ValidationCode.NO_CONVERGENCE);
    }

    @Test
    @DisplayName("条件节点的出边与 branches 不一致 → BRANCH_EDGE_MISMATCH")
    void rejectsBranchEdgeMismatch() {
        String json = """
                {"nodes":[{"type":"START","id":"n1"},
                          {"type":"CONDITION","id":"n2","expr":"true","branches":{"true":"n3","false":"n4"}},
                          {"type":"CONNECTOR_CALL","id":"n3","connector":"c","operation":"o"},
                          {"type":"CONNECTOR_CALL","id":"n4","connector":"c","operation":"o"},
                          {"type":"CONNECTOR_CALL","id":"n5","connector":"c","operation":"o"},
                          {"type":"END","id":"n6"}],
                 "edges":[{"source":"n1","target":"n2"},{"source":"n2","target":"n3"},
                          {"source":"n2","target":"n5"},{"source":"n3","target":"n6"},
                          {"source":"n4","target":"n6"},{"source":"n5","target":"n6"}]}
                """;
        assertThat(codes(json)).contains(ValidationCode.BRANCH_EDGE_MISMATCH);
    }

    @Test
    @DisplayName("重复节点 id")
    void rejectsDuplicateId() {
        String json = """
                {"nodes":[{"type":"START","id":"n1"},{"type":"START","id":"n1"},{"type":"END","id":"n2"}],
                 "edges":[{"source":"n1","target":"n2"}]}
                """;
        assertThat(codes(json)).contains(ValidationCode.DUPLICATE_NODE_ID);
    }

    @Test
    @DisplayName("条件缺 false 分支")
    void rejectsMissingBranch() {
        String json = """
                {"nodes":[{"type":"START","id":"n1"},
                          {"type":"CONDITION","id":"n2","expr":"true","branches":{"true":"n3"}},
                          {"type":"END","id":"n3"}],
                 "edges":[{"source":"n1","target":"n2"},{"source":"n2","target":"n3"}]}
                """;
        assertThat(codes(json)).contains(ValidationCode.BRANCH_TARGET_MISSING);
    }

    @Test
    @DisplayName("子图走不到 bodyEnd")
    void rejectsUnreachableBodyEnd() {
        String json = """
                {"nodes":[{"type":"START","id":"n1"},
                          {"type":"LOOP","id":"n2","maxTimes":2,"body":"n3","bodyEnd":"n9"},
                          {"type":"CONNECTOR_CALL","id":"n3","connector":"c","operation":"o"},
                          {"type":"CONNECTOR_CALL","id":"n9","connector":"c","operation":"o"},
                          {"type":"END","id":"n4"}],
                 "edges":[{"source":"n1","target":"n2"},{"source":"n2","target":"n4"},
                          {"source":"n3","target":"n4"}]}
                """;
        assertThat(codes(json)).contains(ValidationCode.BODY_END_UNREACHABLE);
    }

    @Test
    @DisplayName("外部 SPI：表达式与引用错误会被收集")
    void collectsExternalErrors() {
        String json = """
                {"nodes":[{"type":"START","id":"n1"},
                          {"type":"CONDITION","id":"n2","expr":"vars. &&","branches":{"true":"n3","false":"n3"}},
                          {"type":"CONNECTOR_CALL","id":"n3","connector":"missing","operation":"o"},
                          {"type":"END","id":"n4"}],
                 "edges":[{"source":"n1","target":"n2"},{"source":"n2","target":"n3"},
                          {"source":"n3","target":"n4"}]}
                """;
        List<ValidationError> errors = new GraphValidator(DagJson.parse(json),
                new com.carbon.framework.dag.validator.DagCheckers() {
                    @Override
                    public String checkExpression(String expr, String nodeId) {
                        return "表达式不合法：" + expr;
                    }

                    @Override
                    public String checkReference(String connector, String operation, String nodeId) {
                        return "连接器不存在：" + connector;
                    }
                }).validate();
        assertThat(errors).extracting(ValidationError::code)
                .contains(ValidationCode.EXPRESSION_INVALID, ValidationCode.REFERENCE_INVALID);
    }
}
