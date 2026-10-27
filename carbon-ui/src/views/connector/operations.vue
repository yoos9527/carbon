<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRoute } from "vue-router";
import { ElMessage } from "element-plus";
import {
  getConnector,
  pageOperations,
  type Connector,
  type Operation
} from "@/api/connector";
import { http, showError, ApiError } from "@/utils/http";
import { useEnvStoreHook } from "@/store/modules/env";

defineOptions({ name: "ConnectorOperations" });

const route = useRoute();
const connectorId = String(route.params.id);
const envStore = useEnvStoreHook();

const connector = ref<Connector>();
const loading = ref(false);
const rows = ref<Array<Operation>>([]);
const total = ref(0);
const query = reactive({ pageNum: 1, pageSize: 10, keyword: "" });

async function load() {
  loading.value = true;
  try {
    const page = await pageOperations(connectorId, {
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined
    });
    rows.value = page.rows;
    total.value = page.total;
  } catch (e) {
    showError(e);
  } finally {
    loading.value = false;
  }
}

async function loadConnector() {
  try {
    connector.value = await getConnector(connectorId);
  } catch (e) {
    showError(e);
  }
}

/* ---------------- 试调 ---------------- */
const debug = reactive({
  visible: false,
  op: undefined as Operation | undefined,
  input: "{}",
  dryRun: false,
  sending: false,
  result: undefined as unknown,
  error: undefined as ApiError | undefined,
  costMs: 0
});

function openDebug(op: Operation) {
  debug.op = op;
  debug.input = "{}";
  debug.dryRun = false;
  debug.result = undefined;
  debug.error = undefined;
  debug.visible = true;
}

async function send() {
  if (!debug.op || !connector.value) return;
  let input: unknown;
  try {
    input = JSON.parse(debug.input || "{}");
  } catch {
    ElMessage.warning("入参不是合法 JSON");
    return;
  }
  debug.sending = true;
  debug.error = undefined;
  const started = Date.now();
  try {
    debug.result = await http.post(
      `/invoke/${connector.value.code}/${debug.op.code}`,
      {
        input,
        dryRun: debug.dryRun,
        envCode: envStore.current || undefined
      }
    );
  } catch (e) {
    debug.result = undefined;
    debug.error = e instanceof ApiError ? e : undefined;
    showError(e);
  } finally {
    debug.costMs = Date.now() - started;
    debug.sending = false;
  }
}

function pretty(v: unknown) {
  return v == null ? "" : JSON.stringify(v, null, 2);
}

onMounted(() => {
  loadConnector();
  load();
});
</script>

<template>
  <div class="p-4">
    <el-breadcrumb class="mb-3" separator="/">
      <el-breadcrumb-item :to="{ path: '/connectors' }">连接器</el-breadcrumb-item>
      <el-breadcrumb-item>{{ connector?.name || connectorId }}</el-breadcrumb-item>
    </el-breadcrumb>

    <el-descriptions v-if="connector" :column="3" border class="mb-4">
      <el-descriptions-item label="编码">
        <span class="font-mono text-xs">{{ connector.code }}</span>
      </el-descriptions-item>
      <el-descriptions-item label="Base URL">
        <span class="font-mono text-xs">{{ connector.baseUrl }}</span>
      </el-descriptions-item>
      <el-descriptions-item label="鉴权">
        {{ connector.authType }}
      </el-descriptions-item>
    </el-descriptions>

    <div class="mb-3 flex items-center gap-2">
      <el-input
        v-model="query.keyword"
        placeholder="接口名称或编码"
        clearable
        style="width: 220px"
        @keyup.enter="load"
        @clear="load"
      />
      <el-button type="primary" @click="load">查询</el-button>
      <div class="flex-1" />
      <el-alert
        v-if="envStore.current"
        :title="`当前环境：${envStore.current}`"
        type="info"
        :closable="false"
        show-icon
        style="width: 200px"
      />
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="name" label="名称" min-width="150" />
      <el-table-column prop="code" label="编码" min-width="150">
        <template #default="{ row }">
          <span class="font-mono text-xs">{{ row.code }}</span>
        </template>
      </el-table-column>
      <el-table-column label="方法" width="90">
        <template #default="{ row }">
          <el-tag size="small" effect="dark" :type="row.method === 'GET' ? 'success' : 'warning'">
            {{ row.method }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="path" label="路径" min-width="220">
        <template #default="{ row }">
          <span class="font-mono text-xs">{{ row.path }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDebug(row)">试调</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="这个连接器还没有配置接口" />
      </template>
    </el-table>

    <div class="mt-3 flex justify-end">
      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="load"
      />
    </div>

    <el-dialog
      v-model="debug.visible"
      :title="`试调：${debug.op?.name ?? ''}`"
      width="720px"
      top="6vh"
    >
      <el-form label-width="90px">
        <el-form-item label="请求">
          <span class="font-mono text-xs">
            {{ debug.op?.method }} {{ connector?.baseUrl }}{{ debug.op?.path }}
          </span>
        </el-form-item>
        <el-form-item label="入参 JSON">
          <el-input
            v-model="debug.input"
            type="textarea"
            :rows="6"
            class="font-mono"
            placeholder='{"orderId":"B-1","qty":2}'
          />
        </el-form-item>
        <el-form-item label="只渲染">
          <el-switch v-model="debug.dryRun" />
          <span class="ml-2 text-xs text-gray-400">
            开启后不真正请求三方，只返回将要发出的请求（排查模板问题最快）
          </span>
        </el-form-item>
        <el-form-item label="结果">
          <div class="w-full">
            <el-alert
              v-if="debug.error"
              :title="`${debug.error.code} · ${debug.error.message}`"
              :description="debug.error.traceId ? `traceId: ${debug.error.traceId}` : ''"
              type="error"
              :closable="false"
              show-icon
            />
            <pre
              v-else-if="debug.result"
              class="max-h-72 overflow-auto rounded bg-gray-50 p-3 text-xs dark:bg-gray-800"
            >{{ pretty(debug.result) }}</pre>
            <div v-else class="text-xs text-gray-400">还没有发送</div>
            <div v-if="debug.result" class="mt-1 text-xs text-gray-400">
              总耗时 {{ debug.costMs }} ms
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="debug.visible = false">关闭</el-button>
        <el-button type="primary" :loading="debug.sending" @click="send">
          {{ debug.dryRun ? "只渲染" : "发送" }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
