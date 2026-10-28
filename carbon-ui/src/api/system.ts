import { http, type TableDataInfo } from "@/utils/http";
import type { PageQuery } from "@/api/common";

/**
 * 操作日志（审计）。
 *
 * 记录由后端 `@Log` 切面写入，只有被注解标注的**写**操作才会出现——
 * 所以这个页面天生不完整，查不到"谁看过某条数据"。
 */
export interface OperLog {
  id: string;
  /** 业务模块，如「流程」「连接器」 */
  title: string;
  businessType?: string;
  /** 后端方法全名，排障时看它 */
  method?: string;
  requestMethod?: string;
  requestUri?: string;
  clientIp?: string;
  userAgent?: string;
  params?: string;
  /** 0 成功 / 1 失败（与 RuoYi 一致） */
  status?: number;
  errorMsg?: string;
  durationMs?: number;
  createdAt?: string;
}

export const BUSINESS_TYPES = [
  { value: "OTHER", label: "其它", type: "info" as const },
  { value: "INSERT", label: "新增", type: "success" as const },
  { value: "UPDATE", label: "修改", type: "warning" as const },
  { value: "DELETE", label: "删除", type: "danger" as const },
  { value: "EXECUTE", label: "执行", type: "primary" as const },
  { value: "EXPORT", label: "导出", type: "info" as const },
  { value: "IMPORT", label: "导入", type: "info" as const },
  { value: "CLEAN", label: "清空", type: "danger" as const }
];

export interface OperLogQuery extends PageQuery {
  title?: string;
  businessType?: string;
  onlyFailed?: boolean;
  keyword?: string;
  /** ISO 本地时间，如 2026-10-27T00:00:00 */
  beginTime?: string;
  endTime?: string;
}

export function pageOperLogs(query: OperLogQuery) {
  return http.get<TableDataInfo<OperLog>>("/oper-logs", { params: query });
}
