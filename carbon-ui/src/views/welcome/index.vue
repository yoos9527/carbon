<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import dayjs from "dayjs";
import echarts from "@/plugins/echarts";
import { getStatsOverview, type StatsOverview } from "@/api/stats";
import { LOG_SOURCES } from "@/api/log";
import { labelOf } from "@/api/common";
import { showError } from "@/utils/http";

defineOptions({ name: "Welcome" });

const router = useRouter();
const loading = ref(false);
const data = ref<StatsOverview | null>(null);
const days = ref(7);
const loadedAt = ref<string>("");

const RANGES = [
  { value: 1, label: "今天" },
  { value: 7, label: "近 7 天" },
  { value: 30, label: "近 30 天" }
];

async function load() {
  loading.value = true;
  try {
    data.value = await getStatsOverview(days.value);
    loadedAt.value = dayjs().format("HH:mm:ss");
    // 数据到家后再画，避免 setOption 拿到空数组后还要再刷一次
    renderTrend();
    renderSource();
  } catch (e) {
    showError(e);
  } finally {
    loading.value = false;
  }
}

/* ---------------- 卡片 ---------------- */

const kpis = computed(() => {
  if (!data.value) return [];
  const { today, window } = data.value;
  const up = window.total - today.total;
  return [
    {
      key: "today",
      label: "今日调用",
      value: today.total.toLocaleString(),
      hint: `失败 ${today.failed} 次`
    },
    {
      key: "rate",
      label: "今日成功率",
      value: `${today.successRate}%`,
      hint: today.total === 0 ? "今日还没有调用" : `${today.failed} 次失败`
    },
    {
      key: "p95",
      label: "今日 P95",
      value: today.p95Ms === 0 ? "-" : `${today.p95Ms} ms`,
      hint: `平均 ${today.avgMs} ms`
    },
    {
      key: "window",
      label: `近 ${days.value} 天调用`,
      value: window.total.toLocaleString(),
      hint: up > 0 ? `今日之外 ${up} 次` : "含今日"
    }
  ];
});

const counts = computed(() => {
  const c = data.value?.counts;
  if (!c) return [];
  return [
    // 没有 `to` 的卡片不可点：应用页还没做，别给一个跳 404 的入口
    {
      key: "connectors",
      label: "连接器",
      value: c.connectors,
      to: "/connectors"
    },
    {
      key: "operations",
      label: "接口",
      value: c.operations,
      to: "/connectors"
    },
    { key: "flows", label: "流程", value: c.flows, to: "/flow/runs" },
    { key: "apps", label: "应用", value: c.apps },
    { key: "jobs", label: "调度任务", value: c.jobs, to: "/jobs" },
    { key: "runs", label: "运行中", value: c.runningRuns, to: "/flow/runs" }
  ];
});

/* ---------------- 图表 ---------------- */

const trendRef = ref<HTMLDivElement>();
const sourceRef = ref<HTMLDivElement>();
let trendChart: echarts.ECharts | null = null;
let sourceChart: echarts.ECharts | null = null;

function renderTrend() {
  const points = data.value?.trend ?? [];
  if (!trendRef.value) return;
  trendChart ??= echarts.init(trendRef.value);
  trendChart.setOption(
    {
      grid: { top: 30, left: 45, right: 20, bottom: 30 },
      tooltip: { trigger: "axis" },
      legend: { data: ["调用", "失败"], right: 0, top: 0 },
      xAxis: {
        type: "category",
        boundaryGap: false,
        data: points.map(p => p.date.slice(5))
      },
      yAxis: [
        {
          type: "value",
          minInterval: 1,
          splitLine: { lineStyle: { type: "dashed" } }
        },
        {
          type: "value",
          name: "平均耗时(ms)",
          minInterval: 1,
          splitLine: { show: false }
        }
      ],
      series: [
        {
          name: "调用",
          type: "line",
          smooth: true,
          areaStyle: { opacity: 0.15 },
          data: points.map(p => p.total)
        },
        {
          name: "失败",
          type: "line",
          smooth: true,
          data: points.map(p => p.failed)
        },
        {
          name: "平均耗时",
          type: "line",
          yAxisIndex: 1,
          smooth: true,
          lineStyle: { type: "dashed" },
          symbol: "none",
          data: points.map(p => p.avgMs)
        }
      ]
    },
    true
  );
}

function renderSource() {
  const list = data.value?.sourceMix ?? [];
  if (!sourceRef.value) return;
  sourceChart ??= echarts.init(sourceRef.value);
  sourceChart.setOption(
    {
      tooltip: { trigger: "item", formatter: "{b}: {c} ({d}%)" },
      legend: { bottom: 0 },
      series: [
        {
          type: "pie",
          radius: ["45%", "70%"],
          avoidLabelOverlap: true,
          label: { formatter: "{b} {d}%" },
          data: list.map(item => ({
            name: labelOf(LOG_SOURCES, item.source),
            value: item.total
          }))
        }
      ]
    },
    true
  );
}

function onResize() {
  trendChart?.resize();
  sourceChart?.resize();
}

/* ---------------- 交互 ---------------- */

/** 慢接口点进去看这批调用的日志：日志页支持 connectorId 过滤 */
function openConnectorLogs(connectorId?: string | null) {
  if (!connectorId) return;
  router.push({ path: "/logs", query: { connectorId } });
}

function formatMs(ms: number) {
  return ms >= 1000 ? `${(ms / 1000).toFixed(2)} s` : `${ms} ms`;
}

watch(days, load);

onMounted(() => {
  load();
  window.addEventListener("resize", onResize);
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", onResize);
  trendChart?.dispose();
  sourceChart?.dispose();
  trendChart = null;
  sourceChart = null;
});
</script>

<template>
  <div v-loading="loading" class="p-4">
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <span class="text-base font-medium">平台概览</span>
      <el-radio-group v-model="days" size="small">
        <el-radio-button v-for="r in RANGES" :key="r.value" :value="r.value">
          {{ r.label }}
        </el-radio-button>
      </el-radio-group>
      <span v-if="loadedAt" class="text-xs text-gray-400">
        更新于 {{ loadedAt }}
      </span>
      <div class="flex-1" />
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
    </div>

    <!-- KPI -->
    <div class="mb-3 grid grid-cols-2 gap-3 lg:grid-cols-4">
      <el-card v-for="k in kpis" :key="k.key" shadow="never" body-class="py-3">
        <div class="text-xs text-gray-500">{{ k.label }}</div>
        <div class="mt-1 text-2xl font-semibold">{{ k.value }}</div>
        <div class="mt-1 text-xs text-gray-400">{{ k.hint }}</div>
      </el-card>
    </div>

    <!-- 资源计数 -->
    <div class="mb-3 grid grid-cols-3 gap-3 lg:grid-cols-6">
      <el-card
        v-for="c in counts"
        :key="c.key"
        shadow="never"
        body-class="py-2 px-3"
        :class="c.to ? 'cursor-pointer hover:border-blue-400' : ''"
        @click="c.to && router.push(c.to)"
      >
        <div class="text-xs text-gray-500">{{ c.label }}</div>
        <div class="text-lg font-medium">{{ c.value }}</div>
      </el-card>
    </div>

    <!-- 趋势 + 来源 -->
    <div class="mb-3 grid grid-cols-1 gap-3 lg:grid-cols-3">
      <el-card shadow="never" class="lg:col-span-2">
        <template #header>
          <span class="text-sm">调用趋势</span>
          <span class="ml-2 text-xs text-gray-400">
            失败判定：errCode 非空（三方 4xx/5xx、超时、脚本报错）
          </span>
        </template>
        <div ref="trendRef" class="h-[260px] w-full" />
      </el-card>
      <el-card shadow="never">
        <template #header><span class="text-sm">调用来源</span></template>
        <div
          v-if="data?.sourceMix?.length"
          ref="sourceRef"
          class="h-[260px] w-full"
        />
        <el-empty v-else :image-size="60" description="窗口内没有调用" />
      </el-card>
    </div>

    <!-- 慢接口 + 错误码 -->
    <div class="grid grid-cols-1 gap-3 lg:grid-cols-2">
      <el-card shadow="never">
        <template #header>
          <span class="text-sm">慢接口 TOP5</span>
          <span class="ml-2 text-xs text-gray-400"
            >按平均耗时排序，点击行看日志</span
          >
        </template>
        <el-table
          v-if="data?.topSlow?.length"
          :data="data.topSlow"
          size="small"
          border
          @row-click="row => openConnectorLogs(row.connectorId)"
        >
          <el-table-column label="接口" min-width="180">
            <template #default="{ row }">
              {{ row.connectorName || "-" }}
              <span class="text-gray-400">
                {{ row.operationName ? `.${row.operationName}` : "" }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="calls" label="调用" width="80" align="right" />
          <el-table-column label="平均" width="100" align="right">
            <template #default="{ row }">{{ formatMs(row.avgMs) }}</template>
          </el-table-column>
          <el-table-column label="最慢" width="100" align="right">
            <template #default="{ row }">{{ formatMs(row.maxMs) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else :image-size="60" description="窗口内没有调用" />
      </el-card>

      <el-card shadow="never">
        <template #header>
          <span class="text-sm">错误码 TOP5</span>
          <span class="ml-2 text-xs text-gray-400">
            去日志页按 traceId 追具体报文
          </span>
        </template>
        <el-table
          v-if="data?.topFailed?.length"
          :data="data.topFailed"
          size="small"
          border
        >
          <el-table-column label="错误码" min-width="180">
            <template #default="{ row }">
              <el-tag size="small" type="danger">{{ row.errCode }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="calls" label="次数" width="80" align="right" />
        </el-table>
        <el-empty v-else :image-size="60" description="窗口内没有失败调用" />
      </el-card>
    </div>
  </div>
</template>
