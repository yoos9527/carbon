package com.carbon.framework.dag;

import com.carbon.framework.dag.cmp.BooleanNodeHandler;
import com.carbon.framework.dag.cmp.IteratorNodeHandler;
import com.carbon.framework.dag.cmp.NodeHandler;
import com.carbon.framework.dag.compiler.CompiledFlow;
import com.carbon.framework.dag.compiler.ElCompiler;
import com.carbon.framework.dag.dsl.DagDsl;
import com.carbon.framework.dag.dsl.node.NodeDef;
import com.carbon.framework.dag.registry.ChainRegistry;
import com.carbon.framework.dag.runtime.DagExecutionContext;
import com.carbon.framework.dag.step.StepMapper;
import com.carbon.framework.dag.step.StepRecord;
import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.property.LiteflowConfig;
import com.yomahub.liteflow.property.LiteflowConfigGetter;
import com.yomahub.liteflow.flow.LiteflowResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("注册进 LiteFlow 后真能跑：EL 可执行、步骤可回读")
class ChainExecutionTest {

    private static final String CHAIN_ID = "flow_demo_v1";

    private static final List<String> EXECUTED = new CopyOnWriteArrayList<>();

    private static CompiledFlow flow;

    private static LiteflowConfig CONFIG;

    @BeforeAll
    static void setUp() {
        // 非 Spring 场景：LiteFlow 要求显式塞一个 config，否则 FlowExecutor.init 抛
        // ConfigErrorException("config error, please check liteflow config property")。
        // 这里刻意保持 ruleSource 为空 —— 节点与链路全部由 ChainRegistry 编程式注册。
        CONFIG = new LiteflowConfig();
        CONFIG.setPrintBanner(false);
        CONFIG.setPrintExecutionLog(false);
        CONFIG.setSupportMultipleType(true);
        LiteflowConfigGetter.setLiteflowConfig(CONFIG);

        DagDsl dsl = DagJson.parse(DagJson.branchAndLoop());
        assertThat(new com.carbon.framework.dag.validator.GraphValidator(dsl).validate()).isEmpty();
        flow = ElCompiler.compile(dsl, "demo");
        ChainRegistry.register(flow, CHAIN_ID);
    }

    @Test
    @DisplayName("EL 语法校验通过")
    void elIsValid() {
        assertThat(ChainRegistry.validateEl(flow.el())).isNull();
    }

    @Test
    @DisplayName("执行成功并回读出每个节点的步骤")
    void executesAndMapsSteps() {
        EXECUTED.clear();
        // FlowExecutor.init() 只看自己的 liteflowConfig 字段（不看 LiteflowConfigGetter），
        // 所以必须显式 setLiteflowConfig，否则抛 ConfigErrorException。
        FlowExecutor executor = new FlowExecutor();
        executor.setLiteflowConfig(CONFIG);
        LiteflowResponse response = executor.execute2Resp(CHAIN_ID, null, TestContext.class);
        assertThat(response.isSuccess())
                .withFailMessage(() -> "执行失败: " + response.getMessage())
                .isTrue();

        // 注意：布尔节点（CONDITION/LOOP）走 BooleanNodeHandler，不走 NodeHandler，
        // 所以 EXECUTED 里没有 n3/n6 —— 这正是 §4.7 里「一张表两条执行路径」的体现。
        assertThat(EXECUTED).containsExactly("n1", "n2", "n4", "n8", "n8", "n8", "n7");

        List<StepRecord> steps = StepMapper.from(response, flow);
        // 步骤队列的真实形状（2.16.3 实测）：布尔节点会被记录（n3 一次），
        // WHILE 的条件节点每轮各记一次，含最后那次返回 false 的收口判定（n6 共 4 次）。
        assertThat(steps).extracting(StepRecord::dslId).containsExactly(
                "n1", "n2", "n3", "n4",
                "n6", "n8", "n6", "n8", "n6", "n8", "n6", "n7");
        assertThat(steps).allSatisfy(step -> assertThat(step.success()).isTrue());
        assertThat(steps).filteredOn(step -> "n6".equals(step.dslId()))
                .extracting(StepRecord::loopIndex).containsExactly(0, 1, 2, 3);
        assertThat(steps).filteredOn(step -> "n8".equals(step.dslId()))
                .extracting(StepRecord::loopIndex).containsExactly(0, 1, 2);
    }

    /**
     * 测试用上下文：LiteFlow 会用无参构造创建它，所以执行体通过静态字段注入。
     */
    public static class TestContext implements DagExecutionContext {

        private final Map<String, Object> vars = new HashMap<>();

        @Override
        public String flowRunId() {
            return "run-test";
        }

        @Override
        public NodeDef nodeDef(String elId) {
            return flow.nodeDefs().get(elId);
        }

        @Override
        public Map<String, Object> vars() {
            return vars;
        }

        @Override
        public void bindItem(String itemVar, Object item) {
            vars.put(itemVar, item);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T service(Class<T> type) {
            if (type == NodeHandler.class) {
                NodeHandler handler = (node, context) -> EXECUTED.add(node.id());
                return (T) handler;
            }
            if (type == BooleanNodeHandler.class) {
                BooleanNodeHandler handler = (node, context) -> true;
                return (T) handler;
            }
            if (type == IteratorNodeHandler.class) {
                IteratorNodeHandler handler = (node, context) -> {
                    List<String> items = new ArrayList<>(List.of("a", "b"));
                    return items.iterator();
                };
                return (T) handler;
            }
            return null;
        }
    }
}
