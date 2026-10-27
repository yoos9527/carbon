package com.carbon.modules.flow.handler;

import com.carbon.framework.dag.cmp.BooleanNodeHandler;
import com.carbon.framework.dag.dsl.node.CondNode;
import com.carbon.framework.dag.dsl.node.LoopNode;
import com.carbon.framework.dag.dsl.node.NodeDef;
import com.carbon.framework.dag.runtime.DagExecutionContext;
import com.carbon.framework.expression.ExpressionEvaluator;
import com.carbon.modules.flow.executor.FlowState;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 布尔节点求值：CONDITION 的分支表达式 与 LOOP 的继续条件。
 *
 * <p>两者共用一个实现，因为它们求值的是同一种东西（表达式 → 布尔），
 * 差别只在于 LOOP 多一层「次数上限」——而次数上限由 {@code LoopCmp} 兜底，
 * 表达式本身不该负责防死循环。
 *
 * @author carbon
 */
@Component
public class FlowBooleanHandler implements BooleanNodeHandler {

    private final ExpressionEvaluator evaluator;

    public FlowBooleanHandler(ExpressionEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @Override
    public boolean test(NodeDef node, DagExecutionContext ctx) {
        Map<String, Object> vars = vars(ctx);
        return switch (node) {
            case CondNode cond -> evaluator.test(cond.expr(), vars);
            case LoopNode loop -> testLoop(loop, vars);
            default -> throw new IllegalStateException(
                    "节点 " + node.id() + " 不是布尔节点，不应由 BooleanNodeHandler 求值");
        };
    }

    /**
     * LOOP 的继续条件。
     *
     * <p>{@code continueWhen} 缺省视为「一直继续」，由 {@code LoopCmp} 的 {@code maxTimes} 收口——
     * 这样画布上只填次数不填条件也能用，而不会因为没写表达式就一次都不循环。
     */
    private boolean testLoop(LoopNode loop, Map<String, Object> vars) {
        if (loop.continueWhen() == null || loop.continueWhen().isBlank()) {
            return true;
        }
        return evaluator.test(loop.continueWhen(), vars);
    }

    private Map<String, Object> vars(DagExecutionContext ctx) {
        return FlowState.evalVarsOf(ctx);
    }
}
