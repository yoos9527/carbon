import { http } from "@/utils/http";

/**
 * 看板聚合数据。
 *
 * 后端一次请求把所有块都算好（`GET /api/stats/overview`），前端不再为每张卡片
 * 各查一个列表接口——那样 6 张卡就是 6 个请求，还拿不到「今日成功率」这种聚合值。
 */

export interface StatsCounts {
  connectors: number;
  operations: number;
  flows: number;
  apps: number;
  jobs: number;
  /** 当前仍标记为 RUNNING 的运行记录数（多实例宕机会留下幽灵行，见后端注释） */
  runningRuns: number;
}

export interface StatsWindow {
  total: number;
  failed: number;
  /** 百分比，97.5 表示 97.5% */
  successRate: number;
  avgMs: number;
  p95Ms: number;
}

export interface StatsTrendPoint {
  /** yyyy-MM-dd */
  date: string;
  total: number;
  failed: number;
  avgMs: number;
}

export interface StatsSourceMix {
  /** CONSOLE 试调 / GW 网关 / FLOW 编排 */
  source: string;
  total: number;
  avgMs: number;
}

export interface StatsTopSlow {
  /** 雪花 id，字符串；调用未落连接器时为空 */
  connectorId?: string | null;
  operationId?: string | null;
  connectorName?: string | null;
  operationName?: string | null;
  calls: number;
  avgMs: number;
  maxMs: number;
}

export interface StatsTopFailed {
  errCode: string;
  calls: number;
}

export interface StatsOverview {
  counts: StatsCounts;
  /** 今日 00:00 起 */
  today: StatsWindow;
  /** 趋势窗口（默认近 7 天）汇总 */
  window: StatsWindow;
  trend: Array<StatsTrendPoint>;
  sourceMix: Array<StatsSourceMix>;
  topSlow: Array<StatsTopSlow>;
  topFailed: Array<StatsTopFailed>;
}

/** @param days 1~30，后端会夹紧，超范围不报错 */
export function getStatsOverview(days = 7) {
  return http.get<StatsOverview>("/stats/overview", { params: { days } });
}
