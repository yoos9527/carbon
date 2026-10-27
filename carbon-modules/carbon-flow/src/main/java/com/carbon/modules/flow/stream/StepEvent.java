package com.carbon.modules.flow.stream;

/**
 * 节点执行的一次播报。
 *
 * <p>与 {@code flow_step_run} 的区别：这是<b>实时</b>事件（节点进/出各一条），
 * 落库的那份是运行结束后的权威快照。两者不抢职责——实时事件丢了不影响审计，
 * 落库那份永远能回放。
 *
 * @param runId     flow_run 主键
 * @param nodeId    画布上的节点 id（DSL 里的 id，不是 LiteFlow 的 elId）
 * @param nodeType  CALL / SCRIPT / DELAY / START / END / COND / LOOP / FOREACH
 * @param status    RUNNING（进入）| SUCCESS | FAILED（离开）
 * @param durationMs 离开事件才有
 * @param error     仅 FAILED
 * @author carbon
 */
public record StepEvent(String runId, String nodeId, String nodeType, String status,
                        Long durationMs, String error) {

    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
}
