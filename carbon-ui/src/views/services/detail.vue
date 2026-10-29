<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { getEndpoint, type Endpoint } from "@/api/endpoint";
import type { Operation } from "@/api/endpoint";
import { listCredentials, type Credential } from "@/api/partner";
import {
  BODY_TYPES,
  METHODS,
  getService,
  invokeService,
  updateService
} from "@/api/service";
import { ApiError, showError } from "@/utils/http";
import { useEnvStoreHook } from "@/store/modules/env";

defineOptions({ name: "ServiceDetail" });

const route = useRoute();
const router = useRouter();
const envStore = useEnvStoreHook();
const connectorId = String(route.params.connectorId);
const operationId = String(route.params.operationId);

const loading = ref(false);
const saving = ref(false);
const dirty = ref(false);
const connector = ref<Endpoint>();
const operation = ref<Operation>();
const credentials = ref<Array<Credential>>([]);

/**
 * 分步而不是平铺 Tab：一屏只看一件事，改完走到下一步，回来也知道自己停在哪。
 * 步骤是所有字段的子集分组，不改变后端契约。
 */
const STEPS = [
  { key: "basic", title: "基础配置", hint: "名称、标识、请求地址" },
  { key: "contract", title: "出入参契约", hint: "下游可用的出参字段" },
  { key: "script", title: "脚本处理", hint: "前后置 Groovy" },
  { key: "credential", title: "凭证", hint: "继承 / 指定 / 不鉴权" },
  { key: "runtime", title: "超时与重试", hint: "留空继承接入点" },
  { key: "debug", title: "试调", hint: "只渲染 / 真发一次" }
] as const;
const step = ref(0);
const advanced = ref(false);

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
  /** 凭证三选一：接口这一层只决定「用哪一条」，密钥本身在合作方 */
  credentialMode: "INHERIT",
  credentialId: "" as string,
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
  form.credentialMode = op.credentialMode || "INHERIT";
  form.credentialId = op.credentialId || "";
  form.responseExtract = Array.isArray(op.responseExtract)
    ? (op.responseExtract as Array<Record<string, string>>).map(i => ({
        name: String(i.name ?? ""),
        jsonpath: String(i.jsonpath ?? ""),
        default: i.default == null ? "" : String(i.default)
      }))
    : [];
  // fill 会触发深层 watch，标记要在下一拍清掉，否则一进来就显示「未保存」
  nextTick(() => (dirty.value = false));
}

async function load() {
  loading.value = true;
  try {
    const [op, conn] = await Promise.all([
      getService(connectorId, operationId),
      getEndpoint(connectorId)
    ]);
    connector.value = conn;
    fill(op);
    // 凭证下拉只需要同一个合作方下的那几条
    if (conn.partnerId) {
      credentials.value = await listCredentials(conn.partnerId);
    }
  } catch (e) {
    showError(e);
  } finally {
    loading.value = false;
  }
}

/** 允许留空：留空 = 继承接入点默认，不是「设为 0」 */
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
    step.value = 0;
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
    ElMessage.warning("出参契约里每一行的「字段标识」和「JSONPath」必须成对填写");
    step.value = 1;
    return;
  }
  const named = form.responseExtract
    .filter(i => i.name)
    .map(i => i.name.toLowerCase());
  if (new Set(named).size !== named.length) {
    ElMessage.warning("出参字段名不能重复（下游节点按名取值）");
    step.value = 1;
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
      credentialMode: form.credentialMode,
      credentialId:
        form.credentialMode === "OVERRIDE" ? form.credentialId : undefined,
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

const contractCount = computed(
  () => form.responseExtract.filter(i => i.name).length
);
const advancedCount = computed(
  () =>
    [form.queryParams, form.headers, form.bodyTemplate].filter(s => s.trim())
      .length
);
const actualUrl = computed(
  () => `${form.method} ${connector.value?.baseUrl ?? ""}${form.path}`
);

watch(form, () => (dirty.value = true), { deep: true });

onMounted(load);
</script>

<template>
  <div v-loading="loading" class="svc" :class="{ 'is-on': form.status === 1 }">
    <!-- 头：接口身份 + 实际请求，不放卡片，靠字重和等宽区分层级 -->
    <header class="svc-head">
      <el-breadcrumb class="mb-2" separator="/">
        <el-breadcrumb-item :to="{ path: '/services' }">
          数据源服务接口
        </el-breadcrumb-item>
        <el-breadcrumb-item>{{
          operation?.name || operationId
        }}</el-breadcrumb-item>
      </el-breadcrumb>
      <div class="svc-head__row">
        <div class="min-w-0">
          <h1 class="svc-head__name">
            {{ form.name || "未命名接口" }}
            <span class="svc-head__code">{{ form.code || "-" }}</span>
          </h1>
          <p class="svc-head__url">{{ actualUrl }}</p>
        </div>
        <div class="svc-head__aside">
          <span class="svc-tag" :class="form.status === 1 ? 'is-on' : 'is-off'">
            {{ form.status === 1 ? "启用" : "草稿" }}
          </span>
          <span class="svc-head__conn">
            接入点 {{ connector?.name }}
            <span v-if="connector?.code !== connector?.name" class="mono">
              {{ connector?.code }}</span
            >
          </span>
        </div>
      </div>
    </header>

    <!-- 步骤条：自绘，数字等宽，当前步用下划线而不是胶囊 -->
    <nav class="svc-steps">
      <button
        v-for="(s, i) in STEPS"
        :key="s.key"
        type="button"
        class="svc-step"
        :class="{ 'is-active': step === i, 'is-done': i < step }"
        @click="step = i"
      >
        <span class="svc-step__no">{{ String(i + 1).padStart(2, "0") }}</span>
        <span class="svc-step__text">
          <span class="svc-step__title">{{ s.title }}</span>
          <span class="svc-step__hint">{{ s.hint }}</span>
        </span>
        <span v-if="i === 1 && contractCount" class="svc-step__badge">
          {{ contractCount }}
        </span>
      </button>
    </nav>

    <div class="svc-body">
      <!-- ① 基础配置 -->
      <section v-show="step === 0" class="fields">
        <div class="field">
          <label class="field__label">接口名称<i>*</i></label>
          <div class="field__body">
            <el-input v-model="form.name" style="width: 340px" />
          </div>
        </div>

        <div class="field">
          <label class="field__label">接口标识<i>*</i></label>
          <div class="field__body">
            <el-input
              v-model="form.code"
              style="width: 260px"
              class="font-mono"
              placeholder="接入点内唯一"
            />
            <p class="field__help">
              编排节点按标识引用它，改名会让已有流程找不到接口。
            </p>
          </div>
        </div>

        <div class="field">
          <label class="field__label">请求地址<i>*</i></label>
          <div class="field__body">
            <div class="flex items-start gap-2">
              <el-select v-model="form.method" style="width: 104px">
                <el-option
                  v-for="m in METHODS"
                  :key="m"
                  :label="m"
                  :value="m"
                />
              </el-select>
              <el-input
                v-model="form.path"
                class="font-mono"
                placeholder="/company/{{input.name}}"
              />
            </div>
            <p class="field__help">
              拼在 Base URL 之后；<code class="mono">&#123;&#123;input.x&#125;&#125;</code>
              用双大括号取入参，渲染发生在脚本之后。
            </p>
          </div>
        </div>

        <div class="field">
          <label class="field__label">状态</label>
          <div class="field__body">
            <div class="flex items-center gap-3">
              <el-switch
                v-model="form.status"
                :active-value="1"
                :inactive-value="0"
              />
              <span class="field__help mt-0">
                {{ form.status === 1 ? "当前启用" : "当前是草稿" }}——草稿状态的接口不能被编排与网关调用。
              </span>
            </div>
          </div>
        </div>

        <div class="field">
          <label class="field__label" />
          <div class="field__body">
            <button
              type="button"
              class="svc-disclose"
              @click="advanced = !advanced"
            >
              <span>{{ advanced ? "收起高级配置" : "高级配置" }}</span>
              <span class="svc-disclose__count">
                Query / Headers / Body<span v-if="advancedCount">
                  · 已配 {{ advancedCount }} 项</span
                >
              </span>
            </button>
          </div>
        </div>

        <template v-if="advanced">
          <div class="field">
            <label class="field__label">Query 参数</label>
            <div class="field__body">
              <el-input
                v-model="form.queryParams"
                type="textarea"
                :rows="4"
                class="font-mono"
                placeholder='{"key":"{{input.key}}"}'
              />
            </div>
          </div>
          <div class="field">
            <label class="field__label">Headers</label>
            <div class="field__body">
              <el-input
                v-model="form.headers"
                type="textarea"
                :rows="4"
                class="font-mono"
                placeholder='{"X-Trace":"{{ctx.traceId}}"}'
              />
            </div>
          </div>
          <div class="field">
            <label class="field__label">Body 类型</label>
            <div class="field__body">
              <el-select v-model="form.bodyType" style="width: 200px">
                <el-option
                  v-for="t in BODY_TYPES"
                  :key="t.value"
                  :label="t.label"
                  :value="t.value"
                />
              </el-select>
            </div>
          </div>
          <div class="field">
            <label class="field__label">Body 模板</label>
            <div class="field__body">
              <el-input
                v-model="form.bodyTemplate"
                type="textarea"
                :rows="8"
                class="font-mono"
                placeholder='{"name":"{{input.companyName}}"}'
              />
              <p class="field__help">
                JSON 模板先解析成树再逐叶子替换，保证类型与转义正确，不是字符串拼接。
              </p>
            </div>
          </div>
        </template>
      </section>

      <!-- ② 出入参契约 -->
      <section v-show="step === 1">
        <div class="svc-lead">
          出参契约是编排的基础：这里声明的每个字段，都会成为下游节点可选的上游出参；不声明就只能靠脚本硬塞，节点出参池里看不到它。
        </div>

        <div class="svc-table">
          <el-table :data="form.responseExtract" size="small" :border="false">
            <el-table-column label="字段标识" min-width="200">
              <template #default="{ row }">
                <el-input
                  v-model="row.name"
                  size="small"
                  class="font-mono"
                  placeholder="companyName"
                />
              </template>
            </el-table-column>
            <el-table-column label="JSONPath" min-width="240">
              <template #default="{ row }">
                <el-input
                  v-model="row.jsonpath"
                  size="small"
                  class="font-mono"
                  placeholder="$.data.name"
                />
              </template>
            </el-table-column>
            <el-table-column label="默认值" min-width="160">
              <template #default="{ row }">
                <el-input
                  v-model="row.default"
                  size="small"
                  placeholder="缺失时使用"
                />
              </template>
            </el-table-column>
            <el-table-column label="" width="72" align="right">
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
              <div class="svc-empty">
                还没有声明出参字段。点下面的「新增字段」，从响应 JSON 里挑几个下游要用的值。
              </div>
            </template>
          </el-table>
        </div>

        <div class="mt-3 flex items-center gap-3">
          <el-button size="small" @click="addContractRow">新增字段</el-button>
          <span class="text-xs text-[var(--svc-muted)]">
            已声明 {{ contractCount }} 个出参；字段名重复会被拦下。
          </span>
        </div>

        <div class="svc-note">
          入参侧还没有结构定义（后端 <span class="mono">endpoint_operation</span>
          缺 <span class="mono">input_schema</span> 列，见 docs/09 §7 的 V6 迁移）。
          在那之前，入参靠 Query 参数与 Body 模板里的占位符表达。
        </div>
      </section>

      <!-- ③ 脚本 -->
      <section v-show="step === 2" class="fields">
        <div class="svc-lead">
          脚本在模板渲染之前执行：前置脚本可改
          <span class="mono">ctx.request</span> /
          <span class="mono">ctx.vars</span>，后置脚本可改
          <span class="mono">ctx.response</span> /
          <span class="mono">ctx.vars</span>。先于 URL 渲染是有意为之——否则脚本就能改掉请求地址，等于开了后门。
        </div>
        <div class="field">
          <label class="field__label">前置脚本</label>
          <div class="field__body">
            <el-input
              v-model="form.preScript"
              type="textarea"
              :rows="10"
              class="font-mono"
              placeholder="// ctx.vars.xxx = ..."
            />
          </div>
        </div>
        <div class="field">
          <label class="field__label">后置脚本</label>
          <div class="field__body">
            <el-input
              v-model="form.postScript"
              type="textarea"
              :rows="10"
              class="font-mono"
              placeholder="// ctx.response.body.xxx = ..."
            />
          </div>
        </div>
        <div class="field">
          <label class="field__label">脚本超时</label>
          <div class="field__body">
            <div class="flex items-center gap-2">
              <el-input-number
                v-model="form.scriptTimeoutMs"
                :min="100"
                :step="100"
                style="width: 160px"
              />
              <span class="field__help mt-0">毫秒</span>
            </div>
          </div>
        </div>
      </section>

      <!-- ④ 凭证 -->
      <section v-show="step === 3" class="fields">
        <div class="field">
          <label class="field__label">凭证来源</label>
          <div class="field__body">
            <el-radio-group v-model="form.credentialMode">
              <el-radio-button value="INHERIT">继承</el-radio-button>
              <el-radio-button value="OVERRIDE">指定凭证</el-radio-button>
              <el-radio-button value="NONE">不鉴权</el-radio-button>
            </el-radio-group>
            <p class="field__help">
              <template v-if="form.credentialMode === 'INHERIT'">
                先用接入点「{{ connector?.name }}" 的默认凭证（{{
                  connector?.credentialName || "未指定"
                }}），没有就用合作方默认凭证；都没有则不鉴权。
              </template>
              <template v-else-if="form.credentialMode === 'OVERRIDE'">
                这个接口要用另一套密钥（比如查询和回传是两套授权）。
              </template>
              <template v-else>
                本接口完全不发鉴权信息。公开接口选这个，
                别把 key 留在接入点上白送第三方。
              </template>
            </p>
          </div>
        </div>
        <div v-if="form.credentialMode === 'OVERRIDE'" class="field">
          <label class="field__label">选择凭证</label>
          <div class="field__body">
            <el-select
              v-model="form.credentialId"
              placeholder="选一条凭证"
              style="width: 340px"
            >
              <el-option
                v-for="c in credentials"
                :key="c.id"
                :label="`${c.name}（${c.type}）`"
                :value="c.id"
              />
            </el-select>
            <p class="field__help">
              只能选同一合作方下的凭证；找不到就去
              <a class="text-blue-600" @click="router.push(`/partners/${connector?.partnerId}/credentials`)">
                合作方凭证页
              </a>
              新建。
            </p>
          </div>
        </div>
      </section>

      <!-- ⑤ 超时与重试 -->
      <section v-show="step === 4" class="fields">
        <div class="field">
          <label class="field__label">接口超时</label>
          <div class="field__body">
            <div class="flex items-center gap-2">
              <el-input-number
                v-model="form.timeoutMs"
                :min="0"
                :step="500"
                :value-on-clear="undefined"
                placeholder="留空继承"
                style="width: 160px"
              />
              <span class="field__help mt-0">
                留空 = 继承接入点的 {{ connector?.timeoutMs ?? "-" }} ms
              </span>
            </div>
          </div>
        </div>
        <div class="field">
          <label class="field__label">重试策略</label>
          <div class="field__body">
            <el-input
              v-model="form.retry"
              type="textarea"
              :rows="5"
              class="font-mono"
              placeholder='{"maxAttempts":3,"backoffMs":200}'
            />
            <p class="field__help">
              留空 = 继承接入点；只对幂等方法（GET / HEAD）建议开启。
            </p>
          </div>
        </div>
      </section>

      <!-- ⑥ 试调 -->
      <section v-show="step === 5" class="max-w-[1040px]">
        <div class="grid grid-cols-1 gap-6 md:grid-cols-2">
          <div>
            <div class="svc-subhead">
              <span>入参 JSON</span>
              <el-button link type="primary" size="small" @click="fillSampleInput">
                按契约生成骨架
              </el-button>
            </div>
            <el-input
              v-model="debug.input"
              type="textarea"
              :rows="14"
              class="font-mono"
            />
            <div class="mt-3 flex items-center gap-3">
              <el-button type="primary" :loading="debug.sending" @click="send">
                {{ debug.dryRun ? "只渲染请求" : "发送" }}
              </el-button>
              <el-checkbox v-model="debug.dryRun">只渲染，不发请求</el-checkbox>
            </div>
            <p v-if="envStore.current" class="field__help">
              环境：<span class="mono">{{ envStore.current }}</span>
            </p>
          </div>
          <div>
            <div class="svc-subhead">
              <span>结果</span>
              <span v-if="debug.result" class="svc-subhead__meta mono">
                {{ debug.costMs }} ms
              </span>
            </div>
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
            <pre v-else-if="debug.result" class="svc-pre">{{ pretty(debug.result) }}</pre>
            <div v-else class="svc-empty svc-empty--box">还没有发送</div>
          </div>
        </div>
      </section>
    </div>

    <!-- 底部操作条：固定，带未保存标记 -->
    <footer class="svc-bar">
      <span class="svc-bar__state">
        <span v-if="dirty" class="svc-dot" />{{
          dirty ? "有未保存的改动" : "已保存"
        }}
      </span>
      <div class="flex-1" />
      <el-button v-if="step > 0" @click="step--">上一步</el-button>
      <el-button v-if="step < STEPS.length - 1" @click="step++">
        下一步
      </el-button>
      <el-button :loading="saving" type="primary" @click="save">保存</el-button>
      <el-button @click="router.push('/services')">返回列表</el-button>
    </footer>
  </div>
</template>

<style scoped>
.svc {
  --svc-ink: #15171a;
  --svc-muted: #6b7280;
  --svc-line: #e4e4e1;
  --svc-line-soft: #f0f0ed;
  --svc-signal: #1d4ed8;
  --svc-paper: #f6f6f4;
  display: flex;
  min-height: 100%;
  flex-direction: column;
  background: var(--svc-paper);
  color: var(--svc-ink);
  /* 顶部 2px 色条 = 接口状态，不用再去找标签 */
  --svc-status: #b45309;
}
.svc.is-on {
  --svc-status: #0f766e;
}

.mono {
  font-family: "IBM Plex Mono", "JetBrains Mono", ui-monospace, monospace;
  font-variant-ligatures: none;
}

/* ---------- 头部 ---------- */
.svc-head {
  padding: 16px 28px 14px;
  background: #fff;
  border-top: 2px solid var(--svc-status, transparent);
  border-bottom: 1px solid var(--svc-line);
}
.svc-head__row {
  display: flex;
  align-items: flex-start;
  gap: 24px;
}
.svc-head__name {
  margin: 0;
  font-size: 19px;
  font-weight: 600;
  letter-spacing: -0.01em;
  line-height: 1.3;
}
.svc-head__code {
  margin-left: 10px;
  font-family: "IBM Plex Mono", ui-monospace, monospace;
  font-size: 12px;
  font-weight: 400;
  letter-spacing: 0.02em;
  color: var(--svc-muted);
  vertical-align: 2px;
}
.svc-head__url {
  margin: 4px 0 0;
  font-family: "IBM Plex Mono", ui-monospace, monospace;
  font-size: 12px;
  color: var(--svc-muted);
  overflow-wrap: anywhere;
}
.svc-head__aside {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-left: auto;
  padding-top: 2px;
  font-size: 12px;
  color: var(--svc-muted);
  white-space: nowrap;
}
.svc-head__conn .mono {
  color: var(--svc-ink);
}
.svc-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 8px 2px 7px;
  font-size: 12px;
  border: 1px solid currentcolor;
  border-radius: 2px;
}
.svc-tag.is-on {
  color: #0f766e;
}
.svc-tag.is-off {
  color: #b45309;
}

/* ---------- 步骤条 ---------- */
.svc-steps {
  display: flex;
  flex-wrap: wrap;
  padding: 0 22px;
  background: #fff;
  border-bottom: 1px solid var(--svc-line);
}
.svc-step {
  display: flex;
  align-items: baseline;
  gap: 9px;
  padding: 12px 16px 11px 0;
  margin-right: 12px;
  text-align: left;
  background: none;
  border: 0;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  color: var(--svc-muted);
  transition: color 0.15s ease;
}
.svc-step:hover {
  color: var(--svc-ink);
}
.svc-step__no {
  font-family: "IBM Plex Mono", ui-monospace, monospace;
  font-size: 11px;
  letter-spacing: 0.06em;
  color: inherit;
  opacity: 0.7;
}
.svc-step__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.svc-step__title {
  font-size: 13.5px;
  font-weight: 500;
  color: inherit;
  white-space: nowrap;
}
.svc-step__hint {
  font-size: 11.5px;
  color: var(--svc-muted);
  white-space: nowrap;
  opacity: 0.85;
}
.svc-step.is-active {
  color: var(--svc-ink);
  border-bottom-color: var(--svc-signal);
}
.svc-step.is-done .svc-step__title {
  color: var(--svc-ink);
}
.svc-step__badge {
  align-self: center;
  padding: 0 5px;
  font-family: "IBM Plex Mono", ui-monospace, monospace;
  font-size: 10.5px;
  line-height: 15px;
  color: var(--svc-signal);
  border: 1px solid rgb(29 78 216 / 35%);
  border-radius: 2px;
}

/* ---------- 表单主体 ---------- */
.svc-body {
  flex: 1;
  padding: 24px 28px 28px;
}
.fields {
  display: flex;
  flex-direction: column;
  gap: 20px;
  max-width: 940px;
}
.field {
  display: grid;
  grid-template-columns: 168px minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}
.field__label {
  padding-top: 7px;
  font-size: 13px;
  line-height: 1.4;
  color: var(--svc-ink);
  text-align: right;
}
.field__label i {
  margin-left: 3px;
  font-style: normal;
  color: #b91c1c;
}
.field__body {
  min-width: 0;
}
.field__help {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.55;
  color: var(--svc-muted);
}
.field__help.mt-0 {
  margin-top: 0;
}
.field__help code,
.svc-note .mono,
.svc-lead .mono {
  padding: 0 3px;
  font-family: "IBM Plex Mono", ui-monospace, monospace;
  font-size: 11.5px;
  background: #f1f1ee;
  border-radius: 2px;
}

/* 披露块 */
.svc-disclose {
  display: flex;
  width: 100%;
  align-items: baseline;
  justify-content: space-between;
  padding: 9px 0;
  font-size: 13px;
  color: var(--svc-ink);
  background: none;
  border: 0;
  border-top: 1px solid var(--svc-line-soft);
  cursor: pointer;
}
.svc-disclose__count {
  font-size: 12px;
  color: var(--svc-muted);
}

/* 引导句 / 提示块 */
.svc-lead {
  max-width: 76ch;
  margin-bottom: 18px;
  font-size: 13px;
  line-height: 1.7;
  color: #3f4348;
}
.svc-note {
  max-width: 76ch;
  margin-top: 20px;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.65;
  color: var(--svc-muted);
  background: #fbfbf9;
  border-left: 2px solid var(--svc-line);
}
.svc-subhead {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 500;
}
.svc-subhead__meta {
  font-size: 12px;
  font-weight: 400;
  color: var(--svc-muted);
}

/* 表格：去掉卡片边框，改靠表头底线 + 行底线 */
.svc-table :deep(.el-table) {
  background: #fff;
}
.svc-table :deep(.el-table th.el-table__cell) {
  padding: 8px 0;
  font-size: 12px;
  font-weight: 500;
  color: var(--svc-muted);
  background: #fff;
  border-bottom: 1px solid var(--svc-line);
}
.svc-table :deep(.el-table td.el-table__cell) {
  padding: 6px 0;
  border-bottom: 1px solid var(--svc-line-soft);
}
.svc-table :deep(.el-table__inner-wrapper::before) {
  display: none;
}
.svc-empty {
  padding: 22px 4px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--svc-muted);
}
.svc-empty--box {
  padding: 18px;
  background: #fff;
  border: 1px dashed var(--svc-line);
}
.svc-pre {
  max-height: 360px;
  margin: 0;
  padding: 14px;
  overflow: auto;
  font-family: "IBM Plex Mono", ui-monospace, monospace;
  font-size: 12px;
  line-height: 1.6;
  background: #fff;
  border: 1px solid var(--svc-line);
}

/* ---------- 底部操作条 ---------- */
.svc-bar {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 28px;
  background: rgb(255 255 255 / 92%);
  border-top: 1px solid var(--svc-line);
  backdrop-filter: blur(6px);
}
.svc-bar__state {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: var(--svc-muted);
}
.svc-dot {
  width: 5px;
  height: 5px;
  background: #b45309;
  border-radius: 50%;
}
</style>
