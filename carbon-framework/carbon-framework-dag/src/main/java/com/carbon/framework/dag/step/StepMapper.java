package com.carbon.framework.dag.step;

import com.carbon.framework.dag.compiler.CompiledFlow;
import com.carbon.framework.dag.dsl.node.NodeDef;
import com.yomahub.liteflow.flow.LiteflowResponse;
import com.yomahub.liteflow.flow.entity.CmpStep;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 LiteFlow 的执行步骤翻译成平台自己的节点记录。
 *
 * <p>用 {@code getExecuteStepQueue()} 而不是 {@code getExecuteSteps()}：后者是 Map，
 * 同一节点在 LOOP/FOREACH 里执行多次只会留下最后一次，而我们要的是**每一次**。
 *
 * @author carbon
 */
public final class StepMapper {

    private StepMapper() {
    }

    /**
     * 映射执行步骤。
     *
     * @param response LiteFlow 执行结果
     * @param flow     编译产物（用于 elId → dslId 与节点名）
     * @return 步骤记录（按执行顺序）
     */
    public static List<StepRecord> from(LiteflowResponse response, CompiledFlow flow) {
        List<StepRecord> records = new ArrayList<>();
        if (response == null || response.getExecuteStepQueue() == null) {
            return records;
        }
        for (CmpStep step : response.getExecuteStepQueue()) {
            String elId = step.getNodeId();
            String dslId = flow.dslIdOf(elId);
            NodeDef node = flow.nodeDefs().get(elId);
            String name = node == null ? step.getNodeName() : node.id();
            Exception exception = step.getException();
            records.add(new StepRecord(elId, dslId, name, step.getTag(),
                    exception == null && step.isSuccess(),
                    step.getTimeSpent() == null ? 0L : step.getTimeSpent(),
                    step.getLoopIndex(),
                    exception == null ? null : exception.getMessage()));
        }
        return records;
    }
}
