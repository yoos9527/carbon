package com.carbon.modules.gateway.gw;

import com.carbon.modules.flow.domain.entity.Flow;
import com.carbon.modules.flow.executor.FlowRunResult;
import com.carbon.modules.flow.executor.FlowRunner;
import com.carbon.modules.flow.service.IFlowService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 编排的对外入口：{@code /gw/flows/{flowCode}}。
 *
 * <p>与 {@code /gw/{connectorCode}/{operationCode}} 的关系是「一个接口 vs 一段业务」：
 * 连接器入口适合「一次三方调用」，编排入口适合「一次需要多次调用 + 判断 + 循环」的业务动作。
 * 两者共用同一套签名协议与错误码（见 {@link GwGuard}），接入方不需要学两遍。
 *
 * <p><b>同步执行</b>：调用方要的是结果，异步只能靠轮询 {@code /api/flow-runs/{id}}，
 * 而那是管理端接口（要按应用分权），不该暴露给外部系统。代价是流程里不能放
 * 几十秒的 {@code DELAY}——那类流程更适合走 Quartz 定时，而不是网关同步调用。
 *
 * <p>body 顶层字段 + query 直接作为流程 input（同 {@code InvokeService} 的约定）。
 *
 * @author carbon
 */
@RestController
@RequestMapping("/gw/flows")
public class GwFlowController {

    private final GwGuard guard;
    private final IFlowService flowService;
    private final FlowRunner flowRunner;

    public GwFlowController(GwGuard guard, IFlowService flowService, FlowRunner flowRunner) {
        this.guard = guard;
        this.flowService = flowService;
        this.flowRunner = flowRunner;
    }

    @RequestMapping("/{flowCode}")
    public ResponseEntity<GwResult<Map<String, Object>>> run(
            @PathVariable String flowCode,
            HttpServletRequest request,
            @RequestBody(required = false) String rawBody) {

        String traceId = GwGuard.currentTraceId();
        long startedAt = System.currentTimeMillis();

        // 校验顺序与连接器入口完全一致；连接器白名单在这一步之后按流程引用逐个补
        GwGuard.Caller caller = guard.verify(request, rawBody, null);
        Flow flow = flowService.requirePublishedByCode(flowCode);
        guard.requireFlowConnectorsAllowed(caller, flowCode, flowService.publishedConnectorIds(flow.getId()));

        Map<String, Object> input = GwGuard.collectInput(request, rawBody);
        FlowRunResult result = flowRunner.run(flow.getId(), input, "gw", caller.appKey());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", result.runId());
        data.put("status", result.status());
        data.put("output", result.output());
        data.put("stepCount", result.stepCount());
        data.put("flowTraceId", result.traceId());
        if (result.error() != null) {
            data.put("error", result.error());
        }
        // 流程跑完但状态不是 SUCCESS（PARTIAL/FAILED）时，HTTP 仍给 200：
        // 「网关调用成功、业务执行失败」是两种不同的事实，混成一个状态码业务方无从区分
        GwErrorCode code = "SUCCESS".equals(result.status()) ? GwErrorCode.OK : GwErrorCode.INTERNAL_ERROR;
        GwResult<Map<String, Object>> body = new GwResult<>(code.code(),
                result.error() == null ? code.defaultMessage() : result.error(),
                data, traceId, null, System.currentTimeMillis() - startedAt);
        return ResponseEntity.status(code.httpStatus()).body(body);
    }
}
