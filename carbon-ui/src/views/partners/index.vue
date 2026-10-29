<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  createPartner,
  deletePartner,
  pagePartners,
  partnerStatusMeta,
  updatePartner,
  updatePartnerStatus,
  PARTNER_STATUS,
  type Partner
} from "@/api/partner";
import { showError } from "@/utils/http";

defineOptions({ name: "PartnerList" });

/**
 * 合作方列表。
 *
 * 这一页只回答「哪家公司、还能不能用、合同到哪」。地址在接入点，密钥在凭证，
 * 停用这里会级联停掉它旗下所有接入点——所以改状态要先把影响面摊开给用户看。
 */
const router = useRouter();
const route = useRoute();
const loading = ref(false);
const rows = ref<Array<Partner>>([]);
const total = ref(0);

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: "",
  status: undefined as number | undefined
});

async function load() {
  loading.value = true;
  try {
    const page = await pagePartners({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined,
      status: query.status
    });
    rows.value = page.rows;
    total.value = page.total;
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
  query.status = undefined;
  search();
}

/**
 * 改状态：先看影响面再动手。
 *
 * 后端 updateStatus 会回『会连带多少接入点/接口/凭证失效』，
 * 这里直接把这串数字念给用户听，比一句「确定停用？」有用得多。
 */
async function changeStatus(row: Partner, next: number) {
  if (next === row.status) return;
  const meta = partnerStatusMeta(next);
  const danger = next !== 1;
  try {
    await ElMessageBox.confirm(
      danger
        ? `「${row.name}」将被标记为「${meta.label}」，它旗下 ${row.endpointCount ?? 0} 个接入点、${row.operationCount ?? 0} 个接口会立即全部不可调用（含编排里的节点）。确定？`
        : `「${row.name}」将恢复为「${meta.label}」。接入点是否可用仍取决于它们自己的启停状态。继续？`,
      "变更合作方状态",
      {
        type: danger ? "warning" : "info",
        confirmButtonText: "确定",
        cancelButtonText: "取消"
      }
    );
  } catch {
    return;
  }
  try {
    const impact = await updatePartnerStatus(row.id, next);
    row.status = next;
    if (danger && impact.operationCount > 0) {
      ElMessage.warning(
        `已${meta.label}：连带 ${impact.endpointCount} 个接入点、${impact.operationCount} 个接口失效`
      );
    } else {
      ElMessage.success(`已${meta.label}`);
    }
    load();
  } catch (e) {
    showError(e);
  }
}

async function remove(row: Partner) {
  try {
    await ElMessageBox.confirm(
      `确定删除合作方「${row.name}」？若它下面还有接入点，后端会拒绝删除并提示改为停用。`,
      "删除合作方",
      { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  try {
    await deletePartner(row.id);
    ElMessage.success("已删除");
    load();
  } catch (e) {
    showError(e);
  }
}

/* ---------------- 新建 / 编辑 ---------------- */
const dialog = reactive({ visible: false, saving: false, id: "" });
const form = reactive({
  code: "",
  name: "",
  status: 1,
  contactName: "",
  contactPhone: "",
  contractNo: "",
  priority: 100,
  remark: ""
});

function openCreate() {
  dialog.id = "";
  Object.assign(form, {
    code: "",
    name: "",
    status: 1,
    contactName: "",
    contactPhone: "",
    contractNo: "",
    priority: 100,
    remark: ""
  });
  dialog.visible = true;
}

function openEdit(row: Partner) {
  dialog.id = row.id;
  Object.assign(form, {
    code: row.code,
    name: row.name,
    status: row.status,
    contactName: row.contactName ?? "",
    contactPhone: row.contactPhone ?? "",
    contractNo: row.contractNo ?? "",
    priority: row.priority ?? 100,
    remark: row.remark ?? ""
  });
  dialog.visible = true;
}

async function save() {
  if (!form.code || !form.name) {
    ElMessage.warning("标识与名称必填");
    return;
  }
  dialog.saving = true;
  try {
    if (dialog.id) {
      await updatePartner(dialog.id, { ...form });
      ElMessage.success("已保存");
    } else {
      await createPartner({ ...form });
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

onMounted(() => {
  // 从接入点/接口页跳过来时带 ?focus=<partnerId>，预置成关键词方便定位
  const focus = route.query.focus;
  if (typeof focus === "string" && focus) query.keyword = "";
  load();
});
</script>

<template>
  <div class="p-4">
    <div class="mb-3 flex flex-wrap items-center gap-2">
      <el-input
        v-model="query.keyword"
        placeholder="名称、标识、合同号"
        clearable
        style="width: 220px"
        @keyup.enter="search"
        @clear="search"
      />
      <el-select
        v-model="query.status"
        placeholder="全部状态"
        clearable
        style="width: 130px"
        @change="search"
      >
        <el-option
          v-for="s in PARTNER_STATUS"
          :key="s.value"
          :label="s.label"
          :value="s.value"
        />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <div class="flex-1" />
      <el-button type="primary" @click="openCreate">新建合作方</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="name" label="合作方名称" min-width="150" />
      <el-table-column prop="code" label="标识" min-width="120">
        <template #default="{ row }">
          <span class="font-mono text-xs">{{ row.code }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-dropdown trigger="click" @command="(s: number) => changeStatus(row, s)">
            <el-tag
              :type="partnerStatusMeta(row.status).type"
              size="small"
              class="cursor-pointer"
            >
              {{ partnerStatusMeta(row.status).label }}
            </el-tag>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  v-for="s in PARTNER_STATUS"
                  :key="s.value"
                  :command="s.value"
                  :disabled="s.value === row.status"
                >
                  {{ s.label }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </el-table-column>
      <el-table-column prop="contactName" label="联系人" width="100">
        <template #default="{ row }">{{ row.contactName || "-" }}</template>
      </el-table-column>
      <el-table-column prop="contractNo" label="合同号" min-width="130">
        <template #default="{ row }">
          <span class="font-mono text-xs">{{ row.contractNo || "-" }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="endpointCount" label="接入点" width="80" />
      <el-table-column prop="operationCount" label="接口" width="70" />
      <el-table-column prop="credentialCount" label="凭证" width="70" />
      <el-table-column label="默认凭证" min-width="120">
        <template #default="{ row }">
          <span v-if="row.defaultCredentialName">
            {{ row.defaultCredentialName }}
          </span>
          <span v-else class="text-xs text-amber-600">未配置</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            @click="router.push(`/endpoints?partnerId=${row.id}`)"
          >
            接入点
          </el-button>
          <el-button
            link
            type="primary"
            @click="router.push(`/partners/${row.id}/credentials`)"
          >
            凭证
          </el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty
          :description="
            query.keyword || query.status !== undefined
              ? '筛选无结果，试试重置条件'
              : '还没有合作方。接入点必须挂在合作方下，所以先建一家'
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

    <el-dialog
      v-model="dialog.visible"
      :title="dialog.id ? '编辑合作方' : '新建合作方'"
      width="620px"
    >
      <el-form :model="form" label-width="100px">
        <el-form-item label="标识" required>
          <el-input
            v-model="form.code"
            :disabled="!!dialog.id"
            placeholder="小写字母/数字/下划线/短横线，如 qcc"
          />
          <div v-if="dialog.id" class="mt-1 text-xs text-gray-400">
            标识被编排与日志引用，创建后不可改
          </div>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 企查查" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option
              v-for="s in PARTNER_STATUS"
              :key="s.value"
              :label="s.label"
              :value="s.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="form.contactName" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="form.contactPhone" />
        </el-form-item>
        <el-form-item label="合同号">
          <el-input v-model="form.contractNo" placeholder="商务合同编号" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="0" :max="9999" />
          <span class="ml-2 text-xs text-gray-400">数字越小越优先</span>
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
