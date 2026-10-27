const Layout = () => import("@/layout/index.vue");

export default {
  path: "/log",
  name: "Log",
  component: Layout,
  meta: {
    icon: "ep/document",
    title: "调用日志",
    rank: 3
  },
  children: [
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
