<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  BODY_TYPES,
  METHODS,
  createService,
  deleteServices,
  hasContract,
  listAllConnectors,
  listServices,
  type ServiceItem
} from "@/api/service";
import type { Connector } from "@/api/connector";
import { showError } from "@/utils/http";

defineOptions({ name: "ServiceList" });

const router = useRouter();
const route = useRoute();

const loading = ref(false);
const cardView = ref(true);
const items = ref<Array<ServiceItem>>([]);
const connectors = ref<Array<Connector>>([]);
const truncated = ref(false);

const query = reactive({
  keyword: "",
  connectorId: "",
  method: "",
  status: undefined as number | undefined,
  onlyNoContract: false
});

async function load() {
  loading.value = true;
  try {
    const [result, all] = await Promise.all([
      listServices({
        keyword: query.keyword || undefined,
        connectorId: query.connectorId || undefined,
        method: query.method || undefined,
        status: query.status,
        onlyNoContract: query.onlyNoContract
      }),
      listAllConnectors()
    ]);
    items.value = result.items;
    truncated.value = result.truncated;
    connectors.value = all.connectors;
  } catch (e) {
    showError(e);
  } finally {
    loading.value = false;
  }
}

function reset() {
  query.keyword = "";
  query.connectorId = "";
  query.method = "";
  query.status = undefined;
  query.onlyNoContract = false;
  load();
}

/** 按连接器分组，卡片区按"供应商"聚拢——运营看列表时先看是谁家的接口 */
const grouped = computed(() => {
  const map = new Map<string, Array<ServiceItem>>();
  items.value.forEach(i => {
    const key = `${i.connectorName}（${i.connectorCode}）`;
    if (!map.has(key)) map.set(key, []);
    map.get(key).push(i);
  });
  return [...map.entries()];
});

const stats = computed(() => {
  const total = items.value.length;
  const withContract = items.value.filter(hasContract).length;
  const enabled = items.value.filter(i => i.status === 1).length;
  return { total, withContract, enabled, noContract: total - withContract };
});

function methodType(method: string) {
  if (method === "GET") return "success";
  if (method === "DELETE") return "danger";
  return "warning";
}

function openDetail(row: ServiceItem) {
  router.push(`/services/${row.connectorId}/${row.id}`);
}

async function remove(row: ServiceItem) {
  try {
    await ElMessageBox.confirm(
      `删除「${row.name}」后，引用该接口的编排节点会失效。确定删除？`,
      "删除接口",
      { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  try {
    await deleteServices(String(row.connectorId), [String(row.id)]);
    ElMessage.success("已删除");
    load();
  } catch (e) {
    showError(e);
  }
}

/* ---------------- 新建 ---------------- */
const dialog = reactive({ visible: false, saving: false });
const form = reactive({
  connectorId: "",
  name: "",
  code: "",
  method: "GET",
  path: "",
  bodyType: "json"
});

function openCreate() {
  Object.assign(form, {
    connectorId: query.connectorId || connectors.value[0]?.id || "",
    name: "",
    code: "",
    method: "GET",
    path: "",
    bodyType: "json"
  });
  dialog.visible = true;
}

async function save() {
  if (!form.connectorId) {
    ElMessage.warning("先选一个连接器：接口必须挂在某个上游系统下");
    return;
  }
  if (!form.name || !form.code || !form.path) {
    ElMessage.warning("名称、编码、路径必填");
    return;
  }
  dialog.saving = true;
  try {
    const id = await createService(String(form.connectorId), {
      name: form.name,
      code: form.code,
      method: form.method,
      path: form.path,
      bodyType: form.bodyType
    });
    ElMessage.success("已创建，接着补出参契约");
    dialog.visible = false;
    await load();
    router.push(`/services/${form.connectorId}/${id}`);
  } catch (e) {
    showError(e);
  } finally {
    dialog.saving = false;
  }
}

onMounted(() => {
  // 从连接器列表点「查看接口」会带 ?connectorId=，进来就得按它筛
  if (typeof route.query.connectorId === "string") {
    query.connectorId = route.query.connectorId;
  }
  load();
});
</script>

<template>
  <div class="p-4">
    <!-- 顶部指标：这一行是"资产总账"的价值所在，没有它这页就只是接口列表 -->
    <div class="mb-3 grid grid-cols-2 gap-3 md:grid-cols-4">
      <el-card shadow="never">
        <div class="text-xs text-gray-400">接口总数</div>
        <div class="text-2xl font-semibold">{{ stats.total }}</div>
      </el-card>
      <el-card shadow="never">
        <div class="text-xs text-gray-400">已启用</div>
        <div class="text-2xl font-semibold text-green-600">
          {{ stats.enabled }}
        </div>
      </el-card>
      <el-card shadow="never">
        <div class="text-xs text-gray-400">有出参契约</div>
        <div class="text-2xl font-semibold text-blue-600">
          {{ stats.withContract }}
        </div>
      </el-card>
      <el-card shadow="never">
        <div class="text-xs text-gray-400">未配契约</div>
        <div class="text-2xl font-semibold text-amber-600">
          {{ stats.noContract }}
        </div>
      </el-card>
    </div>

    <el-alert
      v-if="truncated"
      class="mb-3"
      type="warning"
      show-icon
      :closable="false"
      title="连接器超过 200 个，本页只聚合了前 200 个"
      description="继续增加连接器前请让后端提供 GET /api/services 平铺接口（设计文档 §2.2 方案 B）"
    />

    <div class="mb-3 flex flex-wrap items-center gap-2">
      <el-input
        v-model="query.keyword"
        placeholder="接口名称 / 编码 / 路径"
        clearable
        style="width: 220px"
        @keyup.enter="load"
        @clear="load"
      />
      <el-select
        v-model="query.connectorId"
        placeholder="全部连接器"
        clearable
        filterable
        style="width: 200px"
        @change="load"
      >
        <el-option
          v-for="c in connectors"
          :key="c.id"
          :label="`${c.name}（${c.code}）`"
          :value="String(c.id)"
        />
      </el-select>
      <el-select
        v-model="query.method"
        placeholder="全部方法"
        clearable
        style="width: 120px"
        @change="load"
      >
        <el-option v-for="m in METHODS" :key="m" :label="m" :value="m" />
      </el-select>
      <el-select
        v-model="query.status"
        placeholder="全部状态"
        clearable
        style="width: 120px"
        @change="load"
      >
        <el-option label="草稿" :value="0" />
        <el-option label="启用" :value="1" />
      </el-select>
      <el-checkbox v-model="query.onlyNoContract" @change="load">
        只看未配契约
      </el-checkbox>
      <el-button @click="reset">重置</el-button>
      <div class="flex-1" />
      <el-radio-group v-model="cardView" size="small">
        <el-radio-button :value="true">卡片</el-radio-button>
        <el-radio-button :value="false">表格</el-radio-button>
      </el-radio-group>
      <el-button type="primary" @click="openCreate">新增接口</el-button>
    </div>

    <!-- 卡片视图：按连接器分组 -->
    <div v-if="cardView" v-loading="loading">
      <div v-for="[group, rows] in grouped" :key="group" class="mb-5">
        <div class="mb-2 flex items-center gap-2">
          <span class="font-medium">{{ group }}</span>
          <el-tag size="small" effect="plain">{{ rows.length }} 个接口</el-tag>
        </div>
        <div class="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
          <el-card
            v-for="row in rows"
            :key="row.id"
            shadow="hover"
            class="cursor-pointer"
            @click="openDetail(row)"
          >
            <div class="mb-2 flex items-start justify-between gap-2">
              <div class="min-w-0">
                <div class="truncate font-medium" :title="row.name">
                  {{ row.name }}
                </div>
                <div class="truncate font-mono text-xs text-gray-400">
                  {{ row.code }}
                </div>
              </div>
              <el-tag
                size="small"
                effect="dark"
                :type="methodType(row.method)"
              >
                {{ row.method }}
              </el-tag>
            </div>
            <div
              class="mb-2 truncate font-mono text-xs text-gray-500"
              :title="row.path"
            >
              {{ row.path }}
            </div>
            <div class="flex flex-wrap items-center gap-2">
              <el-tag
                size="small"
                :type="row.status === 1 ? 'success' : 'info'"
                effect="plain"
              >
                {{ row.status === 1 ? "启用" : "草稿" }}
              </el-tag>
              <el-tag
                size="small"
                :type="hasContract(row) ? 'primary' : 'warning'"
                effect="plain"
              >
                {{ hasContract(row) ? "有出参契约" : "未配契约" }}
              </el-tag>
              <el-tag v-if="row.bodyType" size="small" effect="plain">
                {{ row.bodyType }}
              </el-tag>
              <div class="flex-1" />
              <el-button
                link
                type="danger"
                size="small"
                @click.stop="remove(row)"
              >
                删除
              </el-button>
            </div>
          </el-card>
        </div>
      </div>
      <el-empty
        v-if="!loading && !items.length"
        :description="
          query.keyword || query.connectorId || query.onlyNoContract
            ? '筛选无结果，试试重置条件'
            : '还没有接口，点右上角新增第一个'
        "
      />
    </div>

    <!-- 表格视图：排障时一屏看全 -->
    <el-table v-else v-loading="loading" :data="items" border stripe>
      <el-table-column prop="name" label="接口名称" min-width="160" />
      <el-table-column prop="code" label="编码" min-width="140">
        <template #default="{ row }">
          <span class="font-mono text-xs">{{ row.code }}</span>
        </template>
      </el-table-column>
      <el-table-column label="方法" width="90">
        <template #default="{ row }">
          <el-tag size="small" effect="dark" :type="methodType(row.method)">
            {{ row.method }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="连接器" min-width="140">
        <template #default="{ row }">{{ row.connectorName }}</template>
      </el-table-column>
      <el-table-column prop="path" label="路径" min-width="220">
        <template #default="{ row }">
          <span class="font-mono text-xs">{{ row.path }}</span>
        </template>
      </el-table-column>
      <el-table-column label="契约" width="110">
        <template #default="{ row }">
          <el-tag
            size="small"
            :type="hasContract(row) ? 'primary' : 'warning'"
            effect="plain"
          >
            {{ hasContract(row) ? "已配" : "未配" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
            {{ row.status === 1 ? "启用" : "草稿" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">
            详情
          </el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="还没有接口" />
      </template>
    </el-table>

    <el-dialog v-model="dialog.visible" title="新增接口" width="560px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="连接器" required>
          <el-select
            v-model="form.connectorId"
            filterable
            style="width: 100%"
            placeholder="接口必须挂在某个上游系统下"
          >
            <el-option
              v-for="c in connectors"
              :key="c.id"
              :label="`${c.name}（${c.code}）`"
              :value="String(c.id)"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 企业工商信息查询" />
        </el-form-item>
        <el-form-item label="编码" required>
          <el-input
            v-model="form.code"
            placeholder="连接器内唯一，如 qcc_company"
          />
        </el-form-item>
        <el-form-item label="方法" required>
          <el-select v-model="form.method" style="width: 100%">
            <el-option v-for="m in METHODS" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="路径" required>
          <el-input
            v-model="form.path"
            placeholder="/company/{{input.name}}（支持模板）"
          />
          <div class="mt-1 text-xs text-gray-400">
            只写路径，域名由连接器的 Base URL + 环境决定
          </div>
        </el-form-item>
        <el-form-item label="Body 类型">
          <el-select v-model="form.bodyType" style="width: 100%">
            <el-option
              v-for="t in BODY_TYPES"
              :key="t.value"
              :label="t.label"
              :value="t.value"
            />
          </el-select>
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
