<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import dayjs from "dayjs";
import { LOG_SOURCES, pageLogs, type InvokeLog } from "@/api/log";
import { loadOperationNameMap } from "@/api/service";
import { credentialTypeLabel, partnerOptions, type Partner } from "@/api/partner";
import { labelOf, tagOf } from "@/api/common";
import { showError } from "@/utils/http";

defineOptions({ name: "CallDetailList" });

const router = useRouter();

const loading = ref(false);
const rows = ref<Array<InvokeLog>>([]);
const total = ref(0);
const nameMap = ref(new Map<string, string>());
const partnerMap = ref(new Map<string, string>());

const query = reactive({
  pageNum: 1,
  pageSize: 20,
  source: "",
  onlyFailed: false,
  range: [] as Array<string>
});

async function load() {
  loading.value = true;
  try {
    const [begin, end] = query.range ?? [];
    const page = await pageLogs({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      source: query.source || undefined,
      onlyFailed: query.onlyFailed || undefined,
      beginTime: begin || undefined,
      endTime: end || undefined
    });
    rows.value = page.rows;
    total.value = page.total;
  } catch (e) {
    showError(e);
  } finally {
    loading.value = false;
  }
}

async function loadNames() {
  try {
    nameMap.value = await loadOperationNameMap();
  } catch {
    // 名称映射只是展示优化：拉不到就退回显示 id，不影响对账
  }
  try {
    const list: Array<Partner> = await partnerOptions();
    const m = new Map<string, string>();
    list.forEach(p => m.set(String(p.id), p.name));
    partnerMap.value = m;
  } catch {
    // 同上：合作方名字不影响对账
  }
}

function partnerName(row: InvokeLog) {
  if (!row.partnerId) return "-";
  return partnerMap.value.get(String(row.partnerId)) ?? `#${row.partnerId}`;
}

function credentialLabel(row: InvokeLog) {
  if (!row.credentialType) return "不鉴权";
  return credentialTypeLabel(row.credentialType);
}

function search() {
  query.pageNum = 1;
  load();
}

function reset() {
  query.source = "";
  query.onlyFailed = false;
  query.range = [];
  search();
}

function serviceName(row: InvokeLog) {
  if (!row.connectorId || !row.operationId) return "-";
  return (
    nameMap.value.get(`${row.connectorId}:${row.operationId}`) ??
    `${row.connectorId}:${row.operationId}`
  );
}

function ok(row: InvokeLog) {
  return !row.errCode && (row.respStatus ?? 0) >= 200 && (row.respStatus ?? 0) < 400;
}

/**
 * 指标只统计**当前页**。
 *
 * 后端没有 `GET /api/logs/stats`，要在前端算全量成功率就得把几十万条日志拉到浏览器里，
 * 那是错的。所以这里明写"当前页"，等后端出聚合接口再换。
 */
const pageStats = computed(() => {
  const list = rows.value;
  const failed = list.filter(r => !ok(r)).length;
  const success = list.length - failed;
  const failedList = list.filter(r => !ok(r));
  const failedAvg = failedList.length
    ? Math.round(
        failedList.reduce((s, r) => s + (r.durationMs ?? 0), 0) /
          failedList.length
      )
    : 0;
  const avg = list.length
    ? Math.round(list.reduce((s, r) => s + (r.durationMs ?? 0), 0) / list.length)
    : 0;
  return {
    total: list.length,
    success,
    failed,
    successRate: list.length ? Math.round((success / list.length) * 1000) / 10 : 0,
    failedAvg,
    avg
  };
});

function openTrace(row: InvokeLog) {
  router.push({ path: "/logs", query: { traceId: row.traceId } });
}

onMounted(async () => {
  await Promise.all([load(), loadNames()]);
});
</script>

<template>
  <div class="p-4">
    <div class="mb-3 grid grid-cols-2 gap-3 md:grid-cols-5">
      <el-card shadow="never">
        <div class="text-xs text-gray-400">当前页调用量</div>
        <div class="text-2xl font-semibold">{{ pageStats.total }}</div>
      </el-card>
      <el-card shadow="never">
        <div class="text-xs text-gray-400">成功率</div>
        <div class="text-2xl font-semibold text-green-600">
          {{ pageStats.successRate }}%
        </div>
      </el-card>
      <el-card shadow="never">
        <div class="text-xs text-gray-400">失败量</div>
        <div class="text-2xl font-semibold text-red-600">
          {{ pageStats.failed }}
        </div>
      </el-card>
      <el-card shadow="never">
        <div class="text-xs text-gray-400">失败平均耗时</div>
        <div class="text-2xl font-semibold text-amber-600">
          {{ pageStats.failedAvg }} ms
        </div>
      </el-card>
      <el-card shadow="never">
        <div class="text-xs text-gray-400">整体平均耗时</div>
        <div class="text-2xl font-semibold">{{ pageStats.avg }} ms</div>
      </el-card>
    </div>

    <el-alert
      class="mb-3"
      type="warning"
      :closable="false"
      show-icon
      title="以上指标只统计当前页"
      description="后端暂无 /api/logs/stats 聚合接口。要看全量成功率需要后端补接口，前端不该把全表拉到浏览器里算。"
    />

    <div class="mb-3 flex flex-wrap items-center gap-2">
      <el-date-picker
        v-model="query.range"
        type="datetimerange"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        value-format="YYYY-MM-DDTHH:mm:ss"
        style="width: 360px"
        @change="search"
      />
      <el-select
        v-model="query.source"
        placeholder="全部来源"
        clearable
        style="width: 140px"
        @change="search"
      >
        <el-option
          v-for="s in LOG_SOURCES"
          :key="s.value"
          :label="s.label"
          :value="s.value"
        />
      </el-select>
      <el-checkbox v-model="query.onlyFailed" @change="search">
        只看失败
      </el-checkbox>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <div class="flex-1" />
      <span class="text-xs text-gray-400">
        日志异步落库，刚发生的调用可能晚几百毫秒才出现
      </span>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column label="调用标识" min-width="180">
        <template #default="{ row }">
          <span class="font-mono text-xs">{{ row.traceId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="数据源服务接口" min-width="220">
        <template #default="{ row }">{{ serviceName(row) }}</template>
      </el-table-column>
      <el-table-column label="合作方" min-width="120">
        <template #default="{ row }">{{ partnerName(row) }}</template>
      </el-table-column>
      <el-table-column label="凭证" width="130">
        <template #default="{ row }">
          <span :class="row.credentialType ? '' : 'text-gray-400'">
            {{ credentialLabel(row) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="tagOf(LOG_SOURCES, row.source).type">
            {{ labelOf(LOG_SOURCES, row.source) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="方法" width="80">
        <template #default="{ row }">
          <span class="font-mono text-xs">{{ row.method || "-" }}</span>
        </template>
      </el-table-column>
      <el-table-column label="返回结果" width="100">
        <template #default="{ row }">
          <span
            class="font-mono text-xs"
            :class="ok(row) ? 'text-green-600' : 'text-red-600'"
          >
            {{ row.respStatus ?? "-" }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="查询结果" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="ok(row) ? 'success' : 'danger'">
            {{ ok(row) ? "成功" : "失败" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="durationMs" label="耗时(ms)" width="100" />
      <el-table-column label="请求时间" width="180">
        <template #default="{ row }">
          {{ row.createdAt ? dayjs(row.createdAt).format("MM-DD HH:mm:ss") : "-" }}
        </template>
      </el-table-column>
      <el-table-column label="失败原因" min-width="180">
        <template #default="{ row }">
          <span v-if="row.errCode || row.errMsg" class="text-xs text-red-600">
            {{ row.errCode }} {{ row.errMsg }}
          </span>
          <span v-else class="text-xs text-gray-400">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openTrace(row)">
            查报文
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="这段时间没有调用记录" />
      </template>
    </el-table>

    <div class="mt-3 flex justify-end">
      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @current-change="load"
        @size-change="search"
      />
    </div>
  </div>
</template>
