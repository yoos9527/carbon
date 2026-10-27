package com.carbon.modules.job.handler;

import com.carbon.modules.flow.executor.FlowRunner;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 唯一的内置 handler：触发一个已发布的流程。
 *
 * @author carbon
 */
@Component
public class FlowJobHandler implements JobHandler {

    private final FlowRunner flowRunner;

    public FlowJobHandler(FlowRunner flowRunner) {
        this.flowRunner = flowRunner;
    }

    @Override
    public String type() {
        return "FLOW";
    }

    @Override
    public void execute(Long jobId, Long flowId, Map<String, Object> params, String triggerBy) {
        // 走和手工执行同一条链路：调度触发的运行记录与手工触发只差 trigger_type/trigger_by，
        // 这样「谁跑的、跑了多久」在一个表里就能查全
        flowRunner.run(flowId, params, "job", triggerBy);
    }
}
