const Layout = () => import("@/layout/index.vue");

export default {
  path: "/data",
  name: "DataAccess",
  component: Layout,
  meta: {
    icon: "ep/connection",
    title: "数据接入",
    rank: 1
  },
  children: [
    {
      path: "/connectors",
      name: "ConnectorList",
      component: () => import("@/views/connector/index.vue"),
      meta: {
        title: "连接器"
      }
    },
    {
      path: "/services",
      name: "ServiceList",
      component: () => import("@/views/services/index.vue"),
      meta: {
        title: "数据源服务接口"
      }
    },
    {
      path: "/services/:connectorId/:operationId",
      name: "ServiceDetail",
      component: () => import("@/views/services/detail.vue"),
      meta: {
        title: "接口详情",
        // 从列表点进的工作页，不占菜单位（否则菜单会出现一条永远选不中的空项）
        showLink: false
      }
    },
    {
      path: "/etl-handlers",
      name: "EtlHandlerList",
      component: () => import("@/views/etl/index.vue"),
      meta: {
        title: "ETL 处理器"
      }
    },
    {
      path: "/envs",
      name: "EnvList",
      component: () => import("@/views/env/index.vue"),
      meta: {
        title: "环境与密钥"
      }
    },
    {
      path: "/apps",
      name: "AppList",
      component: () => import("@/views/app/index.vue"),
      meta: {
        title: "调用方应用"
      }
    }
  ]
} satisfies RouteConfigsTable;
