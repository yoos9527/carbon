import { http, type TableDataInfo } from "@/utils/http";

/** 连接器（对应后端 ConnectorVo） */
export interface Connector {
  id: string;
  code: string;
  name: string;
  category?: string;
  baseUrl: string;
  defaultHeaders?: Record<string, string>;
  timeoutMs?: number;
  retry?: Record<string, unknown>;
  status: number;
  remark?: string;
  authType: string;
  authConfigMasked?: Record<string, unknown>;
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
  status: number;
}

export interface PageQuery {
  pageNum: number;
  pageSize: number;
  orderByColumn?: string;
  isAsc?: string;
}

export interface ConnectorQuery extends PageQuery {
  keyword?: string;
  category?: string;
  status?: number;
}

export function pageConnectors(query: ConnectorQuery) {
  return http.get<TableDataInfo<Connector>>("/connectors", { params: query });
}

export function getConnector(id: string) {
  return http.get<Connector>(`/connectors/${id}`);
}

export function createConnector(body: Partial<Connector>) {
  return http.post<string>("/connectors", body);
}

export function updateConnector(id: string, body: Partial<Connector>) {
  return http.put<void>(`/connectors/${id}`, body);
}

/** 启停用：后端是 query 参数 status，不是 body */
/**
 * 复制连接器（含鉴权与接口）。
 *
 * 副本一律以**停用**状态落地：它拿着同一套三方密钥，一创建就能被网关调到，
 * 等于「复制一下就多了一个生产入口」。空 body 时由后端推导 `<原编码>-copy`。
 */
export function copyConnector(
  id: string,
  body: { code?: string; name?: string; withOperations?: boolean } = {}
) {
  return http.post<string>(`/connectors/${id}/copy`, body);
}

export function updateConnectorStatus(id: string, status: number) {
  return http.put<void>(`/connectors/${id}/status`, undefined, {
    params: { status }
  });
}

/** 删除：后端接收 body 里的 id 数组 */
export function deleteConnectors(ids: Array<string>) {
  return http.delete<void>("/connectors", { data: ids });
}

export function pageOperations(
  connectorId: string,
  query: PageQuery & { keyword?: string; status?: number }
) {
  return http.get<TableDataInfo<Operation>>(
    `/connectors/${connectorId}/operations`,
    { params: query }
  );
}

export function getOperation(connectorId: string, id: string) {
  return http.get<Operation>(`/connectors/${connectorId}/operations/${id}`);
}

export function createOperation(connectorId: string, body: Partial<Operation>) {
  return http.post<string>(`/connectors/${connectorId}/operations`, body);
}

export function updateOperation(
  connectorId: string,
  id: string,
  body: Partial<Operation>
) {
  return http.put<void>(`/connectors/${connectorId}/operations/${id}`, body);
}

export function deleteOperations(connectorId: string, ids: Array<string>) {
  return http.delete<void>(`/connectors/${connectorId}/operations`, {
    data: ids
  });
}

export const AUTH_TYPES = [
  { value: "none", label: "无" },
  { value: "api_key", label: "API Key" },
  { value: "basic", label: "Basic" },
  { value: "bearer", label: "Bearer" },
  { value: "oauth2_cc", label: "OAuth2 客户端凭证" },
  { value: "login_token", label: "登录换 Token" }
];

export function authTypeLabel(type?: string) {
  return AUTH_TYPES.find(i => i.value === type)?.label ?? type ?? "-";
}
