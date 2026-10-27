<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import dayjs from "dayjs";
import {
  RUN_STATUS,
  TRIGGER_TYPES,
  getFlowRun,
  loadFlowNameMap,
  pageFlowRuns,
  pageFlows,
  type Flow,
  type FlowRun,
  type FlowStepRun
} from "@/api/flow";
import { MAX_PAGE_SIZE, PAGE_SIZES, labelOf, tagOf } from "@/api/common";
import { pageConnectors, pageOperations } from "@/api/connector";
import { showError } from "@/utils/http";

defineOptions({ name: "FlowRunList" });

const route = useRoute();
const router = useRouter();
const loading = ref(false);
const rows = ref<Array<FlowRun>>([]);
const total = ref(0);

const flows = ref<Array<Flow>>([]);
const flowNames = ref<Map<string, string>>(new Map());

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  flowId: undefined as string | undefined,
  status: undefined as string | undefined
});

async function loadFlows() {
  try {
    const page = await pageFlows({ pageNum: 1, pageSize: MAX_PAGE_SIZE });
    flows.value = page.rows;
    flowNames.value = new Map(page.rows.map(f => [f.id, f.name]));
  } catch (e) {
    showError(e);
  }
}

async function load() {
  loading.value = true;
  try {
    const page = await pageFlowRuns({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      flowId: query.flowId,
      status: query.status
    });
    rows.value = page.rows;
    total.value = page.total;
    const missing = page.rows
      .map(r => r.flowId)
      .filter(id => !flowNames.value.has(id));
    if (missing.length) flowNames.value = await loadFlowNameMap();
  } catch (e) {
    showError(e);
  } finally {
    loading.value = false;
  }
}

function search() {
  query.pageNum = 1;
  load();
}

function reset() {
  query.flowId = undefined;
  query.status = undefined;
  search();
}

function flowName(id?: string) {
  if (!id) return "-";
  return flowNames.value.get(id) ?? `#${id}`;
}

/** 耗时只能前端算：后端存的是两个时间戳，没存差值 */
function cost(row: FlowRun) {
  if (!row.startedAt) return "-";
  const end = row.finishedAt ? dayjs(row.finishedAt) : dayjs();
  const ms = end.diff(dayjs(row.startedAt));
  if (row.status === "RUNNING") return `运行中 ${(ms / 1000).toFixed(1)}s`;
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
}

/* ---------------- 详情抽屉 ---------------- */
const drawer = reactive({ visible: false, loading: false });
const detail = ref<{ run: FlowRun; steps: Array<FlowStepRun> } | null>(null);

async function openDetail(id: string) {
  drawer.visible = true;
  drawer.loading = true;
  detail.value = null;
  try {
    detail.value = await getFlowRun(id);
    await warmStepNames(detail.value.steps);
  } catch (e) {
    showError(e);
  } finally {
    drawer.loading = false;
  }
}

/** 步骤里存的是 connectorId/operationId，名字得自己查（接口列表已按连接器缓存） */
const operationCache = new Map<string, Map<string, string>>();
const connectorNames = ref<Map<string, string>>(new Map());

async function warmStepNames(steps: Array<FlowStepRun>) {
  const ids = [
    ...new Set(steps.map(s => s.connectorId).filter(Boolean))
  ] as Array<string>;
  const todo = ids.filter(id => !operationCache.has(id));
  if (!todo.length) return;
  try {
    const page = await pageConnectors({ pageNum: 1, pageSize: MAX_PAGE_SIZE });
    connectorNames.value = new Map(page.rows.map(c => [c.id, c.name]));
  } catch {
    // 名字查询失败不影响步骤本身的可读性
  }
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

function stepTarget(step: FlowStepRun) {
  if (!step.connectorId) return "-";
  const c =
    connectorNames.value.get(step.connectorId) ?? `#${step.connectorId}`;
  if (!step.operationId) return c;
  const o =
    operationCache.get(step.connectorId)?.get(step.operationId) ??
    `#${step.operationId}`;
  return `${c}.${o}`;
}

function pretty(text?: string) {
  if (!text) return "";
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

async function copy(text?: string) {
  if (!text) return;
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success("已复制");
  } catch {
    ElMessage.warning("复制失败，请手动选中");
  }
}

const failedSteps = computed(
  () => detail.value?.steps.filter(s => s.status !== "SUCCESS").length ?? 0
);

onMounted(async () => {
  if (typeof route.query.flowId === "string") query.flowId = route.query.flowId;
  if (typeof route.query.status === "string") query.status = route.query.status;
  await loadFlows();
  await load();
  // 深链：/flow/runs?runId=xxx 直接打开详情（日志页「查看编排步骤」用这个）
  if (typeof route.query.runId === "string") openDetail(route.query.runId);
});
</script>

<template>
  <div class="p-4">
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <el-select
        v-model="query.flowId"
        placeholder="全部流程"
        clearable
        filterable
        style="width: 220px"
        @change="search"
      >
        <el-option
          v-for="f in flows"
          :key="f.id"
          :label="f.name"
          :value="f.id"
        />
      </el-select>
      <el-select
        v-model="query.status"
        placeholder="全部状态"
        clearable
        style="width: 130px"
        @change="search"
      >
        <el-option
          v-for="s in RUN_STATUS"
          :key="s.value"
          :label="s.label"
          :value="s.value"
        />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <div class="flex-1" />
      <el-button @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="startedAt" label="开始时间" width="170" />
      <el-table-column label="流程" min-width="160">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            @click="
              router.push({ path: '/flow/runs', query: { flowId: row.flowId } })
            "
          >
            {{ flowName(row.flowId) }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="版本" width="80">
        <template #default="{ row }">{{
          row.version ? `v${row.version}` : "-"
        }}</template>
      </el-table-column>
      <el-table-column label="触发" width="110">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">
            {{ labelOf(TRIGGER_TYPES, row.triggerType) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="触发者" min-width="140">
        <template #default="{ row }">
          <span class="text-xs">{{ row.triggerBy || "-" }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="tagOf(RUN_STATUS, row.status).type">
            {{ tagOf(RUN_STATUS, row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="120">
        <template #default="{ row }">{{ cost(row) }}</template>
      </el-table-column>
      <el-table-column label="traceId" min-width="180">
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
          <el-button link type="primary" @click="openDetail(row.id)"
            >详情</el-button
          >
        </template>
      </el-table-column>
      <template #empty>
        <el-empty
          :description="
            query.flowId || query.status
              ? '筛选无结果，试试重置条件'
              : '还没有运行记录，去画布页试跑一次或等定时任务触发'
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

    <el-drawer v-model="drawer.visible" title="运行详情" size="65%">
      <div v-loading="drawer.loading">
        <template v-if="detail">
          <el-alert
            v-if="detail.run.errorMsg"
            class="mb-3"
            type="error"
            show-icon
            :closable="false"
            :title="detail.run.errorMsg"
          />
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="流程">
              {{ flowName(detail.run.flowId) }}
            </el-descriptions-item>
            <el-descriptions-item label="版本">
              {{ detail.run.version ? `v${detail.run.version}` : "-" }}
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag
                size="small"
                :type="tagOf(RUN_STATUS, detail.run.status).type"
              >
                {{ tagOf(RUN_STATUS, detail.run.status).label }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="触发">
              {{ labelOf(TRIGGER_TYPES, detail.run.triggerType) }}
              {{ detail.run.triggerBy ? `· ${detail.run.triggerBy}` : "" }}
            </el-descriptions-item>
            <el-descriptions-item label="开始">
              {{ detail.run.startedAt || "-" }}
            </el-descriptions-item>
            <el-descriptions-item label="结束">
              {{ detail.run.finishedAt || "-" }}
            </el-descriptions-item>
            <el-descriptions-item label="traceId" :span="3">
              <span class="font-mono text-xs">{{ detail.run.traceId }}</span>
              <el-button
                link
                type="primary"
                class="ml-2"
                @click="copy(detail.run.traceId)"
              >
                复制
              </el-button>
            </el-descriptions-item>
          </el-descriptions>

          <div class="mt-4 mb-1 flex items-center gap-2">
            <span class="font-medium">节点执行</span>
            <span class="text-xs text-gray-400">
              共 {{ detail.steps.length }} 步
              <template v-if="failedSteps"
                >·
                <span class="text-red-500"
                  >{{ failedSteps }} 步失败</span
                ></template
              >
            </span>
          </div>
          <el-table :data="detail.steps" border size="small">
            <el-table-column prop="execIndex" label="#" width="60" />
            <el-table-column prop="nodeId" label="节点" min-width="120">
              <template #default="{ row }">
                <span class="font-mono text-xs">{{ row.nodeId }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="nodeType" label="类型" width="130">
              <template #default="{ row }">
                <span class="font-mono text-xs">{{ row.nodeType }}</span>
              </template>
            </el-table-column>
            <el-table-column label="连接器.接口" min-width="160">
              <template #default="{ row }">{{ stepTarget(row) }}</template>
            </el-table-column>
            <el-table-column label="重试" width="70">
              <template #default="{ row }">{{ row.attempt ?? "-" }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag
                  size="small"
                  :type="row.status === 'SUCCESS' ? 'success' : 'danger'"
                  effect="plain"
                >
                  {{ row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="耗时" width="90">
              <template #default="{ row }"
                >{{ row.durationMs ?? "-" }} ms</template
              >
            </el-table-column>
            <el-table-column label="详情" min-width="240">
              <template #default="{ row }">
                <el-popover
                  v-if="
                    row.requestSummary || row.responseSummary || row.errorMsg
                  "
                  placement="left"
                  :width="520"
                  trigger="click"
                >
                  <template #reference>
                    <el-button link type="primary">查看</el-button>
                  </template>
                  <div class="max-h-[400px] overflow-auto">
                    <div class="text-xs text-gray-400">请求</div>
                    <pre
                      class="mb-2 whitespace-pre-wrap break-all rounded bg-gray-50 p-2 font-mono text-xs"
                      >{{ pretty(row.requestSummary) || "-" }}</pre
                    >
                    <div class="text-xs text-gray-400">响应</div>
                    <pre
                      class="whitespace-pre-wrap break-all rounded bg-gray-50 p-2 font-mono text-xs"
                      >{{ pretty(row.responseSummary) || "-" }}</pre
                    >
                    <template v-if="row.errorMsg">
                      <div class="mt-2 text-xs text-red-500">
                        {{ row.errorMsg }}
                      </div>
                    </template>
                  </div>
                </el-popover>
                <span v-else class="text-xs text-gray-400">-</span>
              </template>
            </el-table-column>
          </el-table>

          <el-collapse class="mt-4">
            <el-collapse-item title="运行入参（input）" name="input">
              <pre
                class="whitespace-pre-wrap break-all rounded bg-gray-50 p-2 font-mono text-xs"
                >{{ pretty(detail.run.inputJson) || "-" }}</pre
              >
            </el-collapse-item>
            <el-collapse-item title="运行出参（output）" name="output">
              <pre
                class="whitespace-pre-wrap break-all rounded bg-gray-50 p-2 font-mono text-xs"
                >{{ pretty(detail.run.outputJson) || "-" }}</pre
              >
            </el-collapse-item>
          </el-collapse>
        </template>
        <el-empty v-else-if="!drawer.loading" description="运行记录不存在" />
      </div>
    </el-drawer>
  </div>
</template>
