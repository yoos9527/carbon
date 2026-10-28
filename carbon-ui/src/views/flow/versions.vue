<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  getFlow,
  listFlowVersions,
  rollbackFlow,
  type Flow,
  type FlowVersion
} from "@/api/flow";
import { showError } from "@/utils/http";

defineOptions({ name: "FlowVersions" });

const route = useRoute();
const router = useRouter();
const flowId = String(route.params.id);

const loading = ref(false);
const rolling = ref(false);
const flow = ref<Flow>();
const versions = ref<Array<FlowVersion>>([]);
/** 对比选中的两个版本号（最多两个，选第三个时挤掉最早选的那个） */
const picked = ref<Array<number>>([]);

async function load() {
  loading.value = true;
  try {
    const [f, list] = await Promise.all([
      getFlow(flowId),
      listFlowVersions(flowId)
    ]);
    flow.value = f;
    // 后端按版本升序返回，页面从新到旧更像"历史"
    versions.value = [...list].sort((a, b) => b.version - a.version);
  } catch (e) {
    showError(e);
  } finally {
    loading.value = false;
  }
}

function togglePick(version: number) {
  const i = picked.value.indexOf(version);
  if (i >= 0) {
    picked.value.splice(i, 1);
    return;
  }
  if (picked.value.length >= 2) picked.value.shift();
  picked.value.push(version);
}

/* ---------------- 版本对比 ---------------- */

interface ParsedDsl {
  version?: number;
  nodes: Array<Record<string, unknown>>;
  edges: Array<Record<string, unknown>>;
  timeoutMs?: number;
}

function parse(dsl?: string): ParsedDsl | null {
  if (!dsl) return null;
  try {
    const obj = JSON.parse(dsl) as ParsedDsl;
    return {
      version: obj.version,
      nodes: Array.isArray(obj.nodes) ? obj.nodes : [],
      edges: Array.isArray(obj.edges) ? obj.edges : [],
      timeoutMs: obj.timeoutMs
    };
  } catch {
    return null;
  }
}

interface DiffRow {
  kind: "新增" | "删除" | "修改";
  layer: "节点" | "连线";
  key: string;
  detail: string;
}

const compare = computed<{ rows: Array<DiffRow>; a?: number; b?: number }>(
  () => {
    if (picked.value.length !== 2) return { rows: [] };
    const [va, vb] = [...picked.value].sort((x, y) => x - y);
    const a = parse(versions.value.find(v => v.version === va)?.dsl);
    const b = parse(versions.value.find(v => v.version === vb)?.dsl);
    if (!a || !b) return { rows: [], a: va, b: vb };

    const rows: Array<DiffRow> = [];

    // 节点：按 id 对齐。id 变了就是删一个加一个，这是有意为之——
    // 画布上"替换节点"本来就该表现为这样，而不是一条看不动的"修改"
    const aNodes = new Map(a.nodes.map(n => [String(n.id), n]));
    const bNodes = new Map(b.nodes.map(n => [String(n.id), n]));
    aNodes.forEach((n, id) => {
      const other = bNodes.get(id);
      if (!other) {
        rows.push({
          kind: "删除",
          layer: "节点",
          key: id,
          detail: String(n.label ?? n.type ?? "")
        });
        return;
      }
      const changed = ["label", "type", "connector", "operation"].filter(
        k => JSON.stringify(n[k]) !== JSON.stringify(other[k])
      );
      if (changed.length) {
        rows.push({
          kind: "修改",
          layer: "节点",
          key: id,
          detail: changed
            .map(
              k => `${k}: ${JSON.stringify(n[k])} → ${JSON.stringify(other[k])}`
            )
            .join("；")
        });
      }
    });
    bNodes.forEach((n, id) => {
      if (!aNodes.has(id)) {
        rows.push({
          kind: "新增",
          layer: "节点",
          key: id,
          detail: String(n.label ?? n.type ?? "")
        });
      }
    });

    // 连线：source->target 是无端口契约下的唯一标识
    const keyOf = (e: Record<string, unknown>) =>
      `${e.source}→${e.target}`;
    const aEdges = new Set(a.edges.map(keyOf));
    const bEdges = new Set(b.edges.map(keyOf));
    aEdges.forEach(k => {
      if (!bEdges.has(k)) {
        rows.push({ kind: "删除", layer: "连线", key: k, detail: "" });
      }
    });
    bEdges.forEach(k => {
      if (!aEdges.has(k)) {
        rows.push({ kind: "新增", layer: "连线", key: k, detail: "" });
      }
    });

    return { rows, a: va, b: vb };
  }
);

const diffCount = computed(() => ({
  added: compare.value.rows.filter(r => r.kind === "新增").length,
  removed: compare.value.rows.filter(r => r.kind === "删除").length,
  modified: compare.value.rows.filter(r => r.kind === "修改").length
}));

function tagType(kind: DiffRow["kind"]) {
  return kind === "新增" ? "success" : kind === "删除" ? "danger" : "warning";
}

/* ---------------- 回滚 ---------------- */
async function rollback(v: FlowVersion) {
  try {
    await ElMessageBox.confirm(
      `会把「${flow.value?.name ?? ""}」的已发布指针退回到 V${v.version}，同时当前草稿会被覆盖成该版本的内容（草稿里未发布的改动会丢失）。确定回滚？`,
      `回滚到 V${v.version}`,
      { type: "warning", confirmButtonText: "回滚", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  rolling.value = true;
  try {
    await rollbackFlow(flowId, v.version);
    ElMessage.success(`已回滚到 V${v.version}`);
    await load();
  } catch (e) {
    showError(e);
  } finally {
    rolling.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div v-loading="loading" class="p-4">
    <el-breadcrumb class="mb-3" separator="/">
      <el-breadcrumb-item :to="{ path: '/flows' }">流程列表</el-breadcrumb-item>
      <el-breadcrumb-item>{{ flow?.name || flowId }}</el-breadcrumb-item>
      <el-breadcrumb-item>版本历史</el-breadcrumb-item>
    </el-breadcrumb>

    <div class="mb-3 flex items-center gap-2">
      <el-button @click="router.push(`/flows/design/${flowId}`)">
        回设计器
      </el-button>
      <div class="flex-1" />
      <span class="text-xs text-gray-400">
        勾选两个版本对比（节点级 + 连线级）
      </span>
    </div>

    <el-table :data="versions" border stripe>
      <el-table-column width="60">
        <template #default="{ row }">
          <el-checkbox
            :model-value="picked.includes(row.version)"
            @change="togglePick(row.version)"
          />
        </template>
      </el-table-column>
      <el-table-column label="版本" width="90">
        <template #default="{ row }">
          <el-tag
            size="small"
            :type="row.version === flow?.publishedVersion ? 'success' : 'info'"
          >
            V{{ row.version }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="publishedBy" label="发布人" width="140" />
      <el-table-column prop="publishedAt" label="发布时间" width="200" />
      <el-table-column label="节点数" width="90">
        <template #default="{ row }">
          {{ parse(row.dsl)?.nodes.length ?? "-" }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag
            v-if="row.version === flow?.publishedVersion"
            size="small"
            type="success"
          >
            当前生效
          </el-tag>
          <span v-else class="text-xs text-gray-400">历史版本</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :loading="rolling"
            :disabled="row.version === flow?.publishedVersion"
            @click="rollback(row)"
          >
            回滚到此版本
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="还没有发布过版本——在设计器里点发布" />
      </template>
    </el-table>

    <!-- 对比面板 -->
    <div v-if="picked.length === 2" class="mt-4">
      <div class="mb-2 flex items-center gap-3">
        <span class="font-medium">
          V{{ compare.a }} → V{{ compare.b }} 对比
        </span>
        <el-tag size="small" type="success">新增 {{ diffCount.added }}</el-tag>
        <el-tag size="small" type="danger">删除 {{ diffCount.removed }}</el-tag>
        <el-tag size="small" type="warning">修改 {{ diffCount.modified }}</el-tag>
      </div>
      <el-alert
        v-if="!compare.rows.length"
        type="success"
        :closable="false"
        show-icon
        title="两个版本在节点与连线层面完全一致"
      />
      <el-table v-else :data="compare.rows" border size="small">
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.kind)">
              {{ row.kind }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="layer" label="层" width="80" />
        <el-table-column prop="key" label="标识" min-width="200">
          <template #default="{ row }">
            <span class="font-mono text-xs">{{ row.key }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="差异" min-width="280" />
      </el-table>
    </div>
  </div>
</template>
