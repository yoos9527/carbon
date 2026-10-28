<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import dayjs from "dayjs";
import {
  BUSINESS_TYPES,
  pageOperLogs,
  type OperLog
} from "@/api/system";
import { labelOf, tagOf } from "@/api/common";
import { showError } from "@/utils/http";

defineOptions({ name: "OperLogList" });

const loading = ref(false);
const rows = ref<Array<OperLog>>([]);
const total = ref(0);

const query = reactive({
  pageNum: 1,
  pageSize: 20,
  keyword: "",
  title: "",
  businessType: "",
  onlyFailed: false,
  range: [] as Array<string>
});

async function load() {
  loading.value = true;
  try {
    const [begin, end] = query.range ?? [];
    const page = await pageOperLogs({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined,
      title: query.title || undefined,
      businessType: query.businessType || undefined,
      onlyFailed: query.onlyFailed || undefined,
      beginTime: begin ? dayjs(begin).format("YYYY-MM-DDTHH:mm:ss") : undefined,
      endTime: end ? dayjs(end).format("YYYY-MM-DDTHH:mm:ss") : undefined
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
  query.title = "";
  query.businessType = "";
  query.onlyFailed = false;
  query.range = [];
  search();
}

/* 详情抽屉：params 可能几百字符，表格里放不下 */
const detail = reactive({ visible: false, row: undefined as OperLog | undefined });

function openDetail(row: OperLog) {
  detail.row = row;
  detail.visible = true;
}

function pretty(text?: string) {
  if (!text) return "";
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
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
      title="只有被 @Log 标注的写操作才会出现在这里"
      description="查询类接口不审计。所以这里看不到「谁看过某条数据」，只能看到「谁改过什么」。"
    />

    <div class="mb-3 flex flex-wrap items-center gap-2">
      <el-input
        v-model="query.keyword"
        placeholder="操作人 / URI / 关键字"
        clearable
        style="width: 200px"
        @keyup.enter="search"
        @clear="search"
      />
      <el-input
        v-model="query.title"
        placeholder="模块，如 流程"
        clearable
        style="width: 140px"
        @keyup.enter="search"
        @clear="search"
      />
      <el-select
        v-model="query.businessType"
        placeholder="全部类型"
        clearable
        style="width: 130px"
        @change="search"
      >
        <el-option
          v-for="t in BUSINESS_TYPES"
          :key="t.value"
          :label="t.label"
          :value="t.value"
        />
      </el-select>
      <el-date-picker
        v-model="query.range"
        type="datetimerange"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        value-format="YYYY-MM-DDTHH:mm:ss"
        style="width: 360px"
        @change="search"
      />
      <el-checkbox v-model="query.onlyFailed" @change="search">
        只看失败
      </el-checkbox>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="title" label="模块" width="110" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag
            size="small"
            :type="tagOf(BUSINESS_TYPES, row.businessType).type"
          >
            {{ labelOf(BUSINESS_TYPES, row.businessType) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="请求" min-width="260">
        <template #default="{ row }">
          <span class="font-mono text-xs">
            {{ row.requestMethod }} {{ row.requestUri }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 0 ? 'success' : 'danger'">
            {{ row.status === 0 ? "成功" : "失败" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="durationMs" label="耗时(ms)" width="100" />
      <el-table-column prop="clientIp" label="来源 IP" width="140" />
      <el-table-column prop="createdAt" label="时间" width="180" />
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">
            详情
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="没有匹配的操作记录" />
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

    <el-drawer v-model="detail.visible" title="操作详情" size="560px">
      <el-descriptions v-if="detail.row" :column="1" border>
        <el-descriptions-item label="模块">
          {{ detail.row.title }}
        </el-descriptions-item>
        <el-descriptions-item label="类型">
          {{ labelOf(BUSINESS_TYPES, detail.row.businessType) }}
        </el-descriptions-item>
        <el-descriptions-item label="方法">
          <span class="font-mono text-xs">{{ detail.row.method }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="请求">
          <span class="font-mono text-xs">
            {{ detail.row.requestMethod }} {{ detail.row.requestUri }}
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="来源">
          {{ detail.row.clientIp }}
        </el-descriptions-item>
        <el-descriptions-item label="UA">
          <span class="text-xs">{{ detail.row.userAgent || "-" }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="耗时">
          {{ detail.row.durationMs }} ms
        </el-descriptions-item>
        <el-descriptions-item label="错误">
          <span v-if="detail.row.errorMsg" class="text-red-600">
            {{ detail.row.errorMsg }}
          </span>
          <span v-else class="text-gray-400">无</span>
        </el-descriptions-item>
      </el-descriptions>
      <div class="mt-3">
        <div class="mb-1 font-medium">请求参数</div>
        <pre
          class="max-h-80 overflow-auto rounded bg-gray-50 p-3 text-xs dark:bg-gray-800"
          >{{ pretty(detail.row?.params) || "（无）" }}</pre
        >
      </div>
    </el-drawer>
  </div>
</template>
