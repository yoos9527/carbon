import { http, type TableDataInfo } from "@/utils/http";
import type { PageQuery } from "./endpoint";

/**
 * 合作方：只描述「哪家公司、什么状态、合同号」。
 *
 * 它**不放** URL，也**不放**密钥：
 * 地址在接入点（api/endpoint.ts），密钥在凭证（本文件 Credential）。
 */
export interface Partner {
  id: string;
  code: string;
  name: string;
  /** 0 草稿 / 1 正常 / 2 停用 / 3 禁用 */
  status: number;
  contactName?: string;
  contactPhone?: string;
  contractNo?: string;
  priority?: number;
  remark?: string;
  /** 统计量，由后端列表接口回填 */
  endpointCount?: number;
  operationCount?: number;
  credentialCount?: number;
  defaultCredentialName?: string;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * 凭证：一套密钥。
 *
 * 敏感字段（value/password/token/clientSecret）在服务端加密存储；
 * 读取时只返回 `******` 掩码，提交时若仍是掩码则表示「不修改」。
 */
export interface Credential {
  id: string;
  partnerId: string;
  code: string;
  name: string;
  type: string;
  config?: Record<string, unknown>;
  configMasked?: Record<string, unknown>;
  isDefault?: boolean;
  status?: number;
  effectiveFrom?: string;
  effectiveTo?: string;
  remark?: string;
  createdAt?: string;
  updatedAt?: string;
}

export const CREDENTIAL_TYPES = [
  { value: "none", label: "无鉴权" },
  { value: "api_key", label: "API Key" },
  { value: "basic", label: "Basic 认证" },
  { value: "bearer", label: "Bearer Token" },
  { value: "oauth2_cc", label: "OAuth2 客户端凭证" },
  { value: "login_token", label: "登录换 Token" }
];

export function credentialTypeLabel(type?: string) {
  return CREDENTIAL_TYPES.find(i => i.value === type)?.label ?? type ?? "-";
}

export const PARTNER_STATUS = [
  { value: 1, label: "正常", type: "success" },
  { value: 2, label: "停用", type: "warning" },
  { value: 3, label: "禁用", type: "danger" },
  { value: 0, label: "草稿", type: "info" }
] as const;

export function partnerStatusMeta(status?: number) {
  return PARTNER_STATUS.find(i => i.value === status) ?? PARTNER_STATUS[3];
}

export interface PartnerQuery extends PageQuery {
  keyword?: string;
  status?: number;
}

export function pagePartners(query: PartnerQuery) {
  return http.get<TableDataInfo<Partner>>("/partners", { params: query });
}

export function getPartner(id: string) {
  return http.get<Partner>(`/partners/${id}`);
}

export function createPartner(body: Partial<Partner>) {
  return http.post<string>("/partners", body);
}

export function updatePartner(id: string, body: Partial<Partner>) {
  return http.put<void>(`/partners/${id}`, body);
}

export function deletePartner(id: string) {
  return http.delete<void>(`/partners/${id}`);
}

/** 改状态：返回影响面（旗下接入点/接口/凭证数量），前端据此做二次确认 */
export function updatePartnerStatus(id: string, status: number) {
  return http.put<{
    endpointCount: number;
    operationCount: number;
    credentialCount: number;
    endpointCodes: string[];
  }>(`/partners/${id}/status`, undefined, { params: { status } });
}

/** 下拉选项：只返回正常状态的合作方 */
export function partnerOptions() {
  return http.get<Partner[]>("/partners/options");
}

// ---------------------------------------------------------------- 凭证

export function listCredentials(partnerId: string) {
  return http.get<Credential[]>(`/partners/${partnerId}/credentials`);
}

export function getCredential(partnerId: string, id: string) {
  return http.get<Credential>(`/partners/${partnerId}/credentials/${id}`);
}

export function createCredential(partnerId: string, body: Partial<Credential>) {
  return http.post<string>(`/partners/${partnerId}/credentials`, body);
}

export function updateCredential(
  partnerId: string,
  id: string,
  body: Partial<Credential>
) {
  return http.put<void>(`/partners/${partnerId}/credentials/${id}`, body);
}

export function deleteCredential(partnerId: string, id: string) {
  return http.delete<void>(`/partners/${partnerId}/credentials/${id}`);
}

export function setDefaultCredential(partnerId: string, id: string) {
  return http.put<void>(`/partners/${partnerId}/credentials/${id}/default`);
}

export function copyCredential(partnerId: string, id: string) {
  return http.post<string>(`/partners/${partnerId}/credentials/${id}/copy`);
}

/** 自检：只碰 tokenUrl/loginUrl，不碰业务地址；失败返回 ok=false 而不是抛错 */
export interface CredentialSelfCheck {
  type: string;
  ok: boolean;
  latencyMs: number;
  expiresIn?: number | null;
  headers?: Record<string, string>;
  queryParams?: Record<string, string>;
  error?: string | null;
}

export function testCredential(partnerId: string, id: string) {
  return http.post<CredentialSelfCheck>(
    `/partners/${partnerId}/credentials/${id}/test`
  );
}
