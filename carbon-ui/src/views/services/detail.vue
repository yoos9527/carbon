<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { getConnector, type Connector } from "@/api/connector";
import type { Operation } from "@/api/connector";
import {
  BODY_TYPES,
  METHODS,
  getService,
  invokeService,
  updateService
} from "@/api/service";
import { ApiError, http, showError } from "@/utils/http";
import { useEnvStoreHook } from "@/store/modules/env";

defineOptions({ name: "ServiceDetail" });

const route = useRoute();
const router = useRouter();
const envStore = useEnvStoreHook();
const connectorId = String(route.params.connectorId);
const operationId = String(route.params.operationId);

const activeTab = ref("basic");
const loading = ref(false);
const saving = ref(false);
const connector = ref<Connector>();
const operation = ref<Operation>();

/** JSON 编辑器统一走字符串：格式化/校验都在提交时一次做完，避免每敲一个字就 parse */
const form = reactive({
  name: "",
  code: "",
  method: "GET",
  path: "",
  bodyType: "json",
  bodyTemplate: "",
  queryParams: "",
  headers: "",
  retry: "",
  preScript: "",
  postScript: "",
  scriptTimeoutMs: 2000,
  timeoutMs: undefined as number | undefined,
  status: 0,
  /** 出参契约用表格编辑，比让用户手写 JSON 数组友好得多 */
  responseExtract: [] as Array<{
    name: string;
    jsonpath: string;
    default?: string;
  }>
});

function fill(op: Operation) {
  operation.value = op;
  form.name = op.name ?? "";
  form.code = op.code ?? "";
  form.method = op.method ?? "GET";
  form.path = op.path ?? "";
  form.bodyType = op.bodyType ?? "json";
  form.bodyTemplate = op.bodyTemplate ?? "";
  form.queryParams = op.queryParams
    ? JSON.stringify(op.queryParams, null, 2)
    : "";
  form.headers = op.headers ? JSON.stringify(op.headers, null, 2) : "";
  form.retry = op.retry ? JSON.stringify(op.retry, null, 2) : "";
  form.preScript = op.preScript ?? "";
  form.postScript = op.postScript ?? "";
  form.scriptTimeoutMs = op.scriptTimeoutMs ?? 2000;
  form.timeoutMs = op.timeoutMs ?? undefined;
  form.status = op.status ?? 0;
  form.responseExtract = Array.isArray(op.responseExtract)
    ? (op.responseExtract as Array<Record<string, string>>).map(i => ({
        name: String(i.name ?? ""),
        jsonpath: String(i.jsonpath ?? ""),
        default: i.default == null ? "" : String(i.default)
      }))
    : [];
}

async function load() {
  loading.value = true;
  try {
    const [op, conn] = await Promise.all([
      getService(connectorId, operationId),
      getConnector(connectorId)
    ]);
    connector.value = conn;
    fill(op);
  } catch (e) {
    showError(e);
  } finally {
    loading.value = false;
  }
}

/** 允许留空：留空 = 继承连接器默认，不是「设为 0」 */
function parseJsonObject(text: string, label: string) {
  const trimmed = text.trim();
  if (!trimmed) return undefined;
  try {
    const v = JSON.parse(trimmed);
    if (v === null || typeof v !== "object" || Array.isArray(v)) {
      ElMessage.warning(`${label} 必须是 JSON 对象`);
      return null;
    }
    return v as Record<string, unknown>;
  } catch {
    ElMessage.warning(`${label} 不是合法 JSON`);
    return null;
  }
}

async function save() {
  if (!form.name || !form.code || !form.path) {
    ElMessage.warning("名称、编码、路径必填");
    return;
  }
  const queryParams = parseJsonObject(form.queryParams, "Query 参数");
  const headers = parseJsonObject(form.headers, "Headers");
  const retry = parseJsonObject(form.retry, "重试策略");
  if (queryParams === null || headers === null || retry === null) return;

  // 契约行的完整性：有 name 必须有 jsonpath，否则执行时抽不出值
  const bad = form.responseExtract.find(
    i => (i.name && !i.jsonpath) || (!i.name && i.jsonpath)
  );
  if (bad) {
    ElMessage.warning("出参契约里每一行的「字段名」和「JSONPath」必须成对填写");
    return;
  }
  const named = form.responseExtract
    .filter(i => i.name)
    .map(i => i.name.toLowerCase());
  if (new Set(named).size !== named.length) {
    ElMessage.warning("出参字段名不能重复（下游节点按名取值）");
    return;
  }

  saving.value = true;
  try {
    await updateService(connectorId, operationId, {
      name: form.name,
      code: form.code,
      method: form.method,
      path: form.path,
      bodyType: form.bodyType,
      bodyTemplate: form.bodyTemplate || undefined,
      queryParams,
      headers,
      retry,
      preScript: form.preScript || undefined,
      postScript: form.postScript || undefined,
      scriptTimeoutMs: form.scriptTimeoutMs,
      timeoutMs: form.timeoutMs ?? undefined,
      status: form.status,
      responseExtract: form.responseExtract
        .filter(i => i.name)
        .map(i => ({
          name: i.name,
          jsonpath: i.jsonpath,
          ...(i.default ? { default: i.default } : {})
        }))
    });
    ElMessage.success("已保存");
    await load();
  } catch (e) {
    showError(e);
  } finally {
    saving.value = false;
  }
}

/* ---------------- 试调 ---------------- */
const debug = reactive({
  input: "{}",
  dryRun: false,
  sending: false,
  result: undefined as unknown,
  error: undefined as ApiError | undefined,
  costMs: 0
});

async function send() {
  if (!connector.value || !operation.value) return;
  let input: unknown;
  try {
    input = JSON.parse(debug.input || "{}");
  } catch {
    ElMessage.warning("入参不是合法 JSON");
    return;
  }
  debug.sending = true;
  debug.error = undefined;
  debug.result = undefined;
  const started = Date.now();
  try {
    debug.result = await invokeService(
      connector.value.code,
      operation.value.code,
      {
        input,
        dryRun: debug.dryRun,
        envCode: envStore.current || undefined
      }
    );
  } catch (e) {
    debug.error = e instanceof ApiError ? e : undefined;
    showError(e);
  } finally {
    debug.costMs = Date.now() - started;
    debug.sending = false;
  }
}

/** 按当前契约生成一份入参骨架，省得对着空 JSON 发呆 */
function fillSampleInput() {
  const skeleton: Record<string, string> = {};
  form.responseExtract.forEach(i => {
    if (i.name) skeleton[i.name] = "<示例值>";
  });
  debug.input = JSON.stringify(skeleton, null, 2);
}

function pretty(v: unknown) {
  return v == null ? "" : JSON.stringify(v, null, 2);
}

function addContractRow() {
  form.responseExtract.push({ name: "", jsonpath: "", default: "" });
}

function removeContractRow(index: number) {
  form.responseExtract.splice(index, 1);
}

onMounted(load);
</script>

<template>
  <div v-loading="loading" class="p-4">
    <el-breadcrumb class="mb-3" separator="/">
      <el-breadcrumb-item :to="{ path: '/services' }">
        数据源服务接口
      </el-breadcrumb-item>
      <el-breadcrumb-item>{{
        operation?.name || operationId
      }}</el-breadcrumb-item>
    </el-breadcrumb>

    <el-descriptions v-if="connector" :column="3" border class="mb-4">
      <el-descriptions-item label="所属连接器">
        {{ connector.name }}
        <span class="font-mono text-xs text-gray-400">
          （{{ connector.code }}）
        </span>
      </el-descriptions-item>
      <el-descriptions-item label="Base URL">
        <span class="font-mono text-xs">{{ connector.baseUrl }}</span>
      </el-descriptions-item>
      <el-descriptions-item label="实际请求">
        <span class="font-mono text-xs">
          {{ form.method }} {{ connector.baseUrl }}{{ form.path }}
        </span>
      </el-descriptions-item>
    </el-descriptions>

    <el-tabs v-model="activeTab" type="border-card">
      <!-- 1 基础 -->
      <el-tab-pane label="基础" name="basic">
        <el-form :model="form" label-width="120px" class="max-w-3xl">
          <el-form-item label="接口名称" required>
            <el-input v-model="form.name" />
          </el-form-item>
          <el-form-item label="接口编码" required>
            <el-input v-model="form.code" placeholder="连接器内唯一" />
            <div class="mt-1 text-xs text-gray-400">
              编码被编排节点引用，改动会让已有流程失效
            </div>
          </el-form-item>
          <el-form-item label="方法" required>
            <el-select v-model="form.method" style="width: 160px">
              <el-option v-for="m in METHODS" :key="m" :label="m" :value="m" />
            </el-select>
          </el-form-item>
          <el-form-item label="路径" required>
            <el-input v-model="form.path" placeholder="/company/{{input.name}}" />
          </el-form-item>
          <el-form-item label="状态">
            <el-switch
              v-model="form.status"
              :active-value="1"
              :inactive-value="0"
              active-text="启用"
              inactive-text="草稿"
            />
            <span class="ml-3 text-xs text-gray-400">
              草稿状态的接口不能被编排与网关调用
            </span>
          </el-form-item>
          <el-form-item label="Query 参数">
            <el-input
              v-model="form.queryParams"
              type="textarea"
              :rows="4"
              class="font-mono"
              placeholder='{"key":"{{input.key}}"}'
            />
          </el-form-item>
          <el-form-item label="Headers">
            <el-input
              v-model="form.headers"
              type="textarea"
              :rows="4"
              class="font-mono"
              placeholder='{"X-Trace":"{{ctx.traceId}}"}'
            />
          </el-form-item>
          <el-form-item label="Body 类型">
            <el-select v-model="form.bodyType" style="width: 200px">
              <el-option
                v-for="t in BODY_TYPES"
                :key="t.value"
                :label="t.label"
                :value="t.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="Body 模板">
            <el-input
              v-model="form.bodyTemplate"
              type="textarea"
              :rows="8"
              class="font-mono"
              placeholder='{"name":"{{input.companyName}}"}'
            />
            <div class="mt-1 text-xs text-gray-400">
              JSON 模板：先解析成树再逐叶子替换，保证类型与转义正确（不是字符串拼接）
            </div>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- 2 出参契约 -->
      <el-tab-pane label="出参契约" name="contract">
        <el-alert
          class="mb-3"
          type="info"
          :closable="false"
          show-icon
          title="出参契约是编排的基础"
          description="这里声明的每个字段，会成为下游节点可选的上游出参。不声明就只能靠脚本硬塞，节点出参池里看不到它。"
        />
        <el-table :data="form.responseExtract" border size="small">
          <el-table-column label="字段名" min-width="180">
            <template #default="{ row }">
              <el-input v-model="row.name" size="small" placeholder="companyName" />
            </template>
          </el-table-column>
          <el-table-column label="JSONPath" min-width="220">
            <template #default="{ row }">
              <el-input
                v-model="row.jsonpath"
                size="small"
                class="font-mono"
                placeholder="$.data.name"
              />
            </template>
          </el-table-column>
          <el-table-column label="默认值" min-width="140">
            <template #default="{ row }">
              <el-input v-model="row.default" size="small" placeholder="缺失时使用" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ $index }">
              <el-button
                link
                type="danger"
                size="small"
                @click="removeContractRow($index)"
              >
                删除
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="还没有声明出参字段" />
          </template>
        </el-table>
        <div class="mt-2">
          <el-button size="small" @click="addContractRow">新增一行</el-button>
        </div>
        <el-alert
          class="mt-3"
          type="warning"
          :closable="false"
          show-icon
          title="入参 schema 待后端加列"
          description="入参侧的字段结构与类型（docs/09 §7 的 input_schema）需要先执行 V6 迁移。在那之前，入参靠 Query 参数 / Body 模板里的占位符表达。"
        />
      </el-tab-pane>

      <!-- 3 脚本 -->
      <el-tab-pane label="脚本" name="script">
        <el-alert
          class="mb-3"
          type="info"
          :closable="false"
          show-icon
          title="脚本在模板渲染之前执行"
          description="前置脚本可改 ctx.request / ctx.vars，后置脚本可改 ctx.response / ctx.vars。脚本先于 URL 渲染是有意为之——否则脚本就能改掉请求地址，等于开了后门。"
        />
        <el-form label-width="120px">
          <el-form-item label="前置脚本">
            <el-input
              v-model="form.preScript"
              type="textarea"
              :rows="10"
              class="font-mono"
              placeholder="// ctx.vars.xxx = ..."
            />
          </el-form-item>
          <el-form-item label="后置脚本">
            <el-input
              v-model="form.postScript"
              type="textarea"
              :rows="10"
              class="font-mono"
              placeholder="// ctx.response.body.xxx = ..."
            />
          </el-form-item>
          <el-form-item label="脚本超时(ms)">
            <el-input-number
              v-model="form.scriptTimeoutMs"
              :min="100"
              :step="100"
            />
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- 4 超时与重试 -->
      <el-tab-pane label="超时与重试" name="runtime">
        <el-form label-width="140px" class="max-w-2xl">
          <el-form-item label="接口超时(ms)">
            <el-input-number
              v-model="form.timeoutMs"
              :min="0"
              :step="500"
              :value-on-clear="undefined"
              placeholder="留空继承连接器"
            />
            <span class="ml-3 text-xs text-gray-400">
              留空 = 继承连接器的 {{ connector?.timeoutMs ?? "-" }} ms
            </span>
          </el-form-item>
          <el-form-item label="重试策略(JSON)">
            <el-input
              v-model="form.retry"
              type="textarea"
              :rows="5"
              class="font-mono"
              placeholder='{"maxAttempts":3,"backoffMs":200}'
            />
            <div class="mt-1 text-xs text-gray-400">
              留空 = 继承连接器；只对幂等方法（GET/HEAD）建议开启
            </div>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- 5 试调 -->
      <el-tab-pane label="试调" name="debug">
        <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <div>
            <div class="mb-2 flex items-center gap-2">
              <span class="font-medium">入参 JSON</span>
              <el-button link type="primary" @click="fillSampleInput">
                按契约生成骨架
              </el-button>
              <div class="flex-1" />
              <el-switch v-model="debug.dryRun" active-text="只渲染" />
            </div>
            <el-input
              v-model="debug.input"
              type="textarea"
              :rows="14"
              class="font-mono"
            />
            <div class="mt-2 flex items-center gap-2">
              <el-button
                type="primary"
                :loading="debug.sending"
                @click="send"
              >
                {{ debug.dryRun ? "只渲染请求" : "发送" }}
              </el-button>
              <span v-if="envStore.current" class="text-xs text-gray-400">
                环境：{{ envStore.current }}
              </span>
            </div>
            <div class="mt-2 text-xs text-gray-400">
              「只渲染」不真正请求三方，只回显将要发出的请求——排查模板问题最快的一条路。
            </div>
          </div>
          <div>
            <div class="mb-2 font-medium">结果</div>
            <el-alert
              v-if="debug.error"
              :title="`${debug.error.code} · ${debug.error.message}`"
              :description="
                debug.error.traceId ? `traceId: ${debug.error.traceId}` : ''
              "
              type="error"
              :closable="false"
              show-icon
            />
            <pre
              v-else-if="debug.result"
              class="max-h-[420px] overflow-auto rounded bg-gray-50 p-3 text-xs dark:bg-gray-800"
              >{{ pretty(debug.result) }}</pre
            >
            <div v-else class="text-xs text-gray-400">还没有发送</div>
            <div v-if="debug.result" class="mt-1 text-xs text-gray-400">
              总耗时 {{ debug.costMs }} ms
            </div>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <div class="mt-4 flex items-center gap-2">
      <el-button type="primary" :loading="saving" @click="save">
        保存
      </el-button>
      <el-button @click="router.push('/services')">返回列表</el-button>
    </div>
  </div>
</template>
