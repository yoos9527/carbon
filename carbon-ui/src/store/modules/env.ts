import { defineStore } from "pinia";
import { store } from "../utils";

/**
 * 全局环境选择器。后端的试调/网关/日志都支持带环境上下文
 * （`X-Carbon-Env` 头，对应 `env.code`），前端只需要一个共享状态。
 * 持久化到 localStorage，刷新页面不丢。
 */
const STORAGE_KEY = "carbon-current-env";

export const useEnvStore = defineStore("carbon-env", {
  state: () => ({
    /** 当前环境编码，空串表示用连接器上配置的默认环境 */
    current: (localStorage.getItem(STORAGE_KEY) ?? "") as string
  }),
  actions: {
    set(code: string) {
      this.current = code ?? "";
      if (this.current) localStorage.setItem(STORAGE_KEY, this.current);
      else localStorage.removeItem(STORAGE_KEY);
    }
  }
});

export function useEnvStoreHook() {
  return useEnvStore(store);
}
