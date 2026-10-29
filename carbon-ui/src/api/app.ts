import { http } from "@/utils/http";

/**
 * 调用方应用（对应后端 `IAppService.toView`）。
 *
 * 三条与后端约定相关的注意点：
 * 1. **没有 `appSecret`**：明文只在创建与重置密钥的响应里出现一次，列表永远只有 `secretSet`；
 * 2. `allowedConnectors` / `ipWhitelist` 里的 id 是**字符串**（雪花 id 已序列化成字符串），
 *    空数组的语义是「不限制」，不是「全部禁止」；
 * 3. 没有单查详情：一个应用的详情就是列表里那一行，凭据类信息不会再返回第二次。
 */
export interface AppItem {
  id: string;
  name: string;
  /** 调用方放进 `X-App-Key` 头 */
  appKey: string;
  /** 是否已设置密钥；密钥本身不可回读 */
  secretSet: boolean;
  /** 允许调用的接入点 id；空 = 不限制 */
  allowedConnectors: Array<string>;
  rateLimitQps: number;
  /** IP / CIDR 白名单；空 = 不限制 */
  ipWhitelist: Array<string>;
  status: number;
  createdAt?: string;
  updatedAt?: string;
}

/** 创建/重置密钥的响应：多出一次性的明文 secret */
export interface AppSecretView extends AppItem {
  appSecret: string;
  notice?: string;
}

/** 新建入参 */
export interface AppCreateForm {
  name: string;
  allowedConnectors?: Array<string>;
  rateLimitQps?: number;
  ipWhitelist?: Array<string>;
}

/**
 * 更新入参：字段缺省 = 不动，数组传 `[]` = 清空（不限制）。
 *
 * 刻意不含 `appKey` 与密钥：前者被调用方写进了配置与日志检索里，平台侧「顺手」换掉
 * 只会让接入方半夜排查 401；后者只能走重置密钥。
 */
export interface AppUpdateForm {
  name: string;
  allowedConnectors?: Array<string>;
  rateLimitQps?: number;
  ipWhitelist?: Array<string>;
}

export function listApps() {
  return http.get<Array<AppItem>>("/apps");
}

export function createApp(data: AppCreateForm) {
  return http.post<AppSecretView>("/apps", data);
}

export function updateApp(id: string, data: AppUpdateForm) {
  return http.put<void>(`/apps/${id}`, data);
}

/** 重新生成密钥：旧密钥立即失效，明文只此一次 */
export function rotateAppSecret(id: string) {
  return http.put<AppSecretView>(`/apps/${id}/secret`);
}

export function updateAppStatus(id: string, status: number) {
  return http.put<void>(`/apps/${id}/status`, { status });
}

export function deleteApp(id: string) {
  return http.delete<void>(`/apps/${id}`);
}
