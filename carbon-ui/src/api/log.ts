import { http, type TableDataInfo } from "@/utils/http";
import type { PageQuery } from "@/api/common";

/**
 * 调用日志。
 *
 * 记录由后端的**有界队列 + 攒批**异步写入（`AsyncLogWriter`），所以：
 *  - 刚发生的调用可能晚几百毫秒才查得到（页面上「刷新」即可，不要做成自动轮询打库）；
 *  - `dropped` 不为 0 意味着队列满丢过日志，必须在页头显式告警，否则
 *    「为什么少了一条」永远查不清。
 */
export interface InvokeLog {
  id: string;
  traceId: string;
  /** CONSOLE（试调）/ GW（网关）/ FLOW（编排节点） */
  source: string;
  appId?: string;
  flowRunId?: string;
  connectorId?: string;
  operationId?: string;
  method?: string;
  realUrl?: string;
  /** 已脱敏的请求头 JSON */
  reqHeadersMasked?: string;
  reqBody?: string;
  respStatus?: number;
  /** 超过 64KB 时被截断 */
  respBody?: string;
  durationMs?: number;
  errCode?: string;
  errMsg?: string;
  createdAt?: string;
}

export interface WriterStatus {
  queueSize: number;
  written: number;
  failed: number;
  /** 队列满被丢弃的条数，非 0 就是故障信号 */
  dropped: number;
}

export const LOG_SOURCES = [
  { value: "CONSOLE", label: "试调", type: "primary" as const },
  { value: "GW", label: "网关", type: "success" as const },
  { value: "FLOW", label: "编排", type: "warning" as const }
];

export interface LogQuery extends PageQuery {
  connectorId?: string;
  operationId?: string;
  source?: string;
  traceId?: string;
  /** true 只看失败（后端参数名就是 onlyFailed，不是 success） */
  onlyFailed?: boolean;
  /** ISO 本地时间，如 2026-10-27T00:00:00（后端 @DateTimeFormat(iso = DATE_TIME)） */
  beginTime?: string;
  endTime?: string;
}

export function pageLogs(query: LogQuery) {
  return http.get<TableDataInfo<InvokeLog>>("/logs", { params: query });
}

/** 同一 traceId 可能有多次调用（编排里同一节点重试），按时间正序返回 */
export function logsByTraceId(traceId: string) {
  return http.get<Array<InvokeLog>>(`/logs/${traceId}`);
}

export function writerStatus() {
  return http.get<WriterStatus>("/logs/writer/status");
}
