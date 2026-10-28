import { http, type TableDataInfo } from "@/utils/http";
import { MAX_PAGE_SIZE } from "@/api/common";
import {
  pageConnectors,
  pageOperations,
  type Connector,
  type Operation
} from "@/api/connector";

/**
 * 数据源服务接口 = 接口 + 所属连接器。
 *
 * 后端**没有** `GET /api/services`：接口挂在 `/api/connectors/{connectorId}/operations`
 * 这条嵌套路径下。所以这个页面只能前端聚合——先拉连接器，再并发拉每个连接器的接口。
 * 这不是偷懒：连接器数量是"几十个"量级（设计文档 §2.2 方案 A），比改后端加一个
 * 平铺接口的收益更高。哪天连接器过百，就该让后端补 `GET /api/services`。
 */
export interface ServiceItem extends Operation {
  connectorCode: string;
  connectorName: string;
  connectorStatus: number;
  connectorCategory?: string;
  baseUrl: string;
}

export interface ServiceQuery {
  keyword?: string;
  connectorId?: string;
  method?: string;
  status?: number;
  /** 只看没有出参契约的接口（对账时找"没配抽取"的裸接口） */
  onlyNoContract?: boolean;
}

/** 并发上限：一次起 40 个请求会被浏览器排队成串行，还可能触发网关限流 */
const CONCURRENCY = 6;

export const METHODS = ["GET", "POST", "PUT", "DELETE", "PATCH"];

/** 接口是否已配契约（出参抽取非空。入参 schema 见 docs/09 §7，列还没加） */
export function hasContract(op: Operation) {
  return Array.isArray(op.responseExtract) && op.responseExtract.length > 0;
}

/**
 * 拉全部连接器。
 * 超过单页上限时标记 truncated——此时列表不完整，页面必须显式提示，
 * 不能让用户以为"系统里只有 200 个连接器"。
 */
export async function listAllConnectors(): Promise<{
  connectors: Array<Connector>;
  truncated: boolean;
}> {
  const page = await pageConnectors({ pageNum: 1, pageSize: MAX_PAGE_SIZE });
  return {
    connectors: page.rows,
    truncated: page.total > page.rows.length
  };
}

/** 分批并发，保持结果顺序 */
async function mapLimit<T, R>(
  items: Array<T>,
  limit: number,
  fn: (item: T) => Promise<R>
): Promise<Array<R>> {
  const out: Array<R> = new Array(items.length);
  let cursor = 0;
  async function worker() {
    while (cursor < items.length) {
      const i = cursor++;
      out[i] = await fn(items[i]);
    }
  }
  await Promise.all(
    Array.from({ length: Math.min(limit, items.length) }, worker)
  );
  return out;
}

export async function listServices(query: ServiceQuery = {}): Promise<{
  items: Array<ServiceItem>;
  total: number;
  connectorTotal: number;
  truncated: boolean;
}> {
  const { connectors, truncated } = await listAllConnectors();
  const picked = query.connectorId
    ? connectors.filter(c => String(c.id) === query.connectorId)
    : connectors;

  // 单连接器失败不能拖垮整页：某条连接器接口取不到就跳过，并记录其编码
  const failed: Array<string> = [];
  const groups = await mapLimit(picked, CONCURRENCY, async c => {
    try {
      const page = await pageOperations(String(c.id), {
        pageNum: 1,
        pageSize: MAX_PAGE_SIZE
      });
      return { connector: c, rows: page.rows };
    } catch {
      failed.push(c.code);
      return { connector: c, rows: [] as Array<Operation> };
    }
  });

  let items: Array<ServiceItem> = [];
  groups.forEach(({ connector, rows }) => {
    rows.forEach(op => {
      items.push({
        ...op,
        connectorCode: connector.code,
        connectorName: connector.name,
        connectorStatus: connector.status,
        connectorCategory: connector.category,
        baseUrl: connector.baseUrl
      });
    });
  });

  const kw = query.keyword?.trim().toLowerCase();
  if (kw) {
    items = items.filter(
      i =>
        i.name?.toLowerCase().includes(kw) ||
        i.code?.toLowerCase().includes(kw) ||
        i.path?.toLowerCase().includes(kw)
    );
  }
  if (query.method) items = items.filter(i => i.method === query.method);
  if (query.status !== undefined && query.status !== null) {
    items = items.filter(i => i.status === query.status);
  }
  if (query.onlyNoContract) items = items.filter(i => !hasContract(i));

  if (failed.length) {
    // 不抛异常：页面已经拿到部分数据，提示比整页白屏有用
    console.warn("[services] 以下连接器的接口拉取失败：", failed.join(", "));
  }

  return {
    items,
    total: items.length,
    connectorTotal: connectors.length,
    truncated
  };
}

/** 详情页按 id 取单个接口 */
export function getService(connectorId: string, operationId: string) {
  return http.get<Operation>(
    `/connectors/${connectorId}/operations/${operationId}`
  );
}

export function updateService(
  connectorId: string,
  operationId: string,
  body: Partial<Operation>
) {
  return http.put<void>(
    `/connectors/${connectorId}/operations/${operationId}`,
    body
  );
}

export function createService(connectorId: string, body: Partial<Operation>) {
  return http.post<string>(`/connectors/${connectorId}/operations`, body);
}

export function deleteServices(connectorId: string, ids: Array<string>) {
  return http.delete<void>(`/connectors/${connectorId}/operations`, {
    data: ids
  });
}

export interface InvokeResult {
  [k: string]: unknown;
}

/**
 * 试调：走 `/api/invoke/{connectorCode}/{operationCode}`（与网关同一个入口）。
 * `dryRun` 只渲染请求不发出去——排查模板问题最快的一条路。
 */
export function invokeService(
  connectorCode: string,
  operationCode: string,
  body: { input: unknown; dryRun: boolean; envCode?: string }
) {
  return http.post<InvokeResult>(
    `/invoke/${connectorCode}/${operationCode}`,
    body
  );
}

/**
 * `connectorId:operationId` → `连接器名 / 接口名`。
 *
 * 调用日志里只存两个雪花 id，直接展示等于让运营对着数字猜——而日志量是
 * “每页几十条”，一次拉全量建 Map 比每行查一次接口便宜得多。
 */
export async function loadOperationNameMap(): Promise<Map<string, string>> {
  const { items } = await listServices();
  const map = new Map<string, string>();
  items.forEach(i => {
    map.set(`${i.connectorId}:${i.id}`, `${i.connectorName} / ${i.name}`);
  });
  return map;
}

export const BODY_TYPES = [
  { value: "none", label: "无" },
  { value: "json", label: "JSON" },
  { value: "form", label: "Form" },
  { value: "urlencoded", label: "x-www-form-urlencoded" },
  { value: "raw", label: "Raw" }
];

export const OPERATION_STATUS = [
  { value: 0, label: "草稿", type: "info" as const },
  { value: 1, label: "启用", type: "success" as const }
];

export type { TableDataInfo };
