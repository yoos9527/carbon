<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import {
  LOG_SOURCES,
  logsByTraceId,
  pageLogs,
  writerStatus,
  type InvokeLog,
  type WriterStatus
} from "@/api/log";
import {
  pageEndpoints,
  pageOperations,
  type Endpoint,
  type Operation
} from "@/api/endpoint";
import { MAX_PAGE_SIZE, PAGE_SIZES, labelOf, tagOf } from "@/api/common";
import { showError } from "@/utils/http";

defineOptions({ name: "InvokeLogList" });

const route = useRoute();
const router = useRouter();
const loading = ref(false);
const rows = ref<Array<InvokeLog>>([]);
const total = ref(0);

const connectors = ref<Array<Endpoint>>([]);
const connectorNames = ref<Map<string, string>>(new Map());
/** connectorId → (operationId → 名称)，按需加载后缓存 */
const operationCache = new Map<string, Map<string, string>>();
const operationOptions = ref<Array<Operation>>([]);

const writer = ref<WriterStatus | null>(null);

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  connectorId: undefined as string | undefined,
  operationId: undefined as string | undefined,
  source: undefined as string | undefined,
  traceId: "",
  onlyFailed: false,
  timeRange: undefined as [string, string] | undefined
});

async function loadConnectors() {
  try {
    const page = await pageEndpoints({ pageNum: 1, pageSize: MAX_PAGE_SIZE });
    connectors.value = page.rows;
    connectorNames.value = new Map(page.rows.map(c => [c.id, c.name]));
  } catch (e) {
    showError(e);
  }
}

/** 选接入点后才有接口下拉；不选接入点就没法按接口筛（后端要求 operationId 独立生效） */
async function onConnectorChange(id?: string) {
  query.operationId = undefined;
  operationOptions.value = [];
  if (!id) return;
  try {
    const page = await pageOperations(id, {
      pageNum: 1,
      pageSize: MAX_PAGE_SIZE
    });
    operationOptions.value = page.rows;
    operationCache.set(id, new Map(page.rows.map(o => [o.id, o.name])));
  } catch (e) {
    showError(e);
  }
}

/**
 * 列表里要显示「接入点.接口」的名字，但日志表只存 id。
 * 做法：对当前页出现的接入点各拉一次接口列表（去重、并行、失败静默降级成 #id），
 * 结果进缓存，翻页时大部分接入点已经在缓存里了。
 */
async function warmOperationNames(logs: Array<InvokeLog>) {
  const ids = [
    ...new Set(logs.map(l => l.connectorId).filter(Boolean))
  ] as Array<string>;
  const todo = ids.filter(id => !operationCache.has(id));
  if (!todo.length) return;
  await Promise.all(
    todo.map(async id => {
      try {
        const page = await pageOperations(id, {
          pageNum: 1,
          pageSize: MAX_PAGE_SIZE
        });
        operationCache.set(id, new Map(page.rows.map(o => [o.id, o.name])));
      } catch {
        operationCache.set(id, new Map());
      }
    })
  );
}

function connectorName(id?: string) {
  if (!id) return "-";
  return connectorNames.value.get(id) ?? `#${id}`;
}

function operationName(connectorId?: string, operationId?: string) {
  if (!operationId) return "-";
  return (
    operationCache.get(connectorId ?? "")?.get(operationId) ?? `#${operationId}`
  );
}

function target(log: InvokeLog) {
  const c = connectorName(log.connectorId);
  const o = operationName(log.connectorId, log.operationId);
  return `${c}.${o}`;
}

async function load() {
  loading.value = true;
  try {
    const page = await pageLogs({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      connectorId: query.connectorId,
      operationId: query.operationId,
      source: query.source,
      traceId: query.traceId.trim() || undefined,
      onlyFailed: query.onlyFailed || undefined,
      beginTime: query.timeRange?.[0],
      endTime: query.timeRange?.[1]
    });
    rows.value = page.rows;
    total.value = page.total;
    await warmOperationNames(page.rows);
  } catch (e) {
    showError(e);
  } finally {
    loading.value = false;
  }
}

async function loadWriterStatus() {
  try {
    writer.value = await writerStatus();
  } catch {
    // 写入器状态拿不到不影响查日志，静默
  }
}

function search() {
  query.pageNum = 1;
  load();
}

function reset() {
  query.connectorId = undefined;
  query.operationId = undefined;
  query.source = undefined;
  query.traceId = "";
  query.onlyFailed = false;
  query.timeRange = undefined;
  operationOptions.value = [];
  search();
}

/* ---------------- 详情抽屉 ---------------- */
const drawer = reactive({ visible: false, loading: false });
const traceLogs = ref<Array<InvokeLog>>([]);
/** 当前点开的那条，用于把抽屉滚到它那一段 */
const focused = ref<InvokeLog | null>(null);

async function openDetail(row: InvokeLog) {
  focused.value = row;
  drawer.visible = true;
  drawer.loading = true;
  traceLogs.value = [];
  try {
    traceLogs.value = await logsByTraceId(row.traceId);
    await warmOperationNames(traceLogs.value);
  } catch (e) {
    // 退化为只显示点开的那一条
    traceLogs.value = [row];
    showError(e);
  } finally {
    drawer.loading = false;
  }
}

const summary = computed(() => focused.value ?? traceLogs.value[0] ?? null);

async function copy(text?: string) {
  if (!text) return;
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success("已复制");
  } catch {
    ElMessage.warning("复制失败，请手动选中");
  }
}

/** 大 JSON 高亮不做，先自动缩进；解析失败就原文展示 */
function pretty(text?: string) {
  if (!text) return "";
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

onMounted(async () => {
  await loadConnectors();
  loadWriterStatus();
  // 支持从别处带 traceId/connectorId 跳过来，直接查这一条
  if (typeof route.query.traceId === "string")
    query.traceId = route.query.traceId;
  if (typeof route.query.connectorId === "string")
    query.connectorId = route.query.connectorId;
  load();
});
</script>

<template>
  <div class="p-4">
    <el-alert
      v-if="writer && writer.dropped > 0"
      class="mb-3"
      type="error"
      show-icon
      :closable="false"
      :title="`有 ${writer.dropped} 条日志因队列满被丢弃`"
      :description="`写入器：队列 ${writer.queueSize} / 已写 ${writer.written} / 失败 ${writer.failed}。日志丢失意味着调用记录不全，需要调大队列或落库批量。`"
    />

    <div class="mb-3 flex flex-wrap items-center gap-2">
      <el-select
        v-model="query.source"
        placeholder="全部来源"
        clearable
        style="width: 120px"
        @change="search"
      >
        <el-option
          v-for="s in LOG_SOURCES"
          :key="s.value"
          :label="s.label"
          :value="s.value"
        />
      </el-select>
      <el-select
        v-model="query.connectorId"
        placeholder="全部接入点"
        clearable
        filterable
        style="width: 180px"
        @change="onConnectorChange($event as string)"
      >
        <el-option
          v-for="c in connectors"
          :key="c.id"
          :label="c.name"
          :value="c.id"
        />
      </el-select>
      <el-select
        v-model="query.operationId"
        placeholder="全部接口"
        clearable
        filterable
        :disabled="!query.connectorId"
        style="width: 180px"
        @change="search"
      >
        <el-option
          v-for="o in operationOptions"
          :key="o.id"
          :label="o.name"
          :value="o.id"
        />
      </el-select>
      <el-input
        v-model="query.traceId"
        placeholder="traceId"
        clearable
        style="width: 260px"
        @keyup.enter="search"
        @clear="search"
      />
      <el-date-picker
        v-model="query.timeRange"
        type="datetimerange"
        value-format="YYYY-MM-DDTHH:mm:ss"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        style="width: 340px"
        @change="search"
      />
      <el-checkbox v-model="query.onlyFailed" @change="search"
        >只看失败</el-checkbox
      >
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="createdAt" label="时间" width="170" />
      <el-table-column label="来源" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="tagOf(LOG_SOURCES, row.source).type">
            {{ tagOf(LOG_SOURCES, row.source).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="接入点.接口" min-width="180">
        <template #default="{ row }">{{ target(row) }}</template>
      </el-table-column>
      <el-table-column prop="method" label="方法" width="80">
        <template #default="{ row }">
          <span class="font-mono text-xs">{{ row.method || "-" }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态码" width="90">
        <template #default="{ row }">
          <span
            class="font-mono text-xs"
            :class="
              row.respStatus && row.respStatus >= 400 ? 'text-red-500' : ''
            "
          >
            {{ row.respStatus ?? "-" }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="90">
        <template #default="{ row }">{{ row.durationMs ?? "-" }} ms</template>
      </el-table-column>
      <el-table-column label="结果" width="150">
        <template #default="{ row }">
          <el-tag v-if="row.errCode" size="small" type="danger" effect="plain">
            {{ row.errCode }}
          </el-tag>
          <el-tag v-else size="small" type="success" effect="plain"
            >成功</el-tag
          >
        </template>
      </el-table-column>
      <el-table-column label="traceId" min-width="200">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            class="font-mono text-xs"
            @click="copy(row.traceId)"
          >
            {{ row.traceId }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)"
            >详情</el-button
          >
        </template>
      </el-table-column>
      <template #empty>
        <el-empty
          :description="
            query.traceId ||
            query.onlyFailed ||
            query.connectorId ||
            query.timeRange
              ? '筛选无结果，试试重置条件'
              : '还没有调用记录，去「接口与试调」发一次请求'
          "
        />
      </template>
    </el-table>

    <div class="mt-3 flex justify-end">
      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="PAGE_SIZES"
        layout="total, sizes, prev, pager, next"
        @current-change="load"
        @size-change="search"
      />
    </div>

    <el-drawer v-model="drawer.visible" title="调用详情" size="60%">
      <div v-loading="drawer.loading">
        <template v-if="summary">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="traceId">
              <span class="font-mono text-xs">{{ summary.traceId }}</span>
              <el-button
                link
                type="primary"
                class="ml-2"
                @click="copy(summary.traceId)"
              >
                复制
              </el-button>
            </el-descriptions-item>
            <el-descriptions-item label="时间">
              {{ summary.createdAt || "-" }}
            </el-descriptions-item>
            <el-descriptions-item label="来源">
              {{ tagOf(LOG_SOURCES, summary.source).label }}
            </el-descriptions-item>
            <el-descriptions-item label="接入点.接口">
              {{ target(summary) }}
            </el-descriptions-item>
            <el-descriptions-item label="状态码">
              {{ summary.respStatus ?? "-" }}
            </el-descriptions-item>
            <el-descriptions-item label="耗时">
              {{ summary.durationMs ?? "-" }} ms
            </el-descriptions-item>
            <el-descriptions-item label="请求 URL" :span="2">
              <span class="font-mono text-xs">{{
                summary.realUrl || "-"
              }}</span>
            </el-descriptions-item>
            <el-descriptions-item v-if="summary.errCode" label="错误" :span="2">
              <el-tag size="small" type="danger" effect="plain">
                {{ summary.errCode }}
              </el-tag>
              <span class="ml-2">{{ summary.errMsg || "" }}</span>
            </el-descriptions-item>
            <el-descriptions-item
              v-if="summary.flowRunId"
              label="所属编排"
              :span="2"
            >
              <el-button
                link
                type="primary"
                @click="
                  router.push({
                    path: '/flow-runs',
                    query: { runId: summary.flowRunId }
                  })
                "
              >
                查看编排步骤
              </el-button>
            </el-descriptions-item>
          </el-descriptions>

          <div v-if="traceLogs.length > 1" class="mt-3 text-xs text-gray-400">
            同一 traceId 共 {{ traceLogs.length }} 次调用（含重试）
          </div>

          <div v-for="(log, i) in traceLogs" :key="log.id" class="mt-4">
            <div class="mb-1 flex items-center gap-2">
              <el-tag size="small" effect="plain">
                #{{ i + 1 }} {{ labelOf(LOG_SOURCES, log.source) }}
              </el-tag>
              <span class="font-mono text-xs text-gray-500">
                {{ log.method }} {{ log.respStatus ?? "-" }} /
                {{ log.durationMs ?? "-" }}ms
              </span>
              <span class="text-xs text-gray-400">{{ log.createdAt }}</span>
            </div>

            <el-collapse :model-value="['req', 'resp']">
              <el-collapse-item title="请求" name="req">
                <div class="text-xs text-gray-400">URL</div>
                <pre
                  class="mb-2 whitespace-pre-wrap break-all rounded bg-gray-50 p-2 font-mono text-xs"
                  >{{ log.realUrl || "-" }}</pre
                >
                <div class="text-xs text-gray-400">Headers（已脱敏）</div>
                <pre
                  class="mb-2 whitespace-pre-wrap break-all rounded bg-gray-50 p-2 font-mono text-xs"
                  >{{ pretty(log.reqHeadersMasked) || "-" }}</pre
                >
                <div class="text-xs text-gray-400">Body</div>
                <pre
                  class="whitespace-pre-wrap break-all rounded bg-gray-50 p-2 font-mono text-xs"
                  >{{ pretty(log.reqBody) || "-" }}</pre
                >
              </el-collapse-item>
              <el-collapse-item title="响应" name="resp">
                <div class="text-xs text-gray-400">
                  状态码 {{ log.respStatus ?? "-" }}（超过 64KB 会被截断）
                </div>
                <pre
                  class="whitespace-pre-wrap break-all rounded bg-gray-50 p-2 font-mono text-xs"
                  >{{ pretty(log.respBody) || "-" }}</pre
                >
              </el-collapse-item>
            </el-collapse>
          </div>
        </template>
        <el-empty v-else-if="!drawer.loading" description="没有查到这条记录" />
      </div>
    </el-drawer>
  </div>
</template>
