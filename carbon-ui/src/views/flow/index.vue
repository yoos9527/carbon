<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  createFlow,
  deleteFlow,
  pageFlows,
  publishFlow,
  runFlowAsync,
  updateFlow,
  type Flow
} from "@/api/flow";
import { showError } from "@/utils/http";

defineOptions({ name: "FlowList" });

const router = useRouter();

const loading = ref(false);
const rows = ref<Array<Flow>>([]);
const total = ref(0);
/** 运行区只看已发布的；编辑区看全部（含从没发布过的草稿） */
const area = ref<"run" | "edit">("run");

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: ""
});

/** 节点数只能从 draftDsl 算，而列表接口不下发它——所以这一列只在编辑区显示为"未加载" */
const showDraftColumns = computed(() => area.value === "edit");

async function load() {
  loading.value = true;
  try {
    const page = await pageFlows({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined
    });
    // 运行区在前端过滤：后端没有"只要已发布"的参数，而这个过滤是页面的核心语义
    rows.value =
      area.value === "run"
        ? page.rows.filter(f => (f.publishedVersion ?? 0) > 0)
        : page.rows;
    total.value = area.value === "run" ? rows.value.length : page.total;
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

function switchArea() {
  query.pageNum = 1;
  load();
}

/* ---------------- 新建 / 编辑基本信息 ---------------- */
const dialog = reactive({ visible: false, saving: false, editingId: "" });
const form = reactive({ code: "", name: "", status: 1 });

function openCreate() {
  dialog.editingId = "";
  Object.assign(form, { code: "", name: "", status: 1 });
  dialog.visible = true;
}

function openEdit(row: Flow) {
  dialog.editingId = row.id;
  Object.assign(form, {
    code: row.code,
    name: row.name,
    status: row.status
  });
  dialog.visible = true;
}

async function save() {
  if (!form.code || !form.name) {
    ElMessage.warning("标识与名称必填");
    return;
  }
  if (!/^[A-Za-z][A-Za-z0-9_]{1,63}$/.test(form.code)) {
    ElMessage.warning("标识只能由字母、数字、下划线组成，且以字母开头（2–64 位）");
    return;
  }
  dialog.saving = true;
  try {
    if (dialog.editingId) {
      await updateFlow(dialog.editingId, { ...form });
      ElMessage.success("已保存");
    } else {
      const created = await createFlow({ ...form });
      ElMessage.success("已创建，进设计器画流程");
      dialog.visible = false;
      router.push(`/flows/design/${created.id}`);
      return;
    }
    dialog.visible = false;
    load();
  } catch (e) {
    showError(e);
  } finally {
    dialog.saving = false;
  }
}

/* ---------------- 行操作 ---------------- */
function design(row: Flow) {
  router.push(`/flows/design/${row.id}`);
}

function versions(row: Flow) {
  router.push(`/flows/${row.id}/versions`);
}

async function publish(row: Flow) {
  try {
    await ElMessageBox.confirm(
      `发布会把当前草稿固化成 V${(row.publishedVersion ?? 0) + 1}，运行区立刻生效。继续？`,
      `发布「${row.name}」`,
      { type: "info", confirmButtonText: "发布", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  try {
    await publishFlow(row.id);
    ElMessage.success("已发布");
    load();
  } catch (e) {
    showError(e);
  }
}

async function runOnce(row: Flow) {
  if (!row.publishedVersion) {
    ElMessage.warning("还没发布过，先发布再试跑（运行的是已发布版本，不是草稿）");
    return;
  }
  try {
    const run = await runFlowAsync(row.id, {});
    ElMessage.success("已提交，跳转运行详情看进度");
    router.push({ path: "/flow-runs", query: { runId: String(run.id) } });
  } catch (e) {
    showError(e);
  }
}

async function remove(row: Flow) {
  try {
    await ElMessageBox.confirm(
      (row.publishedVersion ?? 0) > 0
        ? `「${row.name}」已发布 ${row.publishedVersion} 个版本，删除后运行区入口与历史版本的引用都会失效。确定删除？`
        : `确定删除「${row.name}」？`,
      "删除流程",
      { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  try {
    await deleteFlow(row.id);
    ElMessage.success("已删除");
    load();
  } catch (e) {
    showError(e);
  }
}

onMounted(load);
</script>

<template>
  <div class="p-4">
    <el-alert
      class="mb-3"
      type="info"
      :closable="false"
      show-icon
      title="运行区跑的是已发布版本，编辑区改的是草稿"
      description="两个区不是两套数据，是同一份流程的两个视角：编辑区保存草稿不影响线上，发布后运行区才跟着变。"
    />

    <div class="mb-3 flex flex-wrap items-center gap-2">
      <el-radio-group v-model="area" @change="switchArea">
        <el-radio-button value="run">运行区</el-radio-button>
        <el-radio-button value="edit">编辑区</el-radio-button>
      </el-radio-group>
      <el-input
        v-model="query.keyword"
        placeholder="流程名称或标识"
        clearable
        style="width: 220px"
        @keyup.enter="search"
        @clear="search"
      />
      <el-button @click="search">查询</el-button>
      <div class="flex-1" />
      <el-button type="primary" @click="openCreate">新建流程</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="name" label="流程名称" min-width="180" />
      <el-table-column prop="code" label="标识" min-width="160">
        <template #default="{ row }">
          <span class="font-mono text-xs">{{ row.code }}</span>
        </template>
      </el-table-column>
      <el-table-column label="已发布版本" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.publishedVersion" size="small" type="success">
            V{{ row.publishedVersion }}
          </el-tag>
          <span v-else class="text-xs text-gray-400">从未发布</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
            {{ row.status === 1 ? "启用" : "停用" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        v-if="showDraftColumns"
        prop="updatedAt"
        label="最后修改"
        width="180"
      />
      <el-table-column label="操作" width="320" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="design(row)">设计</el-button>
          <el-button link type="primary" @click="runOnce(row)">试跑</el-button>
          <el-button link type="primary" @click="versions(row)">
            版本历史
          </el-button>
          <el-button link type="primary" @click="publish(row)">发布</el-button>
          <el-button link type="primary" @click="openEdit(row)">改名</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty
          :description="
            area === 'run'
              ? '运行区还没有已发布的流程——去编辑区设计并发布一个'
              : '还没有流程，点右上角新建'
          "
        />
      </template>
    </el-table>

    <div v-if="area === 'edit'" class="mt-3 flex justify-end">
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

    <el-dialog
      v-model="dialog.visible"
      :title="dialog.editingId ? '编辑流程信息' : '新建流程'"
      width="520px"
    >
      <el-form :model="form" label-width="90px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 企查查企业核查" />
        </el-form-item>
        <el-form-item label="标识" required>
          <el-input
            v-model="form.code"
            placeholder="英文开头，如 company_check"
            :disabled="!!dialog.editingId"
          />
          <div class="mt-1 text-xs text-gray-400">
            标识被调度任务与网关引用，创建后不可改
          </div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch
            v-model="form.status"
            :active-value="1"
            :inactive-value="0"
            active-text="启用"
            inactive-text="停用"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="save">
          {{ dialog.editingId ? "保存" : "创建并进设计器" }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
