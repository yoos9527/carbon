import { API_BASE, http, type TableDataInfo } from "@/utils/http";
import type { PageQuery } from "@/api/common";

/**
 * 流程（编排）与运行记录。
 *
 * 只读：画布/发布/试跑在 P2 的画布页里做，这里只服务「运行记录」列表与详情。
 * `draftDsl` 是整张图的 JSON（几十 KB 起），列表页**不请求**也不显示。
 */

export interface Flow {
  id: string;
  code: string;
  name: string;
  /** 当前已发布版本号，0 表示从未发布 */
  publishedVersion?: number;
  status: number;
  createdAt?: string;
  updatedAt?: string;
}

/** 运行记录（对应后端 FlowRun 实体） */
export interface FlowRun {
  id: string;
  traceId: string;
  flowId: string;
  /** 本次运行用的流程版本（未发布时为空） */
  version?: number;
  /** manual（手工）/ job（调度） */
  triggerType: string;
  /** 手工触发为空；调度触发是任务名 */
  triggerBy?: string;
  /** SUCCESS / FAILED / PARTIAL / RUNNING */
  status: string;
  inputJson?: string;
  outputJson?: string;
  errorMsg?: string;
  startedAt?: string;
  finishedAt?: string;
}

/** 节点执行明细（对应后端 FlowStepRun 实体） */
export interface FlowStepRun {
  id: string;
  flowRunId: string;
  nodeId: string;
  nodeType: string;
  connectorId?: string;
  operationId?: string;
  attempt?: number;
  /** 同毫秒内 LOOP 展开的顺序靠它，时间戳排不出来 */
  execIndex: number;
  requestSummary?: string;
  responseSummary?: string;
  status: string;
  durationMs?: number;
  errorMsg?: string;
}

export interface FlowRunDetail {
  run: FlowRun;
  steps: Array<FlowStepRun>;
}

/** 运行状态字典（与 FlowRunner 里的字面量一致） */
export const RUN_STATUS = [
  { value: "SUCCESS", label: "成功", type: "success" as const },
  { value: "PARTIAL", label: "部分成功", type: "warning" as const },
  { value: "FAILED", label: "失败", type: "danger" as const },
  { value: "RUNNING", label: "运行中", type: "primary" as const }
];

/** 触发方式：后端存的是小写字面量（FlowController 传 manual、FlowJobHandler 传 job） */
export const TRIGGER_TYPES = [
  { value: "manual", label: "手工" },
  { value: "job", label: "调度" }
];

/* ---------- 运行进度（SSE） ---------- */

/** 单个节点的实时播报：RUNNING（进入）/ SUCCESS / FAILED（离开） */
export interface FlowRunStepEvent {
  runId: string;
  nodeId: string;
  nodeType: string;
  status: string;
  /** 包装了 ToStringSerializer，Long 会以字符串下发 */
  durationMs?: number | string;
  error?: string;
}

/** 终态事件：带完整明细，收到即可断开 */
export interface FlowRunDoneEvent {
  runId: string;
  status?: string;
  error?: string;
  startedAt?: string;
  finishedAt?: string;
  durationMs?: number | string;
  steps?: Array<FlowStepRun>;
}

export interface FlowRunStreamHandlers {
  onSnapshot?: (run: FlowRun) => void;
  onStep?: (event: FlowRunStepEvent) => void;
  onDone?: (event: FlowRunDoneEvent) => void;
  /** 连接异常（浏览器会自动重连，这里只做提示） */
  onError?: (event: Event) => void;
}

/**
 * 订阅运行进度，返回「断开」函数。
 *
 * 用原生 `EventSource` 而不是 axios：SSE 是长连接，axios 拿到的是迟迟不结束的响应体。
 * 后端只发命名事件（snapshot/step/done），所以必须 `addEventListener`，默认的
 * `onmessage` 收不到。服务端在 `done` 后主动关连接，浏览器对已完成的事件流不会重连。
 */
export function streamFlowRun(
  runId: string,
  handlers: FlowRunStreamHandlers
): () => void {
  const source = new EventSource(`${API_BASE}/flow-runs/${runId}/stream`);
  const on = <T>(name: string, fn?: (payload: T) => void) => {
    if (!fn) return;
    source.addEventListener(name, (event: MessageEvent) => {
      try {
        fn(JSON.parse(event.data) as T);
      } catch {
        // 单条事件解析失败不该让整条流断掉
      }
    });
  };
  on<FlowRun>("snapshot", handlers.onSnapshot);
  on<FlowRunStepEvent>("step", handlers.onStep);
  on<FlowRunDoneEvent>("done", handlers.onDone);
  if (handlers.onError) source.onerror = handlers.onError;
  return () => source.close();
}

export function pageFlows(
  query: PageQuery & { keyword?: string; status?: number }
) {
  return http.get<TableDataInfo<Flow>>("/flows", { params: query });
}

export function getFlow(id: string) {
  return http.get<Flow>(`/flows/${id}`);
}

export function pageFlowRuns(
  query: PageQuery & { flowId?: string; status?: string }
) {
  return http.get<TableDataInfo<FlowRun>>("/flow-runs", { params: query });
}

export function getFlowRun(id: string) {
  return http.get<FlowRunDetail>(`/flow-runs/${id}`);
}

/**
 * 流程 id → 名称。
 *
 * 运行记录表和任务表都只存 `flowId`，列表里显示裸 id 没法看，而流量的数量级
 * 是「几十条」而不是「几十万条」，一次拉全量建 Map 比每行调 `GET /flows/{id}` 划算。
 */
export async function loadFlowNameMap(): Promise<Map<string, string>> {
  const page = await pageFlows({ pageNum: 1, pageSize: 200 });
  return new Map(page.rows.map(f => [f.id, f.name]));
}
