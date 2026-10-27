import { http, type TableDataInfo } from "@/utils/http";
import type { PageQuery } from "@/api/common";

/**
 * 定时任务（Quartz 投影）。
 *
 * 后端 `JobVo.nextFireTimes` 是**运行时从 Quartz 算出来的**，不是库里的字段——
 * 所以改完 cron 必须重新 GET 一次，不能拿本地旧值渲染。
 */
export interface Job {
  id: string;
  flowId: string;
  name: string;
  /** Quartz 表达式：6 段（秒 分 时 日 月 周），不是 Linux crontab 的 5 段 */
  cron: string;
  /** IANA 时区名，如 Asia/Shanghai */
  timezone: string;
  /** DO_NOTHING / FIRE_ONCE */
  misfirePolicy: string;
  /** ALLOW / DISALLOW */
  concurrency: string;
  /** 作为流程 input 的参数 */
  params?: Record<string, unknown>;
  enabled: number;
  /** 最近三次触发时间（yyyy-MM-dd HH:mm:ss），未启用时为空 */
  nextFireTimes?: Array<string>;
  createdAt?: string;
  updatedAt?: string;
}

export interface JobBo {
  flowId: string;
  name: string;
  cron: string;
  timezone?: string;
  misfirePolicy?: string;
  concurrency?: string;
  params?: Record<string, unknown>;
  enabled?: number;
}

export const MISFIRE_POLICIES = [
  { value: "DO_NOTHING", label: "不补跑（推荐）" },
  { value: "FIRE_ONCE", label: "补跑一次" }
];

export const CONCURRENCIES = [
  { value: "DISALLOW", label: "禁止并发（上轮没跑完则跳过）" },
  { value: "ALLOW", label: "允许并发" }
];

/** 常用 cron 预设：多数用户记不住 Quartz 是 6 段 */
export const CRON_PRESETS = [
  { label: "每 30 秒", value: "0/30 * * * * ?" },
  { label: "每分钟", value: "0 * * * * ?" },
  { label: "每 5 分钟", value: "0 0/5 * * * ?" },
  { label: "每小时", value: "0 0 * * * ?" },
  { label: "每天 2:00", value: "0 0 2 * * ?" },
  { label: "工作日 9:00", value: "0 0 9 ? * MON-FRI" }
];

export function pageJobs(query: PageQuery & { flowId?: string }) {
  return http.get<TableDataInfo<Job>>("/jobs", { params: query });
}

export function getJob(id: string) {
  return http.get<Job>(`/jobs/${id}`);
}

export function createJob(body: JobBo) {
  return http.post<string>("/jobs", body);
}

export function updateJob(id: string, body: JobBo) {
  return http.put<void>(`/jobs/${id}`, body);
}

export function deleteJob(id: string) {
  return http.delete<void>(`/jobs/${id}`);
}

export function enableJob(id: string) {
  return http.post<void>(`/jobs/${id}/enable`);
}

export function disableJob(id: string) {
  return http.post<void>(`/jobs/${id}/disable`);
}

/** 立即执行一次：不改下次触发时间，跑完去「运行记录」看结果 */
export function triggerJob(id: string) {
  return http.post<void>(`/jobs/${id}/trigger`);
}
