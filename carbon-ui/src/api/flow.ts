import { API_BASE, http, type TableDataInfo } from "@/utils/http";
import type { PageQuery } from "@/api/common";

/**
 * 流程（编排）、版本与运行记录。
 *
 * `draftDsl` 是整张图的 JSON（几十 KB 起）：列表接口不返回、列表页也不显示，
 * 只有画布页才 `GET /flows/{id}` 取它。列表里能用的只有 code/name/publishedVersion。
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
  /** 只有 GET /flows/{id} 才有 */
  draftDsl?: string;
}

/** 版本快照（对应后端 FlowVersion）：发布后不可变 */
export interface FlowVersion {
  id: string;
  flowId: string;
  version: number;
  dsl: string;
  publishedBy?: string;
  publishedAt?: string;
}

/**
 * 校验错误。
 *
 * `nodeId` 是画布定位的关键：没有它，用户看到「条件表达式不合法」只能自己满图找。
 */
export interface ValidationError {
  code: string;
  message: string;
  nodeId?: string;
  edgeSource?: string;
  edgeTarget?: string;
}

/** 同步试跑结果（POST /flows/{id}/run） */
export interface FlowRunResult {
  runId?: string;
  status?: string;
  output?: Record<string, unknown>;
  steps?: Array<FlowStepRun>;
  error?: string;
  durationMs?: number;
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

/* ---------- 写操作 ---------- */

/** 新建：`draftDsl` 允许不合法（草稿就是改到一半的状态） */
export function createFlow(body: {
  code: string;
  name: string;
  draftDsl?: string;
  status?: number;
}) {
  return http.post<Flow>("/flows", body);
}

export function updateFlow(
  id: string,
  body: {
    code: string;
    name: string;
    draftDsl?: string;
    status?: number;
  }
) {
  return http.put<Flow>(`/flows/${id}`, body);
}

export function deleteFlow(id: string) {
  return http.delete<void>(`/flows/${id}`);
}

/** 校验草稿：返回**全部**错误，画布据此逐条定位 */
export function validateFlowDsl(dsl: string) {
  return http.post<Array<ValidationError>>("/flows/validate", { dsl });
}

/** 发布当前草稿，生成不可变版本快照 */
export function publishFlow(id: string, operator = "anonymous") {
  return http.post<FlowVersion>(`/flows/${id}/publish`, undefined, {
    params: { operator }
  });
}

export function listFlowVersions(id: string) {
  return http.get<Array<FlowVersion>>(`/flows/${id}/versions`);
}

/** 回滚：指针回退 + 草稿同步（草稿会被覆盖成该版本，前端必须二次确认） */
export function rollbackFlow(id: string, version: number, operator = "anonymous") {
  return http.post<FlowVersion>(
    `/flows/${id}/rollback`,
    { version },
    { params: { operator } }
  );
}

/** 同步试跑已发布版本（跑完才返回，适合秒级流程） */
export function runFlow(id: string, input: Record<string, unknown> = {}) {
  return http.post<FlowRunResult>(`/flows/${id}/run`, input);
}

/** 异步试跑：立刻返回 RUNNING 的运行记录，拿 id 去连 SSE 看进度 */
export function runFlowAsync(id: string, input: Record<string, unknown> = {}) {
  return http.post<FlowRun>(`/flows/${id}/run-async`, input);
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
