const Layout = () => import("@/layout/index.vue");

export default {
  path: "/monitor",
  name: "Monitor",
  component: Layout,
  meta: {
    icon: "ep/monitor",
    title: "运行监控",
    rank: 3
  },
  children: [
    {
      path: "/flow-runs",
      name: "FlowRunList",
      component: () => import("@/views/flow/runs.vue"),
      meta: {
        title: "运行记录"
      }
    },
    {
      // 面向运营对账，与「调用日志」分开：前者看业务结果与成功率，后者看报文排障
      path: "/call-details",
      name: "CallDetailList",
      component: () => import("@/views/log/call-details.vue"),
      meta: {
        title: "数据源调用明细"
      }
    },
    {
      path: "/logs",
      name: "InvokeLogList",
      component: () => import("@/views/log/index.vue"),
      meta: {
        title: "调用日志"
      }
    }
  ]
} satisfies RouteConfigsTable;
