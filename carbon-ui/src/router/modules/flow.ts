const Layout = () => import("@/layout/index.vue");

export default {
  path: "/flow",
  name: "Flow",
  component: Layout,
  meta: {
    icon: "ep/share",
    title: "编排",
    rank: 2
  },
  children: [
    {
      // 画布页（P2）挂在 /flow/:id/design，这里先只给运行记录
      path: "/flow/runs",
      name: "FlowRunList",
      component: () => import("@/views/flow/runs.vue"),
      meta: {
        title: "运行记录"
      }
    }
  ]
} satisfies RouteConfigsTable;
