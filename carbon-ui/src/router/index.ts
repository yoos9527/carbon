import { getConfig } from "@/config";
import NProgress from "@/utils/progress";
import { buildHierarchyTree } from "@/utils/tree";
import remainingRouter from "./modules/remaining";
import { usePermissionStoreHook } from "@/store/modules/permission";
import {
  openLink,
  cloneDeep,
  isAllEmpty,
  storageLocal
} from "@pureadmin/utils";
import {
  ascending,
  getHistoryMode,
  isOneOfArray,
  handleAliveRoute,
  formatTwoStageRoutes,
  formatFlatteningRoutes
} from "./utils";
import {
  type Router,
  type RouteRecordRaw,
  type RouteComponent,
  createRouter
} from "vue-router";
import { type DataInfo, userKey } from "@/utils/auth";

/**
 * 静态路由：`src/router/modules/**\/*.ts` 里除 `remaining.ts` 之外全部自动收集。
 *
 * 与基座（pure-admin）的差别：平台不做登录（设计文档 §7.4），因此**没有**「登录后
 * 拉取动态路由 / 无 token 跳登录页」这一段，路由表在构建期就是全量的，
 * `beforeEach` 只负责进度条、标题、keepAlive 与隐藏首页。
 */
const modules: Record<string, any> = import.meta.glob(
  ["./modules/**/*.ts", "!./modules/**/remaining.ts"],
  { eager: true }
);

const routes = [];
Object.keys(modules).forEach(key => {
  routes.push(modules[key].default);
});

/** 处理后的静态路由（三级及以上拍平成二级） */
export const constantRoutes: Array<RouteRecordRaw> = formatTwoStageRoutes(
  formatFlatteningRoutes(buildHierarchyTree(ascending(routes.flat(Infinity))))
);

/** 用于重置路由 */
const initConstantRoutes: Array<RouteRecordRaw> = cloneDeep(constantRoutes);

/** 用于渲染菜单，保持原始层级 */
export const constantMenus: Array<RouteComponent> = ascending(
  routes.flat(Infinity)
).concat(...remainingRouter);

/** 不参与菜单的路由 */
export const remainingPaths = Object.keys(remainingRouter).map(v => {
  return remainingRouter[v].path;
});

export const router: Router = createRouter({
  history: getHistoryMode(import.meta.env.VITE_ROUTER_HISTORY),
  routes: constantRoutes.concat(...(remainingRouter as any)),
  strict: true,
  scrollBehavior(to, from, savedPosition) {
    return new Promise(resolve => {
      if (savedPosition) {
        return savedPosition;
      } else {
        if (from.meta.saveSrollTop) {
          const top: number =
            document.documentElement.scrollTop || document.body.scrollTop;
          resolve({ left: 0, top });
        }
      }
    });
  }
});

/** 记录已经加载的页面路径 */
const loadedPaths = new Set<string>();

export function resetLoadedPaths() {
  loadedPaths.clear();
}

export function resetRouter() {
  router.clearRoutes();
  for (const route of initConstantRoutes.concat(...(remainingRouter as any))) {
    router.addRoute(route);
  }
  router.options.routes = formatTwoStageRoutes(
    formatFlatteningRoutes(buildHierarchyTree(ascending(routes.flat(Infinity))))
  );
  usePermissionStoreHook().clearAllCachePage();
  resetLoadedPaths();
}

const { VITE_HIDE_HOME } = import.meta.env;

router.beforeEach((to, _from, next) => {
  to.meta.loaded = loadedPaths.has(to.path);

  if (!to.meta.loaded) {
    NProgress.start();
  }

  if (to.meta?.keepAlive) {
    handleAliveRoute(to, "add");
    // 页面整体刷新和点击标签页刷新
    if (_from.name === undefined || _from.name === "Redirect") {
      handleAliveRoute(to);
    }
  }

  // 外链路由的 name 就是 URL（http/https 开头），这类不覆盖 document.title
  const externalLink = ["http", "https"].some(p =>
    String(to?.name ?? "").startsWith(p)
  );
  if (!externalLink) {
    to.matched.some(item => {
      if (!item.meta.title) return "";
      const Title = getConfig().Title;
      if (Title) document.title = `${item.meta.title} | ${Title}`;
      else document.title = item.meta.title as string;
    });
  }

  // 开启隐藏首页后，地址栏手动输入 /welcome 走 404
  if (VITE_HIDE_HOME === "true" && to.fullPath === "/welcome") {
    next({ path: "/error/404" });
    return;
  }

  // 平台无登录，但保留角色判断：将来接入 SSO 时只需让 user 表带 roles
  const userInfo = storageLocal().getItem<DataInfo<number>>(userKey);
  if (userInfo?.roles?.length && to.meta?.roles) {
    if (!isOneOfArray(to.meta.roles, userInfo.roles)) {
      next({ path: "/error/403" });
      return;
    }
  }

  if (externalLink) {
    openLink(to?.name as string);
    NProgress.done();
    next(false);
    return;
  }

  if (isAllEmpty(to.name)) {
    // 匹配不到的路由由 remaining.ts 的通配兜底
    next({ path: "/error/404" });
    return;
  }

  next();
});

router.afterEach(to => {
  loadedPaths.add(to.path);
  NProgress.done();
});

export default router;
