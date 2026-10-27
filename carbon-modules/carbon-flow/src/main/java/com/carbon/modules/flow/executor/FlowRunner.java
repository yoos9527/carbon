package com.carbon.modules.flow.executor;

import com.carbon.common.core.exception.ServiceException;
import com.carbon.framework.dag.compiler.CompiledFlow;
import com.carbon.framework.dag.compiler.ElCompiler;
import com.carbon.framework.dag.dsl.DagDsl;
import com.carbon.framework.dag.dsl.node.CallNode;
import com.carbon.framework.dag.dsl.node.NodeDef;
import com.carbon.framework.dag.registry.ChainRegistry;
import com.carbon.framework.dag.step.StepMapper;
import com.carbon.framework.dag.step.StepRecord;
import com.carbon.modules.flow.domain.entity.Flow;
import com.carbon.modules.flow.domain.entity.FlowRun;
import com.carbon.modules.flow.domain.entity.FlowStepRun;
import com.carbon.modules.flow.domain.entity.FlowVersion;
import com.carbon.modules.flow.mapper.FlowMapper;
import com.carbon.modules.flow.mapper.FlowRunMapper;
import com.carbon.modules.flow.mapper.FlowStepRunMapper;
import com.carbon.modules.flow.mapper.FlowVersionMapper;
import com.carbon.modules.flow.support.FlowDsls;
import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.flow.LiteflowResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 流程执行器：把「已发布版本」跑起来，并把每一步落库。
 *
 * <p>执行的永远是版本快照，不是草稿——这是编排系统最容易出错的地方：
 * 用户在画布上改一版还没保存/发布，正在跑的实例不能跟着变。
 *
 * @author carbon
 */
@Service
public class FlowRunner {

    private static final Logger log = LoggerFactory.getLogger(FlowRunner.class);

    private final FlowMapper flowMapper;
    private final FlowVersionMapper versionMapper;
    private final FlowRunMapper runMapper;
    private final FlowStepRunMapper stepMapper;
    private final FlowExecutor liteFlowExecutor;
    private final FlowNodeHandlerRefs handlers;

    public FlowRunner(FlowMapper flowMapper, FlowVersionMapper versionMapper,
                      FlowRunMapper runMapper, FlowStepRunMapper stepMapper,
                      FlowExecutor liteFlowExecutor, FlowNodeHandlerRefs handlers) {
        this.flowMapper = flowMapper;
        this.versionMapper = versionMapper;
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.liteFlowExecutor = liteFlowExecutor;
        this.handlers = handlers;
    }

    /**
     * 执行一个已发布的流程。
     *
     * @param flowId      流程 id
     * @param input       调用方入参，作为 {@code vars} 的初始内容
     * @param triggerType manual|cron|gw|webhook
     * @param triggerBy   触发者标识
     * @return 本次运行记录
     */
    public FlowRunResult run(Long flowId, Map<String, Object> input, String triggerType, String triggerBy) {
        Flow flow = flowMapper.selectById(flowId);
        if (flow == null || flow.getDeleted() != null && flow.getDeleted() == 1) {
            throw new ServiceException("流程不存在：" + flowId);
        }
        if (flow.getPublishedVersion() == null) {
            throw new ServiceException("流程未发布，不能执行：" + flow.getCode());
        }
        FlowVersion version = versionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<FlowVersion>()
                        .eq("flow_id", flowId)
                        .eq("version", flow.getPublishedVersion()));
        if (version == null) {
            throw new ServiceException("流程版本快照缺失：flow=" + flow.getCode()
                    + " v" + flow.getPublishedVersion());
        }

        DagDsl dsl = FlowDsls.parse(version.getDsl());
        String chainId = chainIdOf(flow.getCode(), version.getVersion());
        CompiledFlow compiled = ElCompiler.compile(dsl, chainId);
        // 幂等注册：进程重启后第一次执行会把链路装配回 LiteFlow
        ChainRegistry.register(compiled, chainId);

        String traceId = UUID.randomUUID().toString().replace("-", "");
        FlowRun run = new FlowRun();
        run.setTraceId(traceId);
        run.setFlowId(flowId);
        run.setVersion(version.getVersion());
        run.setTriggerType(triggerType == null ? "manual" : triggerType);
        run.setTriggerBy(triggerBy);
        run.setStatus("RUNNING");
        run.setInputJson(toJson(input));
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);

        FlowState state = new FlowState(String.valueOf(run.getId()), traceId, compiled.nodeDefs());
        state.register(com.carbon.framework.dag.cmp.NodeHandler.class, handlers.node());
        state.register(com.carbon.framework.dag.cmp.BooleanNodeHandler.class, handlers.bool());
        state.register(com.carbon.framework.dag.cmp.IteratorNodeHandler.class, handlers.iterator());
        state.initInput(input);

        LiteflowResponse response;
        state.begin();
        try {
            response = liteFlowExecutor.execute2Resp(chainId, null, FlowCtxBean.class);
        } catch (RuntimeException e) {
            // LiteFlow 自己抛的（配置错、链未注册）不是业务失败，但运行记录必须收尾，
            // 否则库里会留一条永远 RUNNING 的幽灵记录
            finish(run, "FAILED", state, e.getMessage());
            throw e;
        } finally {
            state.end();
        }

        List<StepRecord> steps = StepMapper.from(response, compiled);
        // 失败步骤的 error 兜底：LiteFlow 的 CmpStep 不保证带异常对象，
        // 少了这一步，画布上只能看到「失败」两个字，看不到为什么失败
        String stepError = state.lastError();
        persistSteps(run.getId(), steps, compiled, stepError);

        boolean ok = response.isSuccess();
        // PARTIAL 的语义是「跑完了，但中间有节点失败」（onError=CONTINUE 的场景）；
        // 没走到 END 就停下的是 FAILED。曾用「只要有一步成功就算 PARTIAL」，
        // 结果单节点失败也被标成 PARTIAL，看板上分不清「半成功」和「彻底失败」。
        boolean reachedEnd = steps.stream()
                .anyMatch(step -> step.success() && "END".equals(typeOf(compiled.nodeDefs().get(step.elId()))));
        String status = ok ? "SUCCESS" : reachedEnd ? "PARTIAL" : "FAILED";
        String error = ok ? null : firstNonNull(response.getMessage(), stepError, "执行失败");
        finish(run, status, state, error);

        return new FlowRunResult(run.getId(), traceId, status, run.getOutputJson(), run.getErrorMsg(),
                steps.size(), run.getStartedAt(), LocalDateTime.now());
    }

    /** 链 id 规则：{@code flow_{code}_v{version}}；节点 id 前缀同此，避免不同流程的 n1 撞车。 */
    public static String chainIdOf(String code, int version) {
        return "flow_" + code + "_v" + version;
    }

    private void persistSteps(Long runId, List<StepRecord> steps, CompiledFlow compiled, String fallbackError) {
        int index = 0;
        for (StepRecord step : steps) {
            FlowStepRun row = new FlowStepRun();
            row.setFlowRunId(runId);
            row.setNodeId(step.dslId());
            row.setNodeType(typeOf(compiled.nodeDefs().get(step.elId())));
            row.setAttempt(1);
            row.setExecIndex(index++);
            row.setStatus(step.success() ? "SUCCESS" : "FAILED");
            row.setDurationMs((int) Math.min(Integer.MAX_VALUE, step.elapsedMs()));
            row.setErrorMsg(step.errorMessage() != null ? step.errorMessage()
                    : (step.success() ? null : fallbackError));
            NodeDef node = compiled.nodeDefs().get(step.elId());
            if (node instanceof CallNode call) {
                // connector_id/operation_id 留给后续补：现在只知道 code，查一次库只为填两个 id
                // 不值得（节点已经在 request_summary 里有 url），等画布要按连接器筛选时再补
                log.trace("step connector={} operation={}", call.connector(), call.operation());
            }
            stepMapper.insert(row);
        }
    }

    private void finish(FlowRun run, String status, FlowState state, String error) {
        run.setStatus(status);
        run.setFinishedAt(LocalDateTime.now());
        run.setOutputJson(toJson(new LinkedHashMap<>(state.vars())));
        run.setErrorMsg(error == null ? null : abbreviate(error));
        runMapper.updateById(run);
        if ("FAILED".equals(status)) {
            log.warn("[flow] run={} trace={} 失败：{}", run.getId(), run.getTraceId(), error);
        }
    }

    private String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String typeOf(NodeDef node) {
        if (node == null) {
            return "UNKNOWN";
        }
        return node.getClass().getSimpleName().replace("Node", "").toUpperCase();
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String abbreviate(String text) {
        return text.length() <= 2000 ? text : text.substring(0, 2000) + "...";
    }
}
