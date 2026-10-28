<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  CONCURRENCIES,
  CRON_PRESETS,
  MISFIRE_POLICIES,
  createJob,
  deleteJob,
  disableJob,
  enableJob,
  pageJobs,
  triggerJob,
  updateJob,
  type Job
} from "@/api/job";
import { loadFlowNameMap, pageFlows, type Flow } from "@/api/flow";
import { MAX_PAGE_SIZE, PAGE_SIZES } from "@/api/common";
import { showError } from "@/utils/http";

defineOptions({ name: "JobList" });

const router = useRouter();
const loading = ref(false);
const rows = ref<Array<Job>>([]);
const total = ref(0);

/** flowId → 流程名：任务表只存 id，列表里显示裸 id 没法看 */
const flowNames = ref<Map<string, string>>(new Map());
const flows = ref<Array<Flow>>([]);

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  flowId: undefined as string | undefined
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
    const page = await pageJobs({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      flowId: query.flowId
    });
    rows.value = page.rows;
    total.value = page.total;
    // 有任务指向了没取到的流程（比如流程被删或超过一页）时补一下名字映射
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
  search();
}

function flowName(id?: string) {
  if (!id) return "-";
  return flowNames.value.get(id) ?? `#${id}`;
}

/** 启停用：乐观更新，失败回滚 */
async function toggleEnabled(row: Job) {
  const next = row.enabled === 1 ? 0 : 1;
  const prev = row.enabled;
  row.enabled = next;
  try {
    if (next === 1) await enableJob(row.id);
    else await disableJob(row.id);
    ElMessage.success(
      next === 1 ? "已启用，已注册到 Quartz" : "已停用，已从 Quartz 移除"
    );
    // nextFireTimes 是后端从 Quartz 现算的，必须重新拉
    load();
  } catch (e) {
    row.enabled = prev;
    showError(e);
  }
}

async function runOnce(row: Job) {
  try {
    await triggerJob(row.id);
    ElMessage.success("已提交执行，结果去「运行记录」看");
  } catch (e) {
    showError(e);
  }
}

async function remove(row: Job) {
  try {
    await ElMessageBox.confirm(
      `确定删除任务「${row.name}」？删除后 Quartz 里的触发器会立即移除，已产生的运行记录保留。`,
      "删除任务",
      { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  try {
    await deleteJob(row.id);
    ElMessage.success("已删除");
    load();
  } catch (e) {
    showError(e);
  }
}

/* ---------------- 新建 / 编辑 ---------------- */
const dialog = reactive({
  visible: false,
  saving: false,
  editing: null as Job | null
});
const form = reactive({
  flowId: "" as string,
  name: "",
  cron: "0 0/5 * * * ?",
  timezone: "Asia/Shanghai",
  misfirePolicy: "DO_NOTHING",
  concurrency: "DISALLOW",
  paramsText: "",
  enabled: 1
});

const title = computed(() => (dialog.editing ? "编辑任务" : "新建任务"));

function openCreate() {
  dialog.editing = null;
  Object.assign(form, {
    flowId: flows.value[0]?.id ?? "",
    name: "",
    cron: "0 0/5 * * * ?",
    timezone: "Asia/Shanghai",
    misfirePolicy: "DO_NOTHING",
    concurrency: "DISALLOW",
    paramsText: "",
    enabled: 1
  });
  dialog.visible = true;
}

function openEdit(row: Job) {
  dialog.editing = row;
  Object.assign(form, {
    flowId: row.flowId,
    name: row.name,
    cron: row.cron,
    timezone: row.timezone || "Asia/Shanghai",
    misfirePolicy: row.misfirePolicy || "DO_NOTHING",
    concurrency: row.concurrency || "DISALLOW",
    paramsText:
      row.params && Object.keys(row.params).length
        ? JSON.stringify(row.params, null, 2)
        : "",
    enabled: row.enabled
  });
  dialog.visible = true;
}

/** 参数框是自由 JSON，解析失败要当场拦，别等后端 400 */
function parseParams(): Record<string, unknown> | undefined {
  const text = form.paramsText.trim();
  if (!text) return undefined;
  const parsed = JSON.parse(text);
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error('任务参数必须是 JSON 对象，例如 {"pageSize": 100}');
  }
  return parsed as Record<string, unknown>;
}

async function save() {
  if (!form.flowId) {
    ElMessage.warning("请选择关联流程");
    return;
  }
  if (!form.name.trim()) {
    ElMessage.warning("任务名必填");
    return;
  }
  if (!form.cron.trim()) {
    ElMessage.warning("cron 必填");
    return;
  }
  let params: Record<string, unknown> | undefined;
  try {
    params = parseParams();
  } catch (e) {
    ElMessage.error(`任务参数不是合法 JSON：${(e as Error).message}`);
    return;
  }
  dialog.saving = true;
  try {
    const body = {
      flowId: form.flowId,
      name: form.name.trim(),
      cron: form.cron.trim(),
      timezone: form.timezone,
      misfirePolicy: form.misfirePolicy,
      concurrency: form.concurrency,
      params,
      enabled: form.enabled
    };
    if (dialog.editing) await updateJob(dialog.editing.id, body);
    else await createJob(body);
    ElMessage.success(dialog.editing ? "已保存" : "已创建");
    dialog.visible = false;
    load();
  } catch (e) {
    showError(e);
  } finally {
    dialog.saving = false;
  }
}

onMounted(async () => {
  await loadFlows();
  load();
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
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <div class="flex-1" />
      <el-button type="primary" :disabled="!flows.length" @click="openCreate">
        新建任务
      </el-button>
    </div>

    <el-alert
      v-if="!flows.length"
      class="mb-3"
      type="info"
      show-icon
      :closable="false"
      title="还没有可调度的流程"
      description="先去「流程」里建一条并发布，才能挂定时任务。"
    />

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="name" label="任务名" min-width="160" />
      <el-table-column label="所属流程" min-width="160">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            @click="router.push(`/flow-runs?flowId=${row.flowId}`)"
          >
            {{ flowName(row.flowId) }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="cron" min-width="170">
        <template #default="{ row }">
          <span class="font-mono text-xs">{{ row.cron }}</span>
        </template>
      </el-table-column>
      <el-table-column label="下次触发" min-width="170">
        <template #default="{ row }">
          <el-tooltip
            v-if="row.nextFireTimes?.length"
            placement="top"
            :content="row.nextFireTimes.join('\n')"
          >
            <span class="font-mono text-xs">{{ row.nextFireTimes[0] }}</span>
          </el-tooltip>
          <span v-else class="text-gray-400">未调度</span>
        </template>
      </el-table-column>
      <el-table-column prop="timezone" label="时区" width="140" />
      <el-table-column label="并发" width="110">
        <template #default="{ row }">
          <el-tag
            size="small"
            effect="plain"
            :type="row.concurrency === 'ALLOW' ? 'warning' : 'info'"
          >
            {{ row.concurrency === "ALLOW" ? "允许" : "禁止" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled === 1"
            @change="toggleEnabled(row)"
          />
        </template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="170" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button
            link
            type="primary"
            :disabled="row.enabled !== 1"
            @click="runOnce(row)"
          >
            立即执行
          </el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty
          :description="
            query.flowId
              ? '该流程下还没有任务'
              : '还没有定时任务，点右上角新建一个'
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

    <el-dialog v-model="dialog.visible" :title="title" width="620px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="关联流程" required>
          <el-select v-model="form.flowId" filterable style="width: 100%">
            <el-option
              v-for="f in flows"
              :key="f.id"
              :label="f.name"
              :value="f.id"
            >
              <span>{{ f.name }}</span>
              <span class="ml-2 text-xs text-gray-400">
                {{ f.publishedVersion ? `v${f.publishedVersion}` : "未发布" }}
              </span>
            </el-option>
          </el-select>
          <div class="mt-1 text-xs text-gray-400">
            调度的是**已发布版本**，改了草稿不发布不会生效
          </div>
        </el-form-item>
        <el-form-item label="任务名" required>
          <el-input
            v-model="form.name"
            placeholder="如 每日同步客户"
            maxlength="128"
          />
        </el-form-item>
        <el-form-item label="cron" required>
          <el-input
            v-model="form.cron"
            placeholder="0 0/5 * * * ?"
            class="font-mono"
          />
          <div class="mt-1 flex flex-wrap gap-1">
            <el-tag
              v-for="p in CRON_PRESETS"
              :key="p.value"
              size="small"
              effect="plain"
              class="cursor-pointer"
              @click="form.cron = p.value"
            >
              {{ p.label }}
            </el-tag>
          </div>
          <div class="mt-1 text-xs text-gray-400">
            Quartz 是 <b>6 段</b>（秒 分 时 日 月 周），不是 Linux crontab 的 5
            段； 日与周互斥，其中一个要写 <code>?</code>
          </div>
        </el-form-item>
        <el-form-item label="时区">
          <el-input v-model="form.timezone" placeholder="Asia/Shanghai" />
          <div class="mt-1 text-xs text-gray-400">
            影响 cron 的解释（如「每天 2:00」是哪个时区的 2:00），必须是 IANA
            名称
          </div>
        </el-form-item>
        <el-form-item label="错过触发">
          <el-select v-model="form.misfirePolicy" style="width: 100%">
            <el-option
              v-for="p in MISFIRE_POLICIES"
              :key="p.value"
              :label="p.label"
              :value="p.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="并发策略">
          <el-select v-model="form.concurrency" style="width: 100%">
            <el-option
              v-for="c in CONCURRENCIES"
              :key="c.value"
              :label="c.label"
              :value="c.value"
            />
          </el-select>
          <div class="mt-1 text-xs text-gray-400">
            「禁止并发」靠 Redis 锁实现：上轮没跑完，这轮直接跳过，不会排队堆积
          </div>
        </el-form-item>
        <el-form-item label="任务参数">
          <el-input
            v-model="form.paramsText"
            type="textarea"
            :rows="4"
            class="font-mono"
            placeholder='{"pageSize": 100}'
          />
          <div class="mt-1 text-xs text-gray-400">
            作为流程 input；留空表示无参数。必须是 JSON 对象
          </div>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch
            v-model="form.enabled"
            :active-value="1"
            :inactive-value="0"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="save"
          >保存</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>
