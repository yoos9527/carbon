<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  copyCredential,
  createCredential,
  credentialTypeLabel,
  deleteCredential,
  getPartner,
  listCredentials,
  setDefaultCredential,
  testCredential,
  updateCredential,
  CREDENTIAL_TYPES,
  type Credential,
  type CredentialSelfCheck,
  type Partner
} from "@/api/partner";
import { showError } from "@/utils/http";

defineOptions({ name: "PartnerCredentialList" });

/**
 * 某个合作方下的凭证（一套密钥）。
 *
 * 密钥只在服务端解密，这里永远看到的是 `******`；
 * 提交时若还带着 `******`（或留空），后端视为「不修改」，沿用旧密文。
 */
const route = useRoute();
const router = useRouter();
const partnerId = computed(() => String(route.params.partnerId ?? ""));

const partner = ref<Partner | null>(null);
const rows = ref<Array<Credential>>([]);
const loading = ref(false);

/**
 * 两种凭证：
 * - header：静态列若干条请求头/查询参数，值是密钥，逐条加密；
 * - script：一段 Groovy 算出这次要加什么头，脚本里用到的密钥放在 secrets 里单独加密。
 */
type HeaderRow = { name: string; value: string; in: "header" | "query" };

const headerRows = ref<Array<HeaderRow>>([]);
const secretRows = ref<Array<{ key: string; value: string }>>([]);
const scriptText = ref("");
const scriptTtl = ref("");
const scriptSensitive = ref("");

const typeOptions = CREDENTIAL_TYPES;

const SAMPLE_SCRIPT = [
  "// 可用变量：ctx.secrets（明文密钥）、ctx.http（get/postForm/postJson）、",
  "// utils（json / jsonparse / jsonpath / hmacSha256 / md5 / sha256 / base64 / urlEncode）、log(...)",
  "// 结果写到 ctx.headers / ctx.queryParams；需要缓存就设 ctx.ttlSeconds",
  "def resp = ctx.http.postForm('https://api.example.com/oauth/token', null, [",
  "  grant_type: 'client_credentials',",
  "  client_id: ctx.secrets.clientId,",
  "  client_secret: ctx.secrets.clientSecret",
  "])",
  "if (resp.status != 200) {",
  "  throw new IllegalStateException('换 token 失败：HTTP ' + resp.status + ' ' + resp.body)",
  "}",
  "def token = resp.json?.access_token",
  "if (!token) { throw new IllegalStateException('响应里没有 access_token') }",
  "ctx.headers['Authorization'] = 'Bearer ' + token",
  "ctx.ttlSeconds = 3600",
  ""
].join("\n");

function insertSampleScript() {
  if (scriptText.value.trim()) {
    scriptText.value += "\n";
  }
  scriptText.value += SAMPLE_SCRIPT;
}

function addHeaderRow() {
  headerRows.value.push({ name: "", value: "", in: "header" });
}

function removeHeaderRow(index: number) {
  headerRows.value.splice(index, 1);
}

function addSecretRow() {
  secretRows.value.push({ key: "", value: "" });
}

function removeSecretRow(index: number) {
  secretRows.value.splice(index, 1);
}

function resetConfig(type: string, config?: Record<string, unknown>) {
  headerRows.value = [];
  secretRows.value = [];
  scriptText.value = "";
  scriptTtl.value = "";
  scriptSensitive.value = "";

  if (type === "header") {
    const list = (config?.headers as Array<Record<string, unknown>>) ?? [];
    headerRows.value = list.length
      ? list.map(h => ({
          name: String(h.name ?? ""),
          value: String(h.value ?? ""),
          in: h.in === "query" ? ("query" as const) : ("header" as const)
        }))
      : [{ name: "X-Api-Key", value: "", in: "header" }];
    return;
  }

  if (type === "script") {
    scriptText.value = String(config?.script ?? "");
    const secrets = (config?.secrets as Record<string, unknown>) ?? {};
    const keys = Object.keys(secrets);
    secretRows.value = keys.length
      ? keys.map(k => ({ key: k, value: String(secrets[k] ?? "") }))
      : [{ key: "token", value: "" }];
    scriptTtl.value = config?.ttlSeconds == null ? "" : String(config.ttlSeconds);
    const declared = config?.sensitiveKeys;
    scriptSensitive.value = Array.isArray(declared) ? declared.join(", ") : "";
  }
}

function buildConfig(): Record<string, unknown> {
  if (form.type === "header") {
    return {
      headers: headerRows.value.map(r => ({
        name: r.name.trim(),
        value: r.value,
        in: r.in
      }))
    };
  }
  const secrets: Record<string, string> = {};
  secretRows.value.forEach(r => {
    const key = r.key.trim();
    if (key) {
      secrets[key] = r.value;
    }
  });
  const config: Record<string, unknown> = { script: scriptText.value, secrets };
  if (scriptTtl.value) {
    config.ttlSeconds = Number(scriptTtl.value);
  }
  const declared = scriptSensitive.value
    .split(/[,，\s]+/)
    .map(s => s.trim())
    .filter(Boolean);
  if (declared.length) {
    config.sensitiveKeys = declared;
  }
  return config;
}

async function load() {
  loading.value = true;
  try {
    const [p, list] = await Promise.all([
      getPartner(partnerId.value),
      listCredentials(partnerId.value)
    ]);
    partner.value = p;
    rows.value = list;
  } catch (e) {
    showError(e);
  } finally {
    loading.value = false;
  }
}

/* ---------------- 编辑 ---------------- */
const dialog = reactive({ visible: false, saving: false, id: "" });
const form = reactive({
  code: "",
  name: "",
  type: "header",
  isDefault: false,
  status: 1,
  effectiveFrom: "",
  effectiveTo: "",
  remark: ""
});

function openCreate() {
  dialog.id = "";
  Object.assign(form, {
    code: "",
    name: "",
    type: "header",
    isDefault: rows.value.length === 0,
    status: 1,
    effectiveFrom: "",
    effectiveTo: "",
    remark: ""
  });
  resetConfig("header");
  dialog.visible = true;
}

function openEdit(row: Credential) {
  dialog.id = row.id;
  Object.assign(form, {
    code: row.code,
    name: row.name,
    type: row.type,
    isDefault: !!row.isDefault,
    status: row.status ?? 1,
    effectiveFrom: row.effectiveFrom ?? "",
    effectiveTo: row.effectiveTo ?? "",
    remark: row.remark ?? ""
  });
  resetConfig(row.type, row.configMasked ?? row.config ?? {});
  dialog.visible = true;
}

function onTypeChange(type: string) {
  // 换类型意味着 config 形状变了（headers 列表 ↔ script+secrets），旧的键留着只会被后端当噪声丢掉
  resetConfig(type);
}

async function save() {
  if (!form.code || !form.name) {
    ElMessage.warning("标识与名称必填");
    return;
  }
  dialog.saving = true;
  try {
    const body = {
      code: form.code,
      name: form.name,
      type: form.type,
      isDefault: form.isDefault,
      status: form.status,
      effectiveFrom: form.effectiveFrom || undefined,
      effectiveTo: form.effectiveTo || undefined,
      remark: form.remark,
      config: buildConfig()
    };
    if (dialog.id) {
      await updateCredential(partnerId.value, dialog.id, body);
      ElMessage.success("已保存");
    } else {
      await createCredential(partnerId.value, body);
      ElMessage.success("已创建");
    }
    dialog.visible = false;
    load();
  } catch (e) {
    showError(e);
  } finally {
    dialog.saving = false;
  }
}

async function remove(row: Credential) {
  try {
    await ElMessageBox.confirm(
      `删除凭证「${row.name}」？引用它的接入点/接口会退回「不鉴权」，调用可能立刻开始被三方拒绝。`,
      "删除凭证",
      { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  try {
    await deleteCredential(partnerId.value, row.id);
    ElMessage.success("已删除");
    load();
  } catch (e) {
    showError(e);
  }
}

async function makeDefault(row: Credential) {
  try {
    await setDefaultCredential(partnerId.value, row.id);
    ElMessage.success(`「${row.name}」已设为默认凭证`);
    load();
  } catch (e) {
    showError(e);
  }
}

async function copy(row: Credential) {
  try {
    await copyCredential(partnerId.value, row.id);
    ElMessage.success("已复制（副本为停用状态）");
    load();
  } catch (e) {
    showError(e);
  }
}

/* ---------------- 自检 ---------------- */
const checking = reactive<Record<string, boolean>>({});
const checkResult = reactive<Record<string, CredentialSelfCheck | null>>({});

async function selfCheck(row: Credential) {
  checking[row.id] = true;
  checkResult[row.id] = null;
  try {
    const r = await testCredential(partnerId.value, row.id);
    checkResult[row.id] = r;
    if (r.ok) {
      ElMessage.success(
        `凭证可用，耗时 ${r.latencyMs}ms${
          r.expiresIn ? `，有效期 ${r.expiresIn}s` : ""
        }`
      );
    } else {
      ElMessage.error(r.error || "自检失败");
    }
  } catch (e) {
    showError(e);
  } finally {
    checking[row.id] = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="p-4">
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <el-button link type="primary" @click="router.push('/partners')">
        ← 合作方
      </el-button>
      <span class="text-base font-medium">
        {{ partner?.name || "-" }} 的凭证
      </span>
      <span class="text-xs text-gray-400">
        {{ partner?.code }}
      </span>
      <div class="flex-1" />
      <el-button type="primary" @click="openCreate">新建凭证</el-button>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="mb-3"
      title="密钥由服务端加密存储，这里永远只看到 ******"
      description="解析顺序：接口指定 → 接入点默认 → 合作方默认（is_default 且启用中）。都没有就表示不鉴权。"
    />

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column prop="code" label="标识" width="120">
        <template #default="{ row }">
          <span class="font-mono text-xs">{{ row.code }}</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="150">
        <template #default="{ row }">
          {{ credentialTypeLabel(row.type) }}
        </template>
      </el-table-column>
      <el-table-column label="默认" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.isDefault" size="small" type="success">默认</el-tag>
          <span v-else class="text-xs text-gray-400">-</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag
            size="small"
            :type="row.status === 1 ? 'success' : 'info'"
            effect="plain"
          >
            {{ row.status === 1 ? "启用" : "停用" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="生效期" min-width="180">
        <template #default="{ row }">
          <span v-if="row.effectiveFrom || row.effectiveTo" class="text-xs">
            {{ row.effectiveFrom || "…" }} ~ {{ row.effectiveTo || "…" }}
          </span>
          <span v-else class="text-xs text-gray-400">长期</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :loading="checking[row.id]"
            @click="selfCheck(row)"
          >
            自检
          </el-button>
          <el-button
            link
            type="primary"
            :disabled="row.isDefault"
            @click="makeDefault(row)"
          >
            设为默认
          </el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="copy(row)">复制</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty
          description="还没有凭证。没有凭证的接入点会以「不鉴权」方式调用三方"
        />
      </template>
    </el-table>

    <!-- 自检结果：只展示脱敏后的注入项 -->
    <div v-if="Object.values(checkResult).some(Boolean)" class="mt-3">
      <el-card
        v-for="(r, id) in checkResult"
        v-show="r"
        :key="id"
        shadow="never"
        class="mb-2"
      >
        <div class="mb-1 text-sm">
          <el-tag :type="r?.ok ? 'success' : 'danger'" size="small">
            {{ r?.ok ? "通过" : "失败" }}
          </el-tag>
          <span class="ml-2 text-xs text-gray-500">
            耗时 {{ r?.latencyMs }}ms
            <span v-if="r?.expiresIn">· 有效期 {{ r?.expiresIn }}s</span>
          </span>
        </div>
        <div v-if="r?.error" class="text-xs text-red-600">{{ r.error }}</div>
        <div v-if="r?.headers && Object.keys(r.headers).length" class="mt-1">
          <div class="text-xs text-gray-500">注入 Header（已脱敏）</div>
          <div class="font-mono text-xs">
            <div v-for="(v, k) in r.headers" :key="k">{{ k }}: {{ v }}</div>
          </div>
        </div>
        <div v-if="r?.queryParams && Object.keys(r.queryParams).length" class="mt-1">
          <div class="text-xs text-gray-500">注入 Query（已脱敏）</div>
          <div class="font-mono text-xs">
            <div v-for="(v, k) in r.queryParams" :key="k">{{ k }}={{ v }}</div>
          </div>
        </div>
      </el-card>
    </div>

    <el-dialog
      v-model="dialog.visible"
      :title="dialog.id ? '编辑凭证' : '新建凭证'"
      width="640px"
    >
      <el-form :model="form" label-width="120px">
        <el-form-item label="标识" required>
          <el-input
            v-model="form.code"
            :disabled="!!dialog.id"
            placeholder="如 qcc-prod"
          />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 企查查生产 key" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select
            v-model="form.type"
            style="width: 100%"
            @change="onTypeChange"
          >
            <el-option
              v-for="t in typeOptions"
              :key="t.value"
              :label="t.label"
              :value="t.value"
            />
          </el-select>
        </el-form-item>

        <!-- header 型：一行一条，多行就是多个头 -->
        <el-form-item v-if="form.type === 'header'" label="请求头" required>
          <div class="w-full">
            <el-table :data="headerRows" size="small" border>
              <el-table-column label="#" width="46">
                <template #default="{ $index }">
                  <span class="font-mono text-xs">{{ $index + 1 }}</span>
                </template>
              </el-table-column>
              <el-table-column label="名称" min-width="150">
                <template #default="{ row }">
                  <el-input v-model="row.name" placeholder="X-Api-Key" />
                </template>
              </el-table-column>
              <el-table-column label="值" min-width="180">
                <template #default="{ row }">
                  <el-input
                    v-model="row.value"
                    type="password"
                    show-password
                    placeholder="三方分配的密钥"
                  />
                </template>
              </el-table-column>
              <el-table-column label="位置" width="120">
                <template #default="{ row }">
                  <el-select v-model="row.in">
                    <el-option label="请求头" value="header" />
                    <el-option label="查询参数" value="query" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="" width="64">
                <template #default="{ $index }">
                  <el-button
                    link
                    type="danger"
                    :disabled="headerRows.length <= 1"
                    @click="removeHeaderRow($index)"
                  >
                    删除
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
            <div class="mt-2 flex items-center gap-2">
              <el-button size="small" @click="addHeaderRow">添加一行</el-button>
              <span class="text-xs text-gray-400">
                值由服务端加密；编辑时看到 ****** 代表不修改
              </span>
            </div>
          </div>
        </el-form-item>

        <!-- script 型：代码算出这次要加什么 -->
        <template v-else>
          <el-form-item label="脚本" required>
            <div class="w-full">
              <el-input
                v-model="scriptText"
                type="textarea"
                :rows="12"
                resize="vertical"
                style="font-family: ui-monospace, monospace; font-size: 12px"
                placeholder="ctx.headers['X-Api-Key'] = ctx.secrets.key"
              />
              <div class="mt-2 flex items-center gap-2">
                <el-button size="small" @click="insertSampleScript">
                  插入换 token 示例
                </el-button>
                <span class="text-xs text-gray-400">
                  结果写到 ctx.headers / ctx.queryParams；需要缓存就设 ctx.ttlSeconds
                </span>
              </div>
            </div>
          </el-form-item>

          <el-form-item label="密钥变量">
            <div class="w-full">
              <el-table :data="secretRows" size="small" border>
                <el-table-column label="变量名" min-width="140">
                  <template #default="{ row }">
                    <el-input v-model="row.key" placeholder="token" />
                  </template>
                </el-table-column>
                <el-table-column label="值" min-width="200">
                  <template #default="{ row }">
                    <el-input
                      v-model="row.value"
                      type="password"
                      show-password
                      placeholder="密钥（加密存储）"
                    />
                  </template>
                </el-table-column>
                <el-table-column label="" width="64">
                  <template #default="{ $index }">
                    <el-button link type="danger" @click="removeSecretRow($index)">
                      删除
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
              <div class="mt-2 flex items-center gap-2">
                <el-button size="small" @click="addSecretRow">添加变量</el-button>
                <span class="text-xs text-gray-400">
                  脚本里用 ctx.secrets.变量名 取；密钥本身不进脚本，方便单独轮换
                </span>
              </div>
            </div>
          </el-form-item>

          <el-form-item label="缓存有效期">
            <el-input v-model="scriptTtl" placeholder="秒，留空=每次调用都执行" style="width: 220px" />
            <span class="ml-2 text-xs text-gray-400">
              换 token 类脚本填这里（如 3600），到期前复用结果
            </span>
          </el-form-item>

          <el-form-item label="脱敏字段">
            <el-input
              v-model="scriptSensitive"
              placeholder="如 Authorization, X-Sign；留空=脚本产出的全部打码"
            />
          </el-form-item>
        </template>

        <el-form-item label="设为默认">
          <el-switch v-model="form.isDefault" />
          <span class="ml-2 text-xs text-gray-400">
            一个合作方只能有一条默认凭证
          </span>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 140px">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="生效期">
          <el-date-picker
            v-model="form.effectiveFrom"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="开始（留空=不限）"
            style="width: 220px"
          />
          <span class="mx-2">~</span>
          <el-date-picker
            v-model="form.effectiveTo"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="结束（留空=不限）"
            style="width: 220px"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="save">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
