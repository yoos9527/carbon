package com.carbon.modules.flow.handler;

import com.carbon.framework.dag.cmp.NodeHandler;
import com.carbon.framework.dag.dsl.node.CallNode;
import com.carbon.framework.dag.dsl.node.DelayNode;
import com.carbon.framework.dag.dsl.node.NodeDef;
import com.carbon.framework.dag.dsl.node.ScriptNode;
import com.carbon.framework.dag.runtime.DagExecutionContext;
import com.carbon.framework.expression.RenderContext;
import com.carbon.framework.expression.TemplateRenderer;
import com.carbon.framework.script.GroovySandbox;
import com.carbon.framework.script.ScriptResult;
import com.carbon.modules.engine.invoke.InvokeRequest;
import com.carbon.modules.engine.invoke.InvokeResult;
import com.carbon.modules.engine.invoke.InvokeService;
import com.carbon.modules.flow.executor.FlowState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 普通节点执行体：CONNECTOR_CALL / SCRIPT / DELAY / START / END。
 *
 * <p>一次运行里所有节点共用一个实例（LiteFlow 组件是无状态单例，执行体也就没必要每节点 new 一个），
 * 所以这里<b>只能</b>用入参 {@code node} 与 {@code ctx}，不许有实例字段保存运行状态。
 *
 * @author carbon
 */
@Component
public class FlowNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(FlowNodeHandler.class);

    private final InvokeService invokeService;
    private final TemplateRenderer renderer;
    private final GroovySandbox sandbox;
    private final ObjectMapper objectMapper;

    public FlowNodeHandler(InvokeService invokeService, TemplateRenderer renderer,
                           GroovySandbox sandbox, ObjectMapper objectMapper) {
        this.invokeService = invokeService;
        this.renderer = renderer;
        this.sandbox = sandbox;
        this.objectMapper = objectMapper;
    }

    @Override
    public void execute(NodeDef node, DagExecutionContext ctx) throws Exception {
        try {
            switch (node) {
                case CallNode call -> call(call, ctx);
                case ScriptNode script -> script(script, ctx);
                case DelayNode delay -> delay(delay, ctx);
                // START/END 没有副作用：它们的价值在图上（入口唯一、出口判定），不在执行期
                default -> {
                }
            }
        } catch (RuntimeException e) {
            // LiteFlow 的 CmpStep 不保证带上异常对象，这里留一份给 flow_step_run 用
            FlowState.evalVarsOf(ctx);
            FlowState.current().lastError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw e;
        }
    }

    /* ---------- CONNECTOR_CALL ---------- */

    private void call(CallNode node, DagExecutionContext ctx) throws Exception {
        RenderContext renderContext = renderContext(ctx);
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        node.input().forEach((key, value) -> input.put(key, renderValue(value, renderContext)));

        InvokeResult result = invokeService.invoke(new InvokeRequest(
                node.connector(), node.operation(), input, null, null, false, "FLOW", null));

        // 三方 4xx/5xx 是**业务结果**：写进 vars 让后面的 CONDITION 分流，不在这里抛。
        // 只有技术故障（连不上、读超时、鉴权拿不到 token）才抛——这条边界一破，
        // 「上游 500 也当异常」会让编排退化成一根直线，IF 节点永远用不上。
        boolean businessFailure = "PROVIDER_HTTP_4XX".equals(result.errorCode())
                || "PROVIDER_HTTP_5XX".equals(result.errorCode());
        if (!result.success() && !businessFailure) {
            throw new IllegalStateException("节点 " + node.id() + " 调用失败：" + result.errorMsg());
        }

        Map<String, Object> slot = new java.util.LinkedHashMap<>();
        slot.put("status", result.status());
        slot.put("errorCode", result.errorCode());
        slot.put("body", result.rawBody());
        ctx.vars().put(node.id(), slot);
        ctx.vars().putAll(result.extracted());

        // output 是「变量名 → JSONPath」，在接口级抽取之外给编排层再抽一次
        if (!node.output().isEmpty()) {
            Map<String, Object> extracted = extractByJsonPath(result.rawBody(), node.output());
            ctx.vars().putAll(extracted);
        }
    }

    /* ---------- SCRIPT ---------- */

    private void script(ScriptNode node, DagExecutionContext ctx) {
        // 沙箱**原地**改传入的 ctx，并把 utils/log 塞进去：脚本写 `vars.x = 1` 就是改它。
        // 失败直接抛 ScriptError（编译期违规/超时/运行期异常三类），交给节点失败处理。
        FlowState state = FlowState.current();
        sandbox.execute(node.script(), state.evalVars(), state.traceId());
    }

    /* ---------- DELAY ---------- */

    private void delay(DelayNode node, DagExecutionContext ctx) throws InterruptedException {
        long ms = node.delayMs() != null && node.delayMs() > 0
                ? node.delayMs()
                : ThreadLocalRandom.current().nextLong(node.randomMinMs(), node.randomMaxMs() + 1);
        Thread.sleep(ms);
    }

    /* ---------- 工具 ---------- */

    private RenderContext renderContext(DagExecutionContext ctx) {
        RenderContext context = RenderContext.create();
        // vars 是「此刻的变量表」，input 是「触发时传进来的那批」——两者必须分开。
        // 踩过的坑：曾把 evalVars 的包装表直接灌进来，得到 vars.vars.x 的嵌套，
        // 模板 {{vars.orderId}} 全部解析成 null，所有 CALL 节点集体渲染失败。
        ctx.vars().forEach(context::var);
        FlowState.initialInputOf(ctx).forEach(context::input);
        return context;
    }

    /**
     * 渲染单个入参值。
     *
     * <p>字符串按模板渲染并保持原类型（{@code renderNode} 会保留数字/布尔），
     * 非字符串原样透传——JSON 数组/对象里的模板由 renderer 递归处理。
     */
    private Object renderValue(Object value, RenderContext context) {
        if (value instanceof String text && text.contains("{{")) {
            String rendered = renderer.renderText(text, context);
            return coerce(rendered);
        }
        return value;
    }

    /**
     * 把渲染结果还原成 JSON 类型。
     *
     * <p>模板渲染出来是字符串，但 {@code {{vars.qty}}} 里的 qty 本来是数字：
     * 直接当字符串发出去，三方会收到 {@code "3"} 而不是 {@code 3}。
     * 这里只对「看起来像 JSON 标量」的结果做还原，普通文本不受影响。
     */
    private Object coerce(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return text;
        }
        if ("true".equals(trimmed) || "false".equals(trimmed)) {
            return Boolean.parseBoolean(trimmed);
        }
        if ("null".equals(trimmed)) {
            return null;
        }
        if (trimmed.matches("-?\\d+")) {
            return Long.parseLong(trimmed);
        }
        if (trimmed.matches("-?\\d+\\.\\d+")) {
            return Double.parseDouble(trimmed);
        }
        if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            try {
                return objectMapper.readValue(trimmed, Object.class);
            } catch (Exception ignored) {
                return text;
            }
        }
        return text;
    }

    private Map<String, Object> extractByJsonPath(String body, Map<String, String> output) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return result;
        }
        com.jayway.jsonpath.DocumentContext document = com.jayway.jsonpath.JsonPath.parse(body);
        output.forEach((key, path) -> {
            try {
                result.put(key, document.read(path));
            } catch (Exception e) {
                log.debug("节点输出抽取失败 key={} path={}：{}", key, path, e.getMessage());
            }
        });
        return result;
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
