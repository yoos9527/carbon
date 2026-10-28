const Layout = () => import("@/layout/index.vue");

export default {
  path: "/system",
  name: "System",
  component: Layout,
  meta: {
    icon: "ep/setting",
    title: "系统管理",
    rank: 9
  },
  children: [
    {
      path: "/oper-logs",
      name: "OperLogList",
      component: () => import("@/views/system/oper-log.vue"),
      meta: {
        title: "操作日志"
      }
    }
  ]
} satisfies RouteConfigsTable;
