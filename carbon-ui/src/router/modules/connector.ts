const Layout = () => import("@/layout/index.vue");

export default {
  path: "/connector",
  name: "Connector",
  component: Layout,
  meta: {
    icon: "ep/connection",
    title: "连接器",
    rank: 1
  },
  children: [
    {
      path: "/connectors",
      name: "ConnectorList",
      component: () => import("@/views/connector/index.vue"),
      meta: {
        title: "连接器列表"
      }
    },
    {
      path: "/envs",
      name: "EnvList",
      component: () => import("@/views/env/index.vue"),
      meta: {
        title: "环境变量"
      }
    },
    {
      path: "/connectors/:id/operations",
      name: "ConnectorOperations",
      component: () => import("@/views/connector/operations.vue"),
      meta: {
        title: "接口与试调",
        showLink: false
      }
    }
  ]
} satisfies RouteConfigsTable;
