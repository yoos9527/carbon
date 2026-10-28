<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  AUTH_TYPES,
  authTypeLabel,
  copyConnector,
  createConnector,
  deleteConnectors,
  pageConnectors,
  updateConnectorStatus,
  type Connector
} from "@/api/connector";
import { showError } from "@/utils/http";

defineOptions({ name: "ConnectorList" });

const router = useRouter();
const loading = ref(false);
const rows = ref<Array<Connector>>([]);
const total = ref(0);

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: "",
  category: "",
  status: undefined as number | undefined
});

const categories = ref<Array<string>>([]);

async function load() {
  loading.value = true;
  try {
    const page = await pageConnectors({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined,
      category: query.category || undefined,
      status: query.status
    });
    rows.value = page.rows;
    total.value = page.total;
    // 分组下拉从当前页数据聚合，不额外造字典表
    const set = new Set(categories.value);
    page.rows.forEach(r => r.category && set.add(r.category));
    categories.value = [...set].sort();
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
  query.keyword = "";
  query.category = "";
  query.status = undefined;
  search();
}

/** 启停用：乐观更新，失败回滚 */
async function toggleStatus(row: Connector) {
  const next = row.status === 1 ? 0 : 1;
  const prev = row.status;
  row.status = next;
  try {
    await updateConnectorStatus(row.id, next);
    ElMessage.success(next === 1 ? "已启用" : "已停用");
  } catch (e) {
    row.status = prev;
    showError(e);
  }
}

async function copy(row: Connector) {
  const count = row.operationCount ?? 0;
  try {
    await ElMessageBox.confirm(
      `会连同鉴权配置${count > 0 ? `与 ${count} 个接口` : ""}一起复制，副本以「停用」状态创建（改完基址/环境再启用）。继续？`,
      `复制「${row.name}」`,
      { type: "info", confirmButtonText: "复制", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  try {
    await copyConnector(row.id);
    ElMessage.success("已复制，副本处于停用状态");
    load();
  } catch (e) {
    showError(e);
  }
}

async function remove(row: Connector) {
  const count = row.operationCount ?? 0;
  try {
    await ElMessageBox.confirm(
      count > 0
        ? `「${row.name}」下的 ${count} 个接口会一起删除，调用它的编排将失效。确定删除？`
        : `确定删除「${row.name}」？`,
      "删除连接器",
      { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  try {
    await deleteConnectors([row.id]);
    ElMessage.success("已删除");
    load();
  } catch (e) {
    showError(e);
  }
}

/* ---------------- 新建 ---------------- */
const dialog = reactive({ visible: false, saving: false });
const form = reactive({
  code: "",
  name: "",
  category: "",
  baseUrl: "",
  authType: "none",
  timeoutMs: 10000,
  remark: ""
});

function openCreate() {
  Object.assign(form, {
    code: "",
    name: "",
    category: "",
    baseUrl: "",
    authType: "none",
    timeoutMs: 10000,
    remark: ""
  });
  dialog.visible = true;
}

async function save() {
  if (!form.code || !form.name || !form.baseUrl) {
    ElMessage.warning("编码、名称、Base URL 必填");
    return;
  }
  dialog.saving = true;
  try {
    await createConnector({ ...form });
    ElMessage.success("已创建");
    dialog.visible = false;
    load();
  } catch (e) {
    showError(e);
  } finally {
    dialog.saving = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="p-4">
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <el-input
        v-model="query.keyword"
        placeholder="名称或编码"
        clearable
        style="width: 200px"
        @keyup.enter="search"
        @clear="search"
      />
      <el-select
        v-model="query.category"
        placeholder="全部分组"
        clearable
        style="width: 140px"
        @change="search"
      >
        <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
      </el-select>
      <el-select
        v-model="query.status"
        placeholder="全部状态"
        clearable
        style="width: 120px"
        @change="search"
      >
        <el-option label="启用" :value="1" />
        <el-option label="停用" :value="0" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <div class="flex-1" />
      <el-button type="primary" @click="openCreate">新建连接器</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column prop="code" label="编码" min-width="140">
        <template #default="{ row }">
          <span class="font-mono text-xs">{{ row.code }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="category" label="分组" width="100">
        <template #default="{ row }">{{ row.category || "-" }}</template>
      </el-table-column>
      <el-table-column prop="baseUrl" label="Base URL" min-width="200">
        <template #default="{ row }">
          <span class="font-mono text-xs">{{ row.baseUrl }}</span>
        </template>
      </el-table-column>
      <el-table-column label="鉴权" width="120">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">
            {{ authTypeLabel(row.authType) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="operationCount" label="接口数" width="80" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-switch
            :model-value="row.status === 1"
            @change="toggleStatus(row)"
          />
        </template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="180" />
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            @click="router.push(`/services?connectorId=${row.id}`)"
          >
            接口
          </el-button>
          <el-button link type="primary" @click="copy(row)">复制</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty
          :description="
            query.keyword || query.category || query.status !== undefined
              ? '筛选无结果，试试重置条件'
              : '还没有连接器，点右上角新建一个'
          "
        />
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

    <el-dialog v-model="dialog.visible" title="新建连接器" width="560px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="编码" required>
          <el-input
            v-model="form.code"
            placeholder="英文/数字/下划线/短横线，如 crm"
          />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 客户中心" />
        </el-form-item>
        <el-form-item label="分组">
          <el-input v-model="form.category" placeholder="如 CRM" />
        </el-form-item>
        <el-form-item label="Base URL" required>
          <el-input
            v-model="form.baseUrl"
            placeholder="https://{{env.host}} 或 https://api.example.com"
          />
        </el-form-item>
        <el-form-item label="鉴权类型">
          <el-select v-model="form.authType" style="width: 100%">
            <el-option
              v-for="t in AUTH_TYPES"
              :key="t.value"
              :label="t.label"
              :value="t.value"
            />
          </el-select>
          <div class="mt-1 text-xs text-gray-400">
            鉴权参数（API
            Key/密码等）保存后在详情页配置，密钥入库加密、只回显掩码
          </div>
        </el-form-item>
        <el-form-item label="超时(ms)">
          <el-input-number v-model="form.timeoutMs" :min="1000" :step="1000" />
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
