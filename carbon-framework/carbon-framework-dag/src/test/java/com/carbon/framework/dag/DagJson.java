package com.carbon.framework.dag;

import com.carbon.framework.dag.dsl.DagDsl;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 测试用的 DSL 构造工具：手写 JSON 而不是用 Builder，原因是**反序列化路径本身就是要测的东西**。
 *
 * @author carbon
 */
public final class DagJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DagJson() {
    }

    /**
     * @param json DSL JSON
     * @return 解析结果
     */
    public static DagDsl parse(String json) {
        try {
            return MAPPER.readValue(json, DagDsl.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("测试 JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * @param dsl DSL
     * @return JSON
     */
    public static String write(DagDsl dsl) {
        try {
            return MAPPER.writeValueAsString(dsl);
        } catch (Exception e) {
            throw new IllegalArgumentException("序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 线性流程：START → CALL → END。
     *
     * @return DSL JSON
     */
    public static String linear() {
        return """
                {"version":1,"timeoutMs":30000,
                 "nodes":[
                   {"type":"START","id":"n1"},
                   {"type":"CONNECTOR_CALL","id":"n2","connector":"demo","operation":"query",
                    "input":{"q":"{{input.q}}"},"output":{"code":"$.code"}},
                   {"type":"END","id":"n3"}
                 ],
                 "edges":[{"source":"n1","target":"n2"},{"source":"n2","target":"n3"}]}
                """;
    }

    /**
     * 含条件汇聚 + 循环子图的流程：n1 → n2 → n3(条件) → {n4|n5} → n6(循环) → n7。
     *
     * @return DSL JSON
     */
    public static String branchAndLoop() {
        return """
                {"version":1,"timeoutMs":60000,
                 "nodes":[
                   {"type":"START","id":"n1"},
                   {"type":"CONNECTOR_CALL","id":"n2","connector":"demo","operation":"query",
                    "input":{},"output":{}},
                   {"type":"CONDITION","id":"n3","expr":"vars.code == 200",
                    "branches":{"true":"n4","false":"n5"}},
                   {"type":"CONNECTOR_CALL","id":"n4","connector":"demo","operation":"ok",
                    "input":{},"output":{}},
                   {"type":"CONNECTOR_CALL","id":"n5","connector":"demo","operation":"fail",
                    "input":{},"output":{}},
                   {"type":"LOOP","id":"n6","maxTimes":3,"continueWhen":"vars.retry",
                    "body":"n8","bodyEnd":"n8"},
                   {"type":"END","id":"n7"},
                   {"type":"CONNECTOR_CALL","id":"n8","connector":"demo","operation":"retry",
                    "input":{},"output":{}}
                 ],
                 "edges":[{"source":"n1","target":"n2"},{"source":"n2","target":"n3"},
                   {"source":"n3","target":"n4"},{"source":"n3","target":"n5"},
                   {"source":"n4","target":"n6"},{"source":"n5","target":"n6"},
                   {"source":"n6","target":"n7"}]}
                """;
    }

    /**
     * 遍历流程：n1 → n2(FOREACH body=n3) → n4。
     *
     * @return DSL JSON
     */
    public static String foreach() {
        return """
                {"version":1,
                 "nodes":[
                   {"type":"START","id":"n1"},
                   {"type":"FOREACH","id":"n2","items":"vars.orders","itemVar":"order",
                    "concurrency":2,"body":"n3","bodyEnd":"n3"},
                   {"type":"CONNECTOR_CALL","id":"n3","connector":"demo","operation":"push",
                    "input":{"id":"{{vars.order.id}}"},"output":{}},
                   {"type":"END","id":"n4"}
                 ],
                 "edges":[{"source":"n1","target":"n2"},{"source":"n2","target":"n4"}]}
                """;
    }
}
