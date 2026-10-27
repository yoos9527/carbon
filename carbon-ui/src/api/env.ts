import { http } from "@/utils/http";

/**
 * 环境（对应后端 EnvVo）。
 *
 * `vars` 是**对象**而不是 JSON 字符串：后端 `EnvVo.of` 已经把 `env.vars` 的 JSON 解开，
 * 直接填回输入框就能用。`code`/`baseUrlPrefix` 不在 `vars` 里也取得到——它们是平台
 * 派生的，渲染时自动注入（写了会被后端 400 拒绝，见保留字段校验）。
 */
export interface Env {
  id: string;
  code: string;
  name: string;
  /** 渲染时拼在 base_url 前；空串表示不拼。后端保证带 scheme 且无尾斜杠 */
  baseUrlPrefix?: string;
  /** `{{env.xxx}}` 的取值来源，值只能是字符串/数字/布尔 */
  vars: Record<string, string | number | boolean>;
  isDefault?: number;
  remark?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** 新建/更新入参：`update` 里 null 表示「不动」，空串表示「清空」 */
export interface EnvForm {
  code: string;
  name: string;
  baseUrlPrefix?: string | null;
  vars?: Record<string, string | number | boolean> | null;
  isDefault?: boolean;
  remark?: string | null;
}

export function listEnvs() {
  return http.get<Array<Env>>("/envs");
}

export function getEnv(id: string) {
  return http.get<Env>(`/envs/${id}`);
}

export function createEnv(data: EnvForm) {
  return http.post<number>("/envs", data);
}

export function updateEnv(id: string, data: EnvForm) {
  return http.put<void>(`/envs/${id}`, data);
}

export function deleteEnvs(ids: Array<string>) {
  return http.delete<void>("/envs", { data: ids });
}

/** 设为唯一默认（无 body） */
export function setDefaultEnv(id: string) {
  return http.put<void>(`/envs/${id}/default`);
}

/** 摊平后的变量视图（含派生的 code / baseUrlPrefix），「变量扫描」用 */
export function getEnvVars(code?: string) {
  return http.get<Record<string, unknown>>("/envs/vars", {
    params: code ? { code } : {}
  });
}
