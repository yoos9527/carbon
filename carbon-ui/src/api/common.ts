/**
 * 各 API 模块共用的小类型与文案映射。
 *
 * 放这里而不是各文件各写一份的理由：枚举值（运行状态、触发方式、日志来源）
 * 必须与后端常量**逐字一致**，散落三份早晚会出现一处漂移，
 * 而漂移的表现是「标签显示成英文枚举」这种不报错但很难看的问题。
 */

/** 分页请求（对应后端 PageQuery；pageSize 后端上限 200） */
export interface PageQuery {
  pageNum: number;
  pageSize: number;
  orderByColumn?: string;
  isAsc?: "asc" | "desc";
}

/** 后端 PageQuery.MAX_PAGE_SIZE */
export const MAX_PAGE_SIZE = 200;

/** 表格分页大小选项（都 ≤ 200） */
export const PAGE_SIZES = [10, 20, 50, 100];

export function labelOf(
  dict: ReadonlyArray<{ value: string; label: string }>,
  value?: string | null
) {
  if (!value) return "-";
  return dict.find(i => i.value === value)?.label ?? value;
}

/** el-tag 的 type（Element Plus 的四种语义色 + info） */
export type TagType = "success" | "warning" | "danger" | "info" | "primary";

export function tagOf(
  dict: ReadonlyArray<{ value: string; label: string; type: TagType }>,
  value?: string | null
): { label: string; type: TagType } {
  const hit = dict.find(i => i.value === value);
  return hit
    ? { label: hit.label, type: hit.type }
    : { label: value || "-", type: "info" };
}
