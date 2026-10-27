package com.carbon.modules.flow.executor;

import java.time.LocalDateTime;

/**
 * 一次流程执行的结果摘要。
 *
 * <p>只回摘要，不回 {@code vars}：编排的中间变量可能有几十 KB，
 * 调用方要细节就去查 {@code /api/flow-runs/{id}}，别把执行接口的响应撑大。
 *
 * @param runId      flow_run 主键
 * @param traceId    链路 id
 * @param status     RUNNING|SUCCESS|PARTIAL|FAILED
 * @param output     最终变量快照（JSON 串）
 * @param error      失败原因
 * @param stepCount  落了明细的节点数
 * @param startedAt  开始时间
 * @param finishedAt 结束时间
 * @author carbon
 */
public record FlowRunResult(Long runId, String traceId, String status, String output, String error,
                            int stepCount, LocalDateTime startedAt, LocalDateTime finishedAt) {
}
