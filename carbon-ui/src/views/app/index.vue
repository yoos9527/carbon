<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  createApp,
  deleteApp,
  listApps,
  rotateAppSecret,
  updateApp,
  updateAppStatus,
  type AppItem,
  type AppUpdateForm
} from "@/api/app";
import { pageConnectors, type Connector } from "@/api/connector";
import { showError } from "@/utils/http";

defineOptions({ name: "AppList" });

const loading = ref(false);
const rows = ref<Array<AppItem>>([]);
const connectors = ref<Array<Connector>>([]);

async function load() {
  loading.value = true;
  try {
    rows.value = await listApps();
  } catch (e) {
    showError(e);
  } finally {
    loading.value = false;
  }
}

async function loadConnectors() {
  try {
    const page = await pageConnectors({ pageNum: 1, pageSize: 100 });
    connectors.value = page.rows ?? [];
  } catch (e) {
    showError(e);
  }
}

function connectorName(id: string) {
  return connectors.value.find(c => c.id === String(id))?.name ?? `#${id}`;
}

/* ---------------- 一次性密钥 ---------------- */

const secretDialog = reactive({
  visible: false,
  name: "",
  appKey: "",
  appSecret: "",
  notice: ""
});

function showSecret(
  name: string,
  appKey: string,
  appSecret: string,
  notice?: string
) {
  Object.assign(secretDialog, {
    visible: true,
    name,
    appKey,
    appSecret,
    notice: notice ?? "密钥只显示这一次，请立刻保存；丢失只能重新生成"
  });
}

async function copySecret() {
  try {
    await navigator.clipboard.writeText(secretDialog.appSecret);
    ElMessage.success("已复制密钥");
  } catch {
    // 非 HTTPS 或浏览器拒绝剪贴板权限：让用户自己选中，别让复制失败被当成没生成
    ElMessage.warning("浏览器不允许自动复制，请手动选中密钥");
  }
}

/* ---------------- 新建 / 编辑 ---------------- */

const dialog = reactive({ visible: false, saving: false, editingId: "" });
const form = reactive({
  name: "",
  appKey: "",
  allowedConnectors: [] as Array<string>,
  rateLimitQps: 50,
  ipWhitelist: "" as string
});

const isEdit = computed(() => !!dialog.editingId);

function openCreate() {
  dialog.editingId = "";
  Object.assign(form, {
    name: "",
    appKey: "",
    allowedConnectors: [],
    rateLimitQps: 50,
    ipWhitelist: ""
  });
  dialog.visible = true;
}

function openEdit(row: AppItem) {
  dialog.editingId = row.id;
  Object.assign(form, {
    name: row.name,
    appKey: row.appKey,
    allowedConnectors: [...(row.allowedConnectors ?? [])].map(String),
    rateLimitQps: row.rateLimitQps ?? 50,
    ipWhitelist: (row.ipWhitelist ?? []).join("\n")
  });
  dialog.visible = true;
}

function collectIps(): Array<string> {
  return form.ipWhitelist
    .split(/[\n,;]+/)
    .map(s => s.trim())
    .filter(Boolean);
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning("应用名必填");
    return;
  }
  dialog.saving = true;
  try {
    if (isEdit.value) {
      const payload: AppUpdateForm = {
        name: form.name.trim(),
        allowedConnectors: form.allowedConnectors,
        rateLimitQps: form.rateLimitQps,
        ipWhitelist: collectIps()
      };
      await updateApp(dialog.editingId, payload);
      ElMessage.success("已保存");
    } else {
      const created = await createApp({
        name: form.name.trim(),
        allowedConnectors: form.allowedConnectors,
        rateLimitQps: form.rateLimitQps,
        ipWhitelist: collectIps()
      });
      showSecret(
        created.name,
        created.appKey,
        created.appSecret,
        created.notice
      );
    }
    dialog.visible = false;
    load();
  } catch (e) {
    showError(e);
  } finally {
    dialog.saving = false;
  }
}

async function toggle(row: AppItem) {
  const next = row.status === 1 ? 0 : 1;
  try {
    await updateAppStatus(row.id, next);
    ElMessage.success(
      next === 1 ? "已启用" : "已停用（网关立即拒绝该 appKey）"
    );
    load();
  } catch (e) {
    showError(e);
  }
}

async function rotate(row: AppItem) {
  try {
    await ElMessageBox.confirm(
      `重置后旧密钥立即失效，调用方必须同步换新。确定重置「${row.name}」的密钥？`,
      "重置密钥",
      { type: "warning", confirmButtonText: "重置", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  try {
    const view = await rotateAppSecret(row.id);
    showSecret(view.name, view.appKey, view.appSecret, view.notice);
    load();
  } catch (e) {
    showError(e);
  }
}

async function remove(row: AppItem) {
  try {
    await ElMessageBox.confirm(
      `删除后该 appKey 立即失效且不可恢复（应用没有回收站）。确定删除「${row.name}」？`,
      "删除应用",
      { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  try {
    await deleteApp(row.id);
    ElMessage.success("已删除");
    load();
  } catch (e) {
    showError(e);
  }
}

onMounted(() => {
  load();
  loadConnectors();
});
</script>

<template>
  <div class="p-4">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="mb-3"
      title="应用 = 网关调用方凭证"
      description="业务系统拿 AppKey + Secret 对 /gw/** 做 HMAC-SHA256 签名。密钥只在创建与重置时明文显示一次，平台不留副本；连接器白名单留空表示不限制。"
    />

    <div class="mb-3 flex items-center gap-2">
      <div class="flex-1" />
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="openCreate">新建应用</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="name" label="名称" min-width="140">
        <template #default="{ row }">
          {{ row.name }}
          <el-tag v-if="row.status !== 1" size="small" type="info"
            >已停用</el-tag
          >
        </template>
      </el-table-column>
      <el-table-column label="AppKey" min-width="220">
        <template #default="{ row }">
          <span class="font-mono text-xs">{{ row.appKey }}</span>
        </template>
      </el-table-column>
      <el-table-column label="密钥" width="100">
        <template #default="{ row }">
          <el-tag :type="row.secretSet ? 'success' : 'danger'" size="small">
            {{ row.secretSet ? "已设置" : "缺失" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="连接器白名单" min-width="220">
        <template #default="{ row }">
          <span
            v-if="!row.allowedConnectors?.length"
            class="text-xs text-gray-400"
          >
            不限制
          </span>
          <span v-else class="flex flex-wrap gap-1">
            <el-tag
              v-for="id in row.allowedConnectors"
              :key="id"
              size="small"
              effect="plain"
            >
              {{ connectorName(id) }}
            </el-tag>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="IP 白名单" min-width="180">
        <template #default="{ row }">
          <span v-if="!row.ipWhitelist?.length" class="text-xs text-gray-400">
            不限制
          </span>
          <span v-else class="flex flex-wrap gap-1">
            <el-tag
              v-for="ip in row.ipWhitelist"
              :key="ip"
              size="small"
              effect="plain"
            >
              {{ ip }}
            </el-tag>
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="rateLimitQps" label="QPS" width="88" />
      <el-table-column prop="updatedAt" label="更新时间" width="180" />
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="rotate(row)">
            重置密钥
          </el-button>
          <el-button link type="primary" @click="toggle(row)">
            {{ row.status === 1 ? "停用" : "启用" }}
          </el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="还没有应用，新建一个就能拿到 AppKey" />
      </template>
    </el-table>

    <el-dialog
      v-model="dialog.visible"
      :title="isEdit ? '编辑应用' : '新建应用'"
      width="620px"
    >
      <el-form :model="form" label-width="110px">
        <el-form-item label="应用名" required>
          <el-input v-model="form.name" placeholder="如 订单中心 / 数据组" />
        </el-form-item>
        <el-form-item label="连接器白名单">
          <el-select
            v-model="form.allowedConnectors"
            multiple
            filterable
            clearable
            placeholder="不选 = 不限制"
            style="width: 100%"
          >
            <el-option
              v-for="c in connectors"
              :key="c.id"
              :label="`${c.name}（${c.code}）`"
              :value="c.id"
            />
          </el-select>
          <div class="mt-1 text-xs text-gray-400">
            不选表示不限制。选了之后，调用未授权的连接器会
            403；编排入口会按流程实际引用到的连接器逐个校验。
          </div>
        </el-form-item>
        <el-form-item label="QPS 上限">
          <el-input-number v-model="form.rateLimitQps" :min="1" :max="10000" />
          <div class="mt-1 text-xs text-gray-400">
            应用级限流（Redis 计数）。默认 50
          </div>
        </el-form-item>
        <el-form-item label="IP 白名单">
          <el-input
            v-model="form.ipWhitelist"
            type="textarea"
            :rows="3"
            placeholder="一行一个，支持 CIDR，如 10.0.0.0/8（留空 = 不限制）"
          />
          <div class="mt-1 text-xs text-gray-400">
            保存时即校验格式，写错的条目不会等到调用时才 403
          </div>
        </el-form-item>
        <el-form-item v-if="isEdit" label="AppKey">
          <span class="font-mono text-xs">{{ form.appKey }}</span>
          <div class="mt-1 text-xs text-gray-400">
            AppKey
            不可修改：它已被调用方写进配置与日志检索里，换掉只会让接入方半夜排查
            401。要轮换凭据请用「重置密钥」。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="save">
          保存
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="secretDialog.visible"
      title="密钥（只显示这一次）"
      width="560px"
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        :title="secretDialog.notice"
      />
      <el-form label-width="90px" class="mt-3">
        <el-form-item label="应用">{{ secretDialog.name }}</el-form-item>
        <el-form-item label="AppKey">
          <span class="font-mono text-xs">{{ secretDialog.appKey }}</span>
        </el-form-item>
        <el-form-item label="Secret">
          <el-input
            :model-value="secretDialog.appSecret"
            readonly
            class="font-mono"
          />
        </el-form-item>
      </el-form>
      <div class="text-xs text-gray-400">
        签名串：{method}\n{path?query}\n{timestamp}\n{nonce}\n{sha256Hex(body)}，用
        Secret 做 HMAC-SHA256 后 base64。
      </div>
      <template #footer>
        <el-button @click="copySecret">复制密钥</el-button>
        <el-button type="primary" @click="secretDialog.visible = false">
          我已保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
