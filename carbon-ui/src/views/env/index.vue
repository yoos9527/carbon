<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  createEnv,
  deleteEnvs,
  listEnvs,
  setDefaultEnv,
  updateEnv,
  type Env,
  type EnvForm
} from "@/api/env";
import { showError } from "@/utils/http";
import { useEnvStoreHook } from "@/store/modules/env";

defineOptions({ name: "EnvList" });

const envStore = useEnvStoreHook();

const loading = ref(false);
const rows = ref<Array<Env>>([]);

async function load() {
  loading.value = true;
  try {
    rows.value = await listEnvs();
    // 当前选中的环境被删掉后，全局选择器会指向一个不存在的 code，
    // 表现为「试调一直 400 环境不存在」——发现就清掉，回落到默认环境
    if (
      envStore.current &&
      !rows.value.some(e => e.code === envStore.current)
    ) {
      envStore.set("");
    }
  } catch (e) {
    showError(e);
  } finally {
    loading.value = false;
  }
}

async function makeDefault(row: Env) {
  if (row.isDefault === 1) return;
  try {
    await setDefaultEnv(row.id);
    ElMessage.success(`已把「${row.name}」设为默认环境`);
    load();
  } catch (e) {
    showError(e);
  }
}

async function remove(row: Env) {
  const last = rows.value.length === 1;
  try {
    await ElMessageBox.confirm(
      last
        ? "这是最后一个环境。删掉后试调/编排会因为「尚未配置任何环境」直接失败，确定删除？"
        : row.isDefault === 1
          ? `「${row.name}」是默认环境，删除后会把剩下最早的环境提为默认。确定删除？`
          : `确定删除「${row.name}」？引用 {{env.${row.code}}} 的模板会取不到值。`,
      "删除环境",
      { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  try {
    await deleteEnvs([row.id]);
    ElMessage.success("已删除");
    load();
  } catch (e) {
    showError(e);
  }
}

/* ---------------- 新建 / 编辑 ---------------- */
type VarType = "string" | "number" | "boolean";
interface VarRow {
  key: string;
  type: VarType;
  value: string;
}

const dialog = reactive({
  visible: false,
  saving: false,
  /** 空串 = 新建，非空 = 编辑中的 id */
  editingId: ""
});
const form = reactive({
  code: "",
  name: "",
  baseUrlPrefix: "",
  remark: "",
  vars: [] as Array<VarRow>
});

const isEdit = computed(() => !!dialog.editingId);

function typeOf(value: unknown): VarType {
  if (typeof value === "number") return "number";
  if (typeof value === "boolean") return "boolean";
  return "string";
}

function openCreate() {
  dialog.editingId = "";
  Object.assign(form, {
    code: "",
    name: "",
    baseUrlPrefix: "",
    remark: "",
    vars: []
  });
  dialog.visible = true;
}

function openEdit(row: Env) {
  dialog.editingId = row.id;
  Object.assign(form, {
    code: row.code,
    name: row.name,
    baseUrlPrefix: row.baseUrlPrefix ?? "",
    remark: row.remark ?? "",
    vars: Object.entries(row.vars ?? {}).map(([key, value]) => ({
      key,
      type: typeOf(value),
      value: String(value)
    }))
  });
  dialog.visible = true;
}

function addVar() {
  form.vars.push({ key: "", type: "string", value: "" });
}

function removeVar(index: number) {
  form.vars.splice(index, 1);
}

/** 行内值 → 后端要的 JSON 值；拒绝半成品（空键、数字行填了非数字） */
function collectVars(): Record<string, string | number | boolean> {
  const out: Record<string, string | number | boolean> = {};
  form.vars.forEach((v, i) => {
    const key = v.key.trim();
    if (!key) throw new Error(`第 ${i + 1} 行的变量名为空`);
    if (key in out) throw new Error(`变量名重复：${key}`);
    if (v.type === "number") {
      const n = Number(v.value);
      if (v.value.trim() === "" || Number.isNaN(n)) {
        throw new Error(`变量 ${key} 需要填数字`);
      }
      out[key] = n;
    } else if (v.type === "boolean") {
      if (!["true", "false"].includes(v.value.trim())) {
        throw new Error(`变量 ${key} 只能是 true / false`);
      }
      out[key] = v.value.trim() === "true";
    } else {
      out[key] = v.value;
    }
  });
  return out;
}

async function save() {
  if (!form.code.trim() || !form.name.trim()) {
    ElMessage.warning("编码与名称必填");
    return;
  }
  let vars: Record<string, string | number | boolean>;
  try {
    vars = collectVars();
  } catch (e) {
    ElMessage.warning((e as Error).message);
    return;
  }
  const payload: EnvForm = {
    code: form.code.trim(),
    name: form.name.trim(),
    // 新建时传空串=清空；编辑时允许清空（后端把空串当「清空」处理）
    baseUrlPrefix: form.baseUrlPrefix.trim(),
    vars,
    remark: form.remark
  };
  dialog.saving = true;
  try {
    if (isEdit.value) {
      // code 输入框在编辑态禁改，这里带上的就是原值（后端 EnvBo 的 code 仍必填）
      await updateEnv(dialog.editingId, payload);
      ElMessage.success("已保存");
    } else {
      await createEnv(payload);
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

onMounted(load);
</script>

<template>
  <div class="p-4">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="mb-3"
      title="环境提供 {{env.xxx}} 变量"
      description="接入点只配一份地址，域名差异写在环境里；接入点 base_url 用 https://{{env.host}} 即可切换环境。{{env.code}} 与 {{env.baseUrlPrefix}} 由平台自动注入，不要在变量表里重名。"
    />

    <div class="mb-3 flex flex-wrap items-center gap-2">
      <el-tag type="success" effect="plain">
        当前选择：{{ envStore.current || "默认环境" }}
      </el-tag>
      <div class="flex-1" />
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="openCreate">新建环境</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="name" label="名称" min-width="140">
        <template #default="{ row }">
          <span class="mr-1">{{ row.name }}</span>
          <el-tag v-if="row.isDefault === 1" size="small" type="success">
            默认
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="code" label="编码" width="140">
        <template #default="{ row }">
          <span class="font-mono text-xs">{{ row.code }}</span>
        </template>
      </el-table-column>
      <el-table-column label="前缀" min-width="200">
        <template #default="{ row }">
          <span v-if="row.baseUrlPrefix" class="font-mono text-xs">
            {{ row.baseUrlPrefix }}
          </span>
          <span v-else class="text-xs text-gray-400">不拼接</span>
        </template>
      </el-table-column>
      <el-table-column label="变量" min-width="240">
        <template #default="{ row }">
          <span
            v-if="!Object.keys(row.vars || {}).length"
            class="text-xs text-gray-400"
          >
            无
          </span>
          <span v-else class="flex flex-wrap gap-1">
            <el-tag
              v-for="(v, k) in row.vars"
              :key="k"
              size="small"
              effect="plain"
            >
              {{ k }}={{ v }}
            </el-tag>
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="140">
        <template #default="{ row }">{{ row.remark || "-" }}</template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="180" />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button
            link
            type="primary"
            :disabled="row.isDefault === 1"
            @click="makeDefault(row)"
          >
            设为默认
          </el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="还没有环境，新建一个 dev 环境就能开始试调" />
      </template>
    </el-table>

    <el-dialog
      v-model="dialog.visible"
      :title="isEdit ? '编辑环境' : '新建环境'"
      width="640px"
    >
      <el-form :model="form" label-width="96px">
        <el-form-item label="编码" required>
          <el-input
            v-model="form.code"
            :disabled="isEdit"
            placeholder="小写字母开头，如 dev / test / staging"
          />
          <div v-if="isEdit" class="mt-1 text-xs text-gray-400">
            编码已被模板引用与日志记录，不支持修改
          </div>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 测试环境" />
        </el-form-item>
        <el-form-item label="URL 前缀">
          <el-input
            v-model="form.baseUrlPrefix"
            placeholder="如 https://api-test.example.com（留空=不拼接）"
          />
          <div class="mt-1 text-xs text-gray-400">
            不带 http(s):// 会自动补 http://，尾部斜杠会被去掉
          </div>
        </el-form-item>
        <el-form-item label="变量">
          <div class="w-full">
            <div
              v-for="(v, i) in form.vars"
              :key="i"
              class="mb-2 flex items-center gap-2"
            >
              <el-input
                v-model="v.key"
                placeholder="变量名，如 host"
                style="width: 180px"
              />
              <el-select v-model="v.type" style="width: 96px">
                <el-option label="字符串" value="string" />
                <el-option label="数字" value="number" />
                <el-option label="布尔" value="boolean" />
              </el-select>
              <el-select
                v-if="v.type === 'boolean'"
                v-model="v.value"
                style="flex: 1"
              >
                <el-option label="true" value="true" />
                <el-option label="false" value="false" />
              </el-select>
              <el-input
                v-else
                v-model="v.value"
                :placeholder="v.type === 'number' ? '数字，如 8080' : '值'"
                style="flex: 1"
              />
              <el-button link type="danger" @click="removeVar(i)">
                删除
              </el-button>
            </div>
            <el-button size="small" @click="addVar">添加变量</el-button>
            <div class="mt-1 text-xs text-gray-400">
              只支持字符串/数字/布尔（模板里要能直接拼进 URL 与报文）；
              code、baseUrlPrefix 是保留键
            </div>
          </div>
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
