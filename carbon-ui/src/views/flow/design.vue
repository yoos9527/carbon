<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, shallowRef } from "vue";
import { useRoute, useRouter } from "vue-router";
import LogicFlow, {
  CircleNode,
  CircleNodeModel,
  DiamondNode,
  DiamondNodeModel,
  RectNode,
  RectNodeModel
} from "@logicflow/core";
import "@logicflow/core/lib/style/index.css";
import { ElMessage, ElMessageBox } from "element-plus";
import { getFlow, validateFlowDsl, updateFlow, publishFlow, runFlowAsync, type Flow, type ValidationError } from "@/api/flow";
import { listAllEndpoints } from "@/api/service";
import { pageOperations, type Endpoint, type Operation } from "@/api/endpoint";
import { showError } from "@/utils/http";

defineOptions({ name: "FlowDesign" });

const route = useRoute();
const router = useRouter();
const flowId = computed(() => String(route.params.id ?? ""));

/* ------------------------------------------------------------------ 画布类型 */

const KIND = {
  START: "START",
  CALL: "CONNECTOR_CALL",
  COND: "CONDITION",
  END: "END"
} as const;
type Kind = (typeof KIND)[keyof typeof KIND];

const KIND_META: Record<Kind, { label: string; color: string; shape: string }> = {
  START: { label: "开始", color: "#16a34a", shape: "circle" },
  CONNECTOR_CALL: { label: "三方服务", color: "#2563eb", shape: "rect" },
  CONDITION: { label: "判断", color: "#d97706", shape: "diamond" },
  END: { label: "结束", color: "#dc2626", shape: "circle" }
};

/* 用内置图元 + 覆写样式，避免为四种节点各抄一遍自定义 view/model */
class StartModel extends CircleNodeModel {
  setAttributes() {
    this.r = 26;
  }
  getNodeStyle() {
    return { ...super.getNodeStyle(), fill: "#f0fdf4", stroke: "#16a34a", strokeWidth: 2 };
  }
}
class EndModel extends CircleNodeModel {
  setAttributes() {
    this.r = 26;
  }
  getNodeStyle() {
    return { ...super.getNodeStyle(), fill: "#fef2f2", stroke: "#dc2626", strokeWidth: 2 };
  }
}
class CallModel extends RectNodeModel {
  setAttributes() {
    this.width = 168;
    this.height = 56;
    this.radius = 8;
  }
  getNodeStyle() {
    return { ...super.getNodeStyle(), fill: "#eff6ff", stroke: "#2563eb", strokeWidth: 2 };
  }
}
class CondModel extends DiamondNodeModel {
  setAttributes() {
    this.rx = 64;
    this.ry = 40;
  }
  getNodeStyle() {
    return { ...super.getNodeStyle(), fill: "#fffbeb", stroke: "#d97706", strokeWidth: 2 };
  }
}
/** SCRIPT / LOOP / FOREACH / DELAY：本期画布不编辑，但必须原样保留，用灰色和四方区分 */
class ForeignModel extends RectNodeModel {
  setAttributes() {
    this.width = 168;
    this.height = 56;
    this.radius = 4;
  }
  getNodeStyle() {
    return { ...super.getNodeStyle(), fill: "#f8fafc", stroke: "#94a3b8", strokeWidth: 2, strokeDasharray: "6,4" };
  }
}

/* ------------------------------------------------------------------ 状态 */

const lf = shallowRef<LogicFlow>();
const container = ref<HTMLElement>();
const loading = ref(false);
const saving = ref(false);
const dirty = ref(false);
const flow = ref<Flow>();
const errors = ref<Array<ValidationError>>([]);
const selected = ref<{ id: string; kind: Kind; foreign?: boolean } | null>(null);
const prop = reactive({
  label: "",
  connector: "",
  operation: "",
  expr: "",
  inputText: "{}",
  outputText: "{}",
  onError: "STOP",
  retryEnabled: false,
  maxAttempts: 2,
  backoffMs: 500
});

const connectors = ref<Array<Endpoint>>([]);
const operations = ref<Array<Operation>>();

let idSeq = 1;
/** 原 DSL 的顶层字段（version / timeoutMs 等）：画布不编辑它们，但保存时必须原样带回去 */
let baseMeta: { version?: number; timeoutMs?: number } = {};
function nextId() {
  return `n${idSeq++}`;
}

/* ------------------------------------------------------------------ DSL ⇄ 画布 */

interface DslNode {
  type: string;
  id: string;
  label?: string;
  connector?: string;
  operation?: string;
  input?: Record<string, unknown>;
  output?: Record<string, string>;
  onError?: string;
  retry?: Record<string, unknown>;
  expr?: string;
  branches?: Record<string, string>;
}

interface Dsl {
  version: number;
  nodes: Array<DslNode>;
  edges: Array<{ source: string; target: string }>;
  timeoutMs?: number;
}

function kindOfType(type: string): Kind {
  switch (type) {
    case "START":
      return KIND.START;
    case "END":
      return KIND.END;
    case "CONDITION":
      return KIND.COND;
    default:
      return KIND.CALL;
  }
}

/** 本期画布只编辑这 4 类；其余类型（SCRIPT/LOOP/FOREACH/DELAY）只读，保存时原样带回 */
const EDITABLE_TYPES = ["START", "END", "CONDITION", "CONNECTOR_CALL"];
const FOREIGN_LABELS: Record<string, string> = {
  SCRIPT: "脚本",
  LOOP: "循环",
  FOREACH: "遍历",
  DELAY: "延时"
};
function isForeign(type: string) {
  return !EDITABLE_TYPES.includes(type);
}

function lfType(kind: Kind) {
  return kind === KIND.START
    ? "c-start"
    : kind === KIND.END
      ? "c-end"
      : kind === KIND.COND
        ? "c-cond"
        : "c-call";
}

/**
 * DSL → 画布。
 *
 * DSL 里没有坐标（后端只存语义），所以按 BFS 分层自动摆位：深度决定列，同层顺序决定行。
 * 用户拖完保存后坐标就丢了——这是当前 DSL 的已知缺陷，docs/09 §3 里记着"补 coordinates"待办。
 */
function toCanvas(dsl: Dsl) {
  const kindById = new Map<string, Kind>();
  dsl.nodes.forEach(n => kindById.set(n.id, kindOfType(n.type)));

  const out = new Map<string, Array<string>>();
  const indeg = new Map<string, number>();
  dsl.nodes.forEach(n => indeg.set(n.id, 0));
  dsl.edges.forEach(e => {
    out.set(e.source, [...(out.get(e.source) ?? []), e.target]);
    indeg.set(e.target, (indeg.get(e.target) ?? 0) + 1);
  });

  const depth = new Map<string, number>();
  const queue = dsl.nodes.filter(n => (indeg.get(n.id) ?? 0) === 0).map(n => n.id);
  queue.forEach(id => depth.set(id, 0));
  const pending = [...queue];
  while (pending.length) {
    const id = pending.shift()!;
    const d = depth.get(id) ?? 0;
    (out.get(id) ?? []).forEach(t => {
      if (!depth.has(t) || (depth.get(t) ?? 0) < d + 1) {
        depth.set(t, d + 1);
        pending.push(t);
      }
    });
  }

  const rowOfDepth = new Map<number, number>();
  const nodes = dsl.nodes.map(n => {
    const d = depth.get(n.id) ?? 0;
    const row = rowOfDepth.get(d) ?? 0;
    rowOfDepth.set(d, row + 1);
    const kind = kindOfType(n.type);
    const foreign = isForeign(n.type);
    return {
      id: n.id,
      type: foreign ? "c-foreign" : lfType(kind),
      x: 120 + d * 240,
      y: 110 + row * 110,
      text: n.label || (foreign ? FOREIGN_LABELS[n.type] ?? n.type : KIND_META[kind].label),
      properties: {
        kind,
        foreign,
        // 保留原始节点对象：非本期类型（脚本/循环等）保存时必须整颗带回，否则会被改写成 CONNECTOR_CALL
        raw: n,
        label: n.label ?? "",
        connector: n.connector ?? "",
        operation: n.operation ?? "",
        expr: n.expr ?? "",
        input: n.input ?? {},
        output: n.output ?? {},
        onError: n.onError ?? "STOP",
        retry: n.retry ?? null
      }
    };
  });

  const branchOf = new Map<string, string>();
  dsl.nodes.forEach(n => {
    if (n.branches) {
      Object.entries(n.branches).forEach(([b, target]) => {
        branchOf.set(`${n.id}->${target}`, b);
      });
    }
  });

  const edges = dsl.edges.map(e => {
    const branch = branchOf.get(`${e.source}->${e.target}`);
    return {
      id: `e_${e.source}_${e.target}`,
      type: "polyline",
      sourceNodeId: e.source,
      targetNodeId: e.target,
      properties: { branch: branch ?? null },
      text: branch === "true" ? "真" : branch === "false" ? "假" : ""
    };
  });

  return { nodes, edges };
}

function buildDsl(): Dsl {
  const data = lf.value!.getGraphRawData();
  const kinds = new Map<string, Kind>();
  data.nodes.forEach((n: any) => kinds.set(n.id, n.properties?.kind ?? KIND.CALL));

  const outEdges = new Map<string, Array<any>>();
  data.edges.forEach((e: any) => {
    outEdges.set(e.sourceNodeId, [...(outEdges.get(e.sourceNodeId) ?? []), e]);
  });

  const nodes: Array<DslNode> = data.nodes.map((n: any) => {
    const p = n.properties ?? {};
    const kind: Kind = p.kind ?? KIND.CALL;
    const id = n.id;
    // 非本期节点（脚本/循环/遍历/延时）整颗原样带回，只允许改 label
    if (p.foreign && p.raw) {
      const raw: any = { ...p.raw, id };
      if (p.label) raw.label = p.label;
      else delete raw.label;
      return raw as DslNode;
    }
    if (kind === KIND.START) return { type: "START", id };
    if (kind === KIND.END) return { type: "END", id };
    if (kind === KIND.COND) {
      const es = outEdges.get(id) ?? [];
      // 分支键优先取连线上的显式标记，没标就按连线顺序补：第一条真、第二条假
      const explicit = (b: string) => es.find(e => e.properties?.branch === b)?.targetNodeId;
      const trueTarget = explicit("true") ?? es[0]?.targetNodeId;
      const falseTarget = explicit("false") ?? es.find(e => e.targetNodeId !== trueTarget)?.targetNodeId;
      const branches: Record<string, string> = {};
      if (trueTarget) branches.true = trueTarget;
      if (falseTarget) branches.false = falseTarget;
      return { type: "CONDITION", id, expr: p.expr ?? "", branches };
    }
    const node: DslNode = {
      type: "CONNECTOR_CALL",
      id,
      label: p.label || undefined,
      connector: p.connector,
      operation: p.operation,
      input: p.input ?? {},
      output: p.output ?? {},
      onError: p.onError ?? "STOP"
    };
    if (p.retry) node.retry = p.retry;
    return node;
  });

  return {
    version: baseMeta.version ?? 1,
    ...(baseMeta.timeoutMs ? { timeoutMs: baseMeta.timeoutMs } : {}),
    nodes,
    edges: data.edges.map((e: any) => ({
      source: e.sourceNodeId,
      target: e.targetNodeId
    }))
  };
}

/* ------------------------------------------------------------------ 初始化 */

function registerShapes(instance: LogicFlow) {
  instance.register({ type: "c-start", view: CircleNode, model: StartModel });
  instance.register({ type: "c-end", view: CircleNode, model: EndModel });
  instance.register({ type: "c-call", view: RectNode, model: CallModel });
  instance.register({ type: "c-cond", view: DiamondNode, model: CondModel });
  instance.register({ type: "c-foreign", view: RectNode, model: ForeignModel });
}

async function loadFlow() {
  loading.value = true;
  try {
    const f = await getFlow(flowId.value);
    flow.value = f;
    let dsl: Dsl | undefined;
    if (f.draftDsl) {
      try {
        dsl = JSON.parse(f.draftDsl) as Dsl;
      } catch {
        ElMessage.warning("草稿 DSL 不是合法 JSON，已按空画布打开");
      }
    }
    if (!dsl || !dsl.nodes?.length) {
      dsl = {
        version: 1,
        nodes: [
          { type: "START", id: "start" },
          { type: "END", id: "end" }
        ],
        edges: [{ source: "start", target: "end" }]
      };
    }
    dsl.nodes.forEach(n => {
      const m = /^n(\d+)$/.exec(n.id);
      if (m) idSeq = Math.max(idSeq, Number(m[1]) + 1);
    });
    baseMeta = { version: dsl.version, timeoutMs: dsl.timeoutMs };
    lf.value!.render(toCanvas(dsl));
    // 布局是 BFS 算出来的，节点多了会超出可视区，渲染后自适应一次
    requestAnimationFrame(() => lf.value?.fitView(60, 60));
  } catch (e) {
    showError(e);
  } finally {
    loading.value = false;
    dirty.value = false;
  }
}

async function loadConnectors() {
  try {
    const { connectors: list } = await listAllEndpoints();
    connectors.value = list.filter(c => c.status === 1);
  } catch {
    // 拉不到接入点不影响画布，只影响右侧下拉
  }
}

/** 画布上的接入点按合作方分组展示：选的时候人想的是「哪家公司的接口」 */
const connectorGroups = computed(() => {
  const map = new Map<string, { name: string; items: Array<Endpoint> }>();
  connectors.value.forEach(c => {
    const key = c.partnerId ?? "-";
    const name = c.partnerName || "未分组";
    if (!map.has(key)) map.set(key, { name, items: [] });
    map.get(key)!.items.push(c);
  });
  return [...map.values()];
});

async function loadOperations(connectorId: string) {
  operations.value = undefined;
  if (!connectorId) return;
  try {
    const page = await pageOperations(connectorId, { pageNum: 1, pageSize: 200 });
    operations.value = page.rows.filter(o => o.status === 1);
  } catch (e) {
    showError(e);
  }
}

/* ------------------------------------------------------------------ 交互 */

const palette = [
  { kind: KIND.START, hint: "唯一入口，只能有一个" },
  { kind: KIND.CALL, hint: "调用一个数据源服务接口" },
  { kind: KIND.COND, hint: "布尔表达式二选一分支" },
  { kind: KIND.END, hint: "可以有多个出口" }
];

function addNode(kind: Kind, at?: { x: number; y: number }) {
  const instance = lf.value!;
  if (kind === KIND.START) {
    const has = instance.getGraphRawData().nodes.some((n: any) => n.properties?.kind === KIND.START);
    if (has) {
      ElMessage.warning("开始节点只能有一个");
      return;
    }
  }
  const client = instance.getPointByClient(
    (container.value?.getBoundingClientRect().left ?? 0) +
      (container.value?.clientWidth ?? 400) / 2,
    (container.value?.getBoundingClientRect().top ?? 0) +
      (container.value?.clientHeight ?? 300) / 2
  );
  const point = at ?? client.canvasOverlayPosition;
  instance.addNode({
    id: nextId(),
    type: lfType(kind),
    x: point.x,
    y: point.y,
    text: KIND_META[kind].label,
    properties: {
      kind,
      label: "",
      connector: "",
      operation: "",
      expr: "",
      input: {},
      output: {},
      onError: "STOP",
      retry: null
    }
  });
  dirty.value = true;
}

function onDragStart(ev: DragEvent, kind: Kind) {
  ev.dataTransfer?.setData("text/plain", kind);
  if (ev.dataTransfer) ev.dataTransfer.effectAllowed = "move";
}

function onDrop(ev: DragEvent) {
  const kind = ev.dataTransfer?.getData("text/plain") as Kind | undefined;
  if (!kind) return;
  const instance = lf.value!;
  const point = instance.getPointByClient(ev.clientX, ev.clientY).canvasOverlayPosition;
  addNode(kind, point);
}

function syncSelected(data: any) {
  const p = data.properties ?? {};
  selected.value = { id: data.id, kind: p.kind ?? KIND.CALL, foreign: !!p.foreign };
  prop.label = p.label ?? "";
  prop.connector = p.connector ?? "";
  prop.operation = p.operation ?? "";
  prop.expr = p.expr ?? "";
  prop.inputText = JSON.stringify(p.input ?? {}, null, 2);
  prop.outputText = JSON.stringify(p.output ?? {}, null, 2);
  prop.onError = p.onError ?? "STOP";
  prop.retryEnabled = !!p.retry;
  prop.maxAttempts = p.retry?.maxAttempts ?? 2;
  prop.backoffMs = p.retry?.backoffMs ?? 500;
  if (prop.connector) loadOperations(prop.connector);
}

function applyProps() {
  if (!selected.value) return;
  const instance = lf.value!;
  const id = selected.value.id;
  let input: Record<string, unknown> = {};
  let output: Record<string, string> = {};
  try {
    input = prop.inputText.trim() ? JSON.parse(prop.inputText) : {};
    output = prop.outputText.trim() ? JSON.parse(prop.outputText) : {};
  } catch {
    ElMessage.error("入参/出参不是合法 JSON");
    return;
  }
  const patch: Record<string, unknown> = {
    label: prop.label,
    onError: prop.onError
  };
  if (selected.value.kind === KIND.CALL) {
    patch.connector = prop.connector;
    patch.operation = prop.operation;
    patch.input = input;
    patch.output = output;
    patch.retry = prop.retryEnabled
      ? { maxAttempts: prop.maxAttempts, backoffMs: prop.backoffMs }
      : null;
  }
  if (selected.value.kind === KIND.COND) {
    patch.expr = prop.expr;
  }
  instance.setProperties(id, patch);
  instance.updateText(
    id,
    prop.label || KIND_META[selected.value.kind].label
  );
  dirty.value = true;
  ElMessage.success("已应用到节点");
}

function removeSelected() {
  if (!selected.value) return;
  lf.value!.deleteNode(selected.value.id);
  selected.value = null;
  dirty.value = true;
}

/* ------------------------------------------------------------------ 保存 / 校验 / 发布 / 试跑 */

async function save() {
  saving.value = true;
  try {
    const dsl = buildDsl();
    await updateFlow(flowId.value, {
      code: flow.value!.code,
      name: flow.value!.name,
      draftDsl: JSON.stringify(dsl),
      status: flow.value!.status
    } as any);
    dirty.value = false;
    ElMessage.success("草稿已保存");
  } catch (e) {
    showError(e);
  } finally {
    saving.value = false;
  }
}

async function validate() {
  try {
    const list = await validateFlowDsl(JSON.stringify(buildDsl()));
    errors.value = list;
    if (!list.length) ElMessage.success("校验通过，可以发布");
    else ElMessage.warning(`发现 ${list.length} 处问题`);
  } catch (e) {
    showError(e);
  }
}

function focusNode(nodeId?: string) {
  if (!nodeId) return;
  const instance = lf.value!;
  const model = instance.getNodeModelById(nodeId);
  if (!model) return;
  instance.focusOn({ coordinate: { x: model.x, y: model.y } });
  instance.selectElementById(nodeId);
  const data = instance.getNodeDataById(nodeId);
  if (data) syncSelected(data);
}

async function publish() {
  try {
    await ElMessageBox.confirm(
      "发布会把当前草稿固化成不可变版本，试跑跑的就是它。继续？",
      "发布流程",
      { type: "warning" }
    );
  } catch {
    return;
  }
  try {
    if (dirty.value) await save();
    await publishFlow(flowId.value);
    ElMessage.success("已发布");
    await loadFlow();
  } catch (e) {
    showError(e);
  }
}

const runDialog = reactive({ visible: false, inputText: "{}", running: false });

async function doRun() {
  let input: Record<string, unknown> = {};
  try {
    input = runDialog.inputText.trim() ? JSON.parse(runDialog.inputText) : {};
  } catch {
    ElMessage.error("入参不是合法 JSON");
    return;
  }
  runDialog.running = true;
  try {
    const run = await runFlowAsync(flowId.value, input);
    runDialog.visible = false;
    ElMessage.success("已提交执行，跳到运行记录看进度");
    router.push({ path: "/flow-runs", query: { runId: String(run.id) } });
  } catch (e) {
    showError(e);
  } finally {
    runDialog.running = false;
  }
}

async function tryRun() {
  if (!flow.value?.publishedVersion) {
    ElMessage.warning("还没有发布版本，先发布再试跑（试跑跑的是已发布版本，不是草稿）");
    return;
  }
  runDialog.visible = true;
}

/* ------------------------------------------------------------------ 生命周期 */

function initCanvas() {
  const instance = new LogicFlow({
    container: container.value!,
    grid: { size: 10, visible: true, type: "dot" },
    keyboard: { enabled: true },
    edgeType: "polyline",
    stopScrollGraph: false,
    nodeTextEdit: false,
    edgeTextEdit: false
  });
  registerShapes(instance);
  lf.value = instance;

  instance.on("node:click", ({ data }: any) => syncSelected(data));
  instance.on("node:dnd-drag", () => {
    dirty.value = true;
  });
  instance.on("node:add", () => {
    dirty.value = true;
  });
  instance.on("edge:add", ({ data }: any) => {
    dirty.value = true;
    const source = instance.getNodeDataById(data.sourceNodeId);
    const kind = source?.properties?.kind;
    if (kind === KIND.END) {
      instance.deleteEdge(data.id);
      ElMessage.warning("结束节点不能再连出边");
      return;
    }
    if (kind !== KIND.COND) {
      const out = instance.getGraphRawData().edges.filter(
        (e: any) => e.sourceNodeId === data.sourceNodeId && e.id !== data.id
      );
      if (out.length > 0) {
        instance.deleteEdge(data.id);
        ElMessage.warning("只有判断节点可以有两条出边");
        return;
      }
      return;
    }
    // 判断节点：按已有出边数自动决定这条是真分支还是假分支
    const out = instance.getGraphRawData().edges.filter(
      (e: any) => e.sourceNodeId === data.sourceNodeId && e.id !== data.id
    );
    const used = new Set(out.map((e: any) => e.properties?.branch));
    const branch = used.has("true") ? "false" : "true";
    instance.setProperties(data.id, { branch });
    instance.updateText(data.id, branch === "true" ? "真" : "假");
  });
  instance.on("edge:delete", () => {
    dirty.value = true;
  });
  instance.on("node:delete", () => {
    dirty.value = true;
  });

  Promise.all([loadFlow(), loadConnectors()]).then(() => {
    instance.resize();
  });
}

function onResize() {
  lf.value?.resize();
}

function fitView() {
  lf.value?.fitView();
}

onMounted(() => {
  initCanvas();
  window.addEventListener("resize", onResize);
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", onResize);
  lf.value?.destroy();
});

const currentKindLabel = computed(() =>
  selected.value ? KIND_META[selected.value.kind].label : ""
);

/* 拖入节点后把最后一个节点选中，省得用户再点一次 */
function paletteClick(kind: Kind) {
  addNode(kind);
}
</script>

<template>
  <div class="flex h-[calc(100vh-100px)] flex-col p-3">
    <!-- 顶部工具条 -->
    <div class="mb-2 flex flex-wrap items-center gap-2">
      <el-button text @click="router.push('/flows')">← 流程列表</el-button>
      <span class="font-medium">{{ flow?.name || "加载中…" }}</span>
      <el-tag v-if="flow?.publishedVersion" size="small" type="success">
        已发布 v{{ flow.publishedVersion }}
      </el-tag>
      <el-tag v-else size="small" type="warning">未发布</el-tag>
      <el-tag v-if="dirty" size="small" type="info">有未保存修改</el-tag>
      <div class="flex-1" />
      <el-button @click="validate">校验</el-button>
      <el-button :loading="saving" @click="save">保存草稿</el-button>
      <el-button type="primary" @click="publish">发布</el-button>
      <el-button @click="tryRun">试跑</el-button>
      <el-button
        text
        @click="router.push(`/flows/${flowId}/versions`)"
      >
        版本历史
      </el-button>
    </div>

    <div class="flex min-h-0 flex-1 gap-2">
      <!-- 左：节点面板 -->
      <el-card class="w-48 shrink-0 overflow-auto" shadow="never" body-class="p-2">
        <div class="mb-2 text-xs text-gray-400">
          拖到画布，或单击加到中央
        </div>
        <div
          v-for="p in palette"
          :key="p.kind"
          class="mb-2 cursor-move rounded border border-dashed px-2 py-2 text-center text-sm hover:bg-gray-50 dark:hover:bg-gray-800"
          :style="{ borderColor: KIND_META[p.kind].color }"
          draggable="true"
          @dragstart="onDragStart($event, p.kind)"
          @click="paletteClick(p.kind)"
        >
          <div class="font-medium" :style="{ color: KIND_META[p.kind].color }">
            {{ KIND_META[p.kind].label }}
          </div>
          <div class="text-xs text-gray-400">{{ p.hint }}</div>
        </div>
        <el-divider />
        <div class="text-xs leading-5 text-gray-500">
          <div>本期只开放 4 类节点。</div>
          <div class="mt-1">
            规则集/评分卡/模型/子流程不在这里——它们的上游资产还不存在。
          </div>
        </div>
      </el-card>

      <!-- 中：画布 -->
      <div
        ref="container"
        v-loading="loading"
        class="min-w-0 flex-1 rounded border border-gray-200 dark:border-gray-700"
        @dragover.prevent
        @drop.prevent="onDrop"
      />
      <!-- LogicFlow 会在挂载后自行往容器里插 svg，这里保留一个独立容器由 JS 控制 -->

      <!-- 右：属性面板 -->
      <el-card class="w-96 shrink-0 overflow-auto" shadow="never" body-class="p-3">
        <template v-if="selected">
          <div class="mb-2 flex items-center gap-2">
            <span class="font-medium">{{ currentKindLabel }}</span>
            <span class="font-mono text-xs text-gray-400">{{ selected.id }}</span>
            <div class="flex-1" />
            <el-button link type="danger" @click="removeSelected">删除</el-button>
          </div>

          <el-form label-width="76px" size="small" label-position="top">
            <el-alert
              v-if="selected.foreign"
              class="mb-2"
              type="info"
              :closable="false"
              show-icon
              title="该节点类型本期画布不编辑"
              description="脚本 / 循环 / 遍历 / 延时节点在这里只读展示，保存时原样写回 DSL，不会被改成三方服务。只能改名称。"
            />
            <el-form-item v-if="selected.kind !== KIND.START && selected.kind !== KIND.END" label="节点名称">
              <el-input v-model="prop.label" maxlength="32" placeholder="留空则显示类型名" />
            </el-form-item>

            <template v-if="selected.kind === KIND.CALL && !selected.foreign">
              <el-form-item label="接入点">
                <el-select
                  v-model="prop.connector"
                  filterable
                  placeholder="选择接入点"
                  class="w-full"
                  @change="loadOperations"
                >
                  <el-option-group
                    v-for="g in connectorGroups"
                    :key="g.name"
                    :label="g.name"
                  >
                    <el-option
                      v-for="c in g.items"
                      :key="c.id"
                      :label="`${c.name} (${c.code})`"
                      :value="c.code"
                    />
                  </el-option-group>
                </el-select>
                <div class="mt-1 text-xs text-gray-400">
                  按合作方分组；密钥在合作方，这里只决定调哪个地址
                </div>
              </el-form-item>
              <el-form-item label="接口">
                <el-select
                  v-model="prop.operation"
                  filterable
                  :disabled="!prop.connector"
                  :loading="!operations"
                  placeholder="选择接口"
                  class="w-full"
                >
                  <el-option
                    v-for="o in operations ?? []"
                    :key="o.id"
                    :label="`${o.method} ${o.path} — ${o.name}`"
                    :value="o.code"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="入参（变量名 → 模板串）">
                <el-input
                  v-model="prop.inputText"
                  type="textarea"
                  :rows="4"
                  class="font-mono"
                  placeholder='{"keyword": "{{input.name}}"}'
                />
              </el-form-item>
              <el-form-item label="出参（变量名 → JSONPath）">
                <el-input
                  v-model="prop.outputText"
                  type="textarea"
                  :rows="3"
                  class="font-mono"
                  placeholder='{"hit": "$.data.hit"}'
                />
              </el-form-item>
              <el-form-item label="失败处置">
                <el-select v-model="prop.onError" class="w-full">
                  <el-option label="STOP 中断流程" value="STOP" />
                  <el-option label="CONTINUE 忽略继续" value="CONTINUE" />
                </el-select>
              </el-form-item>
              <el-form-item label="重试">
                <el-switch v-model="prop.retryEnabled" />
                <div v-if="prop.retryEnabled" class="mt-2 flex w-full items-center gap-2">
                  <el-input-number v-model="prop.maxAttempts" :min="1" :max="5" size="small" />
                  <span class="text-xs text-gray-400">次，间隔</span>
                  <el-input-number v-model="prop.backoffMs" :min="0" :step="100" size="small" />
                  <span class="text-xs text-gray-400">ms</span>
                </div>
              </el-form-item>
            </template>

            <template v-if="selected.kind === KIND.COND">
              <el-form-item label="布尔表达式">
                <el-input
                  v-model="prop.expr"
                  type="textarea"
                  :rows="3"
                  class="font-mono"
                  placeholder="vars.score >= 600"
                />
              </el-form-item>
              <el-alert
                type="info"
                :closable="false"
                show-icon
                title="必须连出两条边"
                description="第一条自动标记为「真」，第二条为「假」；两条分支必须汇合到同一个节点，否则后端会报 NO_CONVERGENCE。"
              />
            </template>

            <el-button
              v-if="selected.kind !== KIND.START && selected.kind !== KIND.END"
              class="mt-3 w-full"
              type="primary"
              @click="applyProps"
            >
              应用
            </el-button>
          </el-form>
        </template>
        <el-empty v-else description="点画布上的节点查看属性" :image-size="80" />

        <template v-if="errors.length">
          <el-divider>校验结果（{{ errors.length }}）</el-divider>
          <div
            v-for="(e, i) in errors"
            :key="i"
            class="mb-2 cursor-pointer rounded border border-red-200 bg-red-50 p-2 text-xs dark:bg-red-950"
            @click="focusNode(e.nodeId)"
          >
            <div class="font-mono text-red-600">{{ e.code }}</div>
            <div>{{ e.message }}</div>
            <div v-if="e.nodeId" class="mt-1 text-gray-400">点此定位节点 {{ e.nodeId }}</div>
          </div>
        </template>
      </el-card>
    </div>

    <div class="mt-1 flex items-center gap-3 text-xs text-gray-400">
      <el-button link @click="fitView">适应画布</el-button>
      <span>Backspace 删除选中节点 · Ctrl+滚轮缩放</span>
      <div class="flex-1" />
      <span>共 {{ lf ? lf.getGraphRawData().nodes.length : 0 }} 个节点</span>
    </div>

    <el-dialog v-model="runDialog.visible" title="试跑已发布版本" width="520px">
      <el-alert
        class="mb-2"
        type="warning"
        :closable="false"
        show-icon
        title="跑的是已发布版本，不是当前草稿"
        description="改完草稿记得先发布，否则试跑的还是老版本。"
      />
      <el-input
        v-model="runDialog.inputText"
        type="textarea"
        :rows="10"
        class="font-mono"
        placeholder='{"name": "张三"}'
      />
      <template #footer>
        <el-button @click="runDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="runDialog.running" @click="doRun">
          执行
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
:deep(.lf-graph) {
  background: #fafafa;
}
</style>
