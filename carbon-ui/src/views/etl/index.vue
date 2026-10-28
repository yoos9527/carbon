<script setup lang="ts">
import { ElMessage } from "element-plus";

defineOptions({ name: "EtlHandlerList" });

/**
 * ETL 处理器：参考系统把「前置/后置脚本」从接口里抽出来当资产复用（带版本、上下线、引用检查）。
 * carbon 目前脚本还内联在 connector_operation.pre_script / post_script 上，没有独立表，
 * 所以这一页先老老实实说明现状，不假装有数据。
 */
const planned = [
  {
    type: "前置 ETL",
    desc: "调三方之前加工入参：字段映射、单位换算、脱敏、拼签名",
    anchor: "connector_operation.pre_script"
  },
  {
    type: "后置 ETL",
    desc: "拿响应之后清洗：拍平嵌套、补默认值、算派生字段",
    anchor: "connector_operation.post_script"
  },
  {
    type: "内置处理器",
    desc: "平台自带、只填参数不写代码的处理器（如 XML→JSON、分页翻页）",
    anchor: "—— 未实现"
  },
  {
    type: "公共代码块",
    desc: "被多个 ETL 引用的 Groovy 函数库",
    anchor: "—— 未实现"
  }
];
</script>

<template>
  <div class="p-4">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="这一页还没有后端表"
      description="参考系统把 ETL 处理器做成独立资产（有版本、能上下线、能查谁在引用）。carbon 目前只有接口级的 preScript / postScript 两段内联脚本，所以这里先做说明页，等 P0 之后补 etl_handler 表再换成真列表。"
    />

    <el-card class="mt-3" shadow="never">
      <template #header>
        <div class="flex items-center gap-2">
          <span class="font-medium">要做的四类处理器</span>
          <el-tag size="small" type="warning">未落地</el-tag>
        </div>
      </template>
      <el-table :data="planned" border>
        <el-table-column prop="type" label="类型" width="140" />
        <el-table-column prop="desc" label="作用" min-width="320" />
        <el-table-column label="当前对应位置" min-width="280">
          <template #default="{ row }">
            <span class="font-mono text-xs">{{ row.anchor }}</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="mt-3 text-sm leading-6 text-gray-500">
        <div>为什么现在不做：脚本一独立成资产，就要有版本、上下线、引用计数、试跑沙箱。</div>
        <div>
          没有这些，"抽出来"只是把内联脚本换个地方放，反而更难排查——线上报错时不知道跑的是哪个版本。
        </div>
      </div>
    </el-card>

    <el-card class="mt-3" shadow="never">
      <template #header>现在能用什么</template>
      <div class="flex flex-wrap gap-2">
        <el-button
          type="primary"
          plain
          @click="$router.push('/services')"
        >
          去接口详情里的「脚本」页签写前置/后置脚本
        </el-button>
        <el-button plain @click="ElMessage.info('脚本语法见 docs/09 §4 执行语义')">
          查看脚本执行说明
        </el-button>
      </div>
      <div class="mt-3 text-sm text-gray-500">
        脚本走 Groovy 沙箱，在参数渲染**之前**执行——先改脚本再渲染模板，避免脚本能篡改 URL。
        单次执行超时由接口的 scriptTimeoutMs 控制（默认 1000ms）。
      </div>
    </el-card>
  </div>
</template>
