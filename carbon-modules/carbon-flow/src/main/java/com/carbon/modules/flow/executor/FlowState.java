package com.carbon.modules.flow.executor;

import com.carbon.framework.dag.dsl.node.NodeDef;
import com.carbon.framework.dag.runtime.DagExecutionContext;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次流程运行的真实状态。
 *
 * <p>为什么状态不在上下文 Bean 上：LiteFlow 用无参构造自己 new 上下文 Bean，我们没法往里塞
 * {@code runId}/{@code vars}；而组件是无状态单例，也不能存字段。所以状态放这里，
 * {@link FlowCtxBean} 只做委托，运行线程用 {@link #current()} 取。
 *
 * <p><b>已知边界</b>：用 {@code ThreadLocal} 承载，因此 FOREACH 的并发展开（跨线程）暂不支持；
 * 当前 {@code ForeachCmp} 是顺序执行，所以成立。真要开并发时，这里要换成
 * "runId → 状态" 的注册表 + 显式传参，不能靠线程本地。
 *
 * @author carbon
 */
public final class FlowState implements DagExecutionContext {

    private static final ThreadLocal<FlowState> CURRENT = new ThreadLocal<>();

    private final String runId;
    private final String traceId;
    private final Map<String, NodeDef> nodeDefs;
    private final Map<String, Object> vars = new ConcurrentHashMap<>();
    /** 触发时的入参快照（不清空、不被脚本改写），供 {{input.x}} 使用。 */
    private final Map<String, Object> initialInput = new LinkedHashMap<>();
    private final Map<Class<?>, Object> services = new HashMap<>();

    public FlowState(String runId, String traceId, Map<String, NodeDef> nodeDefs) {
        this.runId = runId;
        this.traceId = traceId;
        this.nodeDefs = nodeDefs;
    }

    /* ---------- 生命周期 ---------- */

    public void begin() {
        CURRENT.set(this);
    }

    public void end() {
        CURRENT.remove();
    }

    /** 不在执行线程内时返回 {@code null}——给「顺手播报一下」这类可缺省的逻辑用。 */
    public static FlowState currentOrNull() {
        return CURRENT.get();
    }

    public static FlowState current() {
        FlowState state = CURRENT.get();
        if (state == null) {
            throw new IllegalStateException("不在流程执行线程内：FlowState 未绑定");
        }
        return state;
    }

    /* ---------- 服务注入 ---------- */

    public FlowState register(Class<?> type, Object impl) {
        services.put(type, impl);
        return this;
    }

    public String traceId() {
        return traceId;
    }

    private volatile String lastError;

    /** 最近一次节点失败的说明。LiteFlow 的 CmpStep 并不总是带上异常，这里留一份兜底。 */
    public String lastError() {
        return lastError;
    }

    public void lastError(String message) {
        this.lastError = message;
    }

    /** 触发时传入的原始入参快照，供 {{input.x}} 使用。 */
    public static Map<String, Object> initialInputOf(DagExecutionContext ctx) {
        return ctx instanceof FlowState state ? state.initialInput : FlowState.current().initialInput;
    }

    /** 触发入参同时进入 vars 与 input 快照。 */
    public void initInput(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return;
        }
        vars.putAll(input);
        initialInput.putAll(input);
    }

    /**
     * 取表达式变量视图。
     *
     * <p>为什么收在这里：执行体拿到的 {@code ctx} 是 LiteFlow 造的 {@link FlowCtxBean}，
     * 它的 {@code vars()} 是**平铺**的变量表（没有 {@code vars.} 前缀）。
     * 在别处各写一遍 instanceof 判断，迟早有一处漏掉——那就是「条件永远走 false」这种极难查的 bug。
     */
    public static Map<String, Object> evalVarsOf(DagExecutionContext ctx) {
        if (ctx instanceof FlowState state) {
            return state.evalVars();
        }
        return current().evalVars();
    }

    /**
     * 表达式变量视图：同时暴露 {@code vars.x} 与 {@code input.x}。
     *
     * <p>为什么两个都要：画布里 {@code CONDITION} 常写 {@code vars.amount > 100}（抽取结果），
     * 而 {@code FOREACH} 的 {@code items} 常写 {@code input.orders}（调用方入参）。只给一个就得让用户记规则。
     */
    public Map<String, Object> evalVars() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("vars", vars);
        view.put("input", initialInput);
        view.put("traceId", traceId);
        return view;
    }

    /* ---------- DagExecutionContext ---------- */

    @Override
    public String flowRunId() {
        return runId;
    }

    @Override
    public NodeDef nodeDef(String elId) {
        return nodeDefs.get(elId);
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
    public <T> T service(Class<T> type) {
        Object impl = services.get(type);
        return impl == null ? null : type.cast(impl);
    }
}
