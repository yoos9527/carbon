const Layout = () => import("@/layout/index.vue");

export default {
  path: "/job",
  name: "Job",
  component: Layout,
  meta: {
    icon: "ep/alarm-clock",
    title: "调度",
    rank: 4
  },
  children: [
    {
      path: "/jobs",
      name: "JobList",
      component: () => import("@/views/job/index.vue"),
      meta: {
        title: "定时任务"
      }
    }
  ]
} satisfies RouteConfigsTable;
