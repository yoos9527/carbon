const Layout = () => import("@/layout/index.vue");

export default {
  path: "/flow",
  name: "Flow",
  component: Layout,
  meta: {
    icon: "ep/share",
    title: "流程编排",
    rank: 2
  },
  children: [
    {
      path: "/flows",
      name: "FlowList",
      component: () => import("@/views/flow/index.vue"),
      meta: {
        title: "流程列表"
      }
    },
    {
      path: "/flows/design/:id",
      name: "FlowDesign",
      component: () => import("@/views/flow/design.vue"),
      meta: {
        title: "编排设计器",
        // 画布是全屏工作台，必须独占视口，所以不进菜单
        showLink: false
      }
    },
    {
      path: "/flows/:id/versions",
      name: "FlowVersions",
      component: () => import("@/views/flow/versions.vue"),
      meta: {
        title: "版本历史",
        showLink: false
      }
    }
  ]
} satisfies RouteConfigsTable;
