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

/** 每种凭证类型的字段定义：secret 的走加密，其余明文存（便于排障） */
interface FieldDef {
  key: string;
  label: string;
  secret?: boolean;
  placeholder?: string;
  /** select 类型：有 options 就渲染下拉 */
  options?: Array<{ value: string; label: string }>;
  hint?: string;
}

const FIELDS: Record<string, Array<FieldDef>> = {
  none: [],
  api_key: [
    { key: "value", label: "密钥值", secret: true, placeholder: "三方分配的 key" },
    { key: "name", label: "Header 名", placeholder: "默认 X-Api-Key" },
    {
      key: "in",
      label: "注入位置",
      options: [
        { value: "header", label: "Header" },
        { value: "query", label: "Query 参数" }
      ],
      hint: "query 型用于把 key 拼在 URL 上的供应商"
    }
  ],
  basic: [
    { key: "username", label: "用户名" },
    { key: "password", label: "密码", secret: true }
  ],
  bearer: [
    { key: "token", label: "Token", secret: true, placeholder: "静态 token" }
  ],
  oauth2_cc: [
    { key: "tokenUrl", label: "Token 地址", placeholder: "https://.../oauth/token" },
    { key: "clientId", label: "Client ID" },
    { key: "clientSecret", label: "Client Secret", secret: true },
    { key: "scope", label: "Scope", placeholder: "可留空" },
    { key: "tokenField", label: "Token 字段", placeholder: "默认 access_token" },
    {
      key: "ttlSeconds",
      label: "兜底有效期(秒)",
      placeholder: "默认 3600，接口返回 expires_in 时以它为准"
    }
  ],
  login_token: [
    { key: "loginUrl", label: "登录地址", placeholder: "https://.../login" },
    {
      key: "method",
      label: "登录方法",
      options: [
        { value: "POST", label: "POST" },
        { value: "GET", label: "GET" }
      ]
    },
    { key: "username", label: "用户名" },
    { key: "password", label: "密码", secret: true },
    { key: "headers", label: "登录附加头", placeholder: '{"X-App":"carbon"}' },
    { key: "body", label: "登录请求体", placeholder: '{"account":"..."}' },
    { key: "tokenJsonpath", label: "Token 路径", placeholder: "默认 $.access_token" },
    { key: "headerName", label: "注入 Header", placeholder: "默认 Authorization" },
    { key: "tokenPrefix", label: "前缀", placeholder: '默认 "Bearer "' }
  ]
};

const typeOptions = CREDENTIAL_TYPES;

function fieldsOf(type: string): Array<FieldDef> {
  return FIELDS[type] ?? [];
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
  type: "api_key",
  isDefault: false,
  status: 1,
  effectiveFrom: "",
  effectiveTo: "",
  remark: "",
  config: {} as Record<string, unknown>
});

function resetConfig(type: string, config?: Record<string, unknown>) {
  const next: Record<string, unknown> = {};
  fieldsOf(type).forEach(f => {
    next[f.key] = config?.[f.key] ?? "";
  });
  form.config = next;
}

function openCreate() {
  dialog.id = "";
  Object.assign(form, {
    code: "",
    name: "",
    type: "api_key",
    isDefault: rows.value.length === 0,
    status: 1,
    effectiveFrom: "",
    effectiveTo: "",
    remark: ""
  });
  resetConfig("api_key");
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
  // 换类型意味着字段结构变了，旧的键留着只会被后端当噪声丢掉
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
      config: form.config
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

        <el-form-item
          v-for="f in fieldsOf(form.type)"
          :key="f.key"
          :label="f.label"
          :required="f.secret"
        >
          <el-select
            v-if="f.options"
            v-model="form.config[f.key] as string"
            style="width: 100%"
          >
            <el-option
              v-for="o in f.options"
              :key="o.value"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
          <el-input
            v-else
            v-model="form.config[f.key] as string"
            :type="f.secret ? 'password' : 'text'"
            :show-password="f.secret"
            :placeholder="f.placeholder"
          />
          <div v-if="f.hint" class="mt-1 text-xs text-gray-400">{{ f.hint }}</div>
          <div
            v-else-if="f.secret && dialog.id"
            class="mt-1 text-xs text-gray-400"
          >
            保持 ****** 或留空表示不修改
          </div>
        </el-form-item>

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
