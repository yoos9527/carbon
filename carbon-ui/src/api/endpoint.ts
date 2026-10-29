import { http, type TableDataInfo } from "@/utils/http";

/**
 * 接入点（后端仍叫 ConnectorVo/Connector，路径已是 /api/endpoints）。
 *
 * 它只描述「怎么连过去」：baseUrl、公共头、超时、重试。
 * **没有密钥字段**——密钥属于合作方（见 api/partner.ts）。
 */
export interface Endpoint {
  id: string;
  /** 所属合作方 */
  partnerId: string;
  partnerName?: string;
  /** 默认凭证；留空表示用合作方的默认凭证 */
  credentialId?: string;
  credentialName?: string;
  code: string;
  name: string;
  category?: string;
  baseUrl: string;
  defaultHeaders?: Record<string, string>;
  timeoutMs?: number;
  retry?: Record<string, unknown>;
  status: number;
  remark?: string;
  operationCount?: number;
  createdAt?: string;
  updatedAt?: string;
}

/** 接口（对应后端 OperationVo） */
export interface Operation {
  id: string;
  connectorId: string;
  code: string;
  name: string;
  method: string;
  path: string;
  queryParams?: Record<string, unknown>;
  headers?: Record<string, unknown>;
  bodyType: string;
  bodyTemplate?: string;
  responseExtract?: Array<Record<string, unknown>>;
  preScript?: string;
  postScript?: string;
  scriptTimeoutMs?: number;
  timeoutMs?: number;
  /** JSON，null 表示继承接入点 */
  retry?: Record<string, unknown>;
  /** INHERIT / OVERRIDE / NONE */
  credentialMode?: string;
  credentialId?: string;
  credentialName?: string;
  status: number;
}

export interface PageQuery {
  pageNum: number;
  pageSize: number;
  orderByColumn?: string;
  isAsc?: string;
}

export interface EndpointQuery extends PageQuery {
  keyword?: string;
  category?: string;
  status?: number;
  partnerId?: string;
}

export const CREDENTIAL_MODES = [
  { value: "INHERIT", label: "继承", hint: "沿用接入点或合作方的默认凭证" },
  { value: "OVERRIDE", label: "指定凭证", hint: "本接口使用另一条凭证" },
  { value: "NONE", label: "不鉴权", hint: "这个接口不需要任何凭证" }
];

export function pageEndpoints(query: EndpointQuery) {
  return http.get<TableDataInfo<Endpoint>>("/endpoints", { params: query });
}

export function getEndpoint(id: string) {
  return http.get<Endpoint>(`/endpoints/${id}`);
}

export function createEndpoint(body: Partial<Endpoint>) {
  return http.post<string>("/endpoints", body);
}

export function updateEndpoint(id: string, body: Partial<Endpoint>) {
  return http.put<void>(`/endpoints/${id}`, body);
}

/**
 * 复制接入点（含接口）。
 *
 * 副本一律以**停用**状态落地：它指向同一个三方地址，一创建就能被网关调到，
 * 等于「复制一下就多了一个生产入口」。空 body 时由后端推导 `<原编码>-copy`。
 */
export function copyEndpoint(
  id: string,
  body: { code?: string; name?: string; withOperations?: boolean } = {}
) {
  return http.post<string>(`/endpoints/${id}/copy`, body);
}

export function updateEndpointStatus(id: string, status: number) {
  return http.put<void>(`/endpoints/${id}/status`, undefined, {
    params: { status }
  });
}

/** 删除：后端接收 body 里的 id 数组 */
export function deleteEndpoints(ids: Array<string>) {
  return http.delete<void>("/endpoints", { data: ids });
}

export function pageOperations(
  connectorId: string,
  query: PageQuery & { keyword?: string; status?: number }
) {
  return http.get<TableDataInfo<Operation>>(
    `/endpoints/${connectorId}/operations`,
    { params: query }
  );
}

export function getOperation(connectorId: string, id: string) {
  return http.get<Operation>(`/endpoints/${connectorId}/operations/${id}`);
}

export function createOperation(connectorId: string, body: Partial<Operation>) {
  return http.post<string>(`/endpoints/${connectorId}/operations`, body);
}

export function updateOperation(
  connectorId: string,
  id: string,
  body: Partial<Operation>
) {
  return http.put<void>(`/endpoints/${connectorId}/operations/${id}`, body);
}

export function deleteOperations(connectorId: string, ids: Array<string>) {
  return http.delete<void>(`/endpoints/${connectorId}/operations`, {
    data: ids
  });
}
