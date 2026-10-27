/**
 * Carbon 前端 HTTP 客户端
 *
 * 替代 pure-admin 基座的 `PureHttp`（那套是给「有登录 + 双 token 刷新」的模板用的，
 * 平台不做登录，见设计文档 §7.4）。这里只做四件事：
 *  1. 统一解开后端响应包装 `R{code,msg,data}`，业务层直接拿 `data`
 *  2. 非 200 抛 `ApiError`（带 traceId），并弹一次错误提示
 *  3. 注入 `X-Carbon-Env`（试调/日志用的环境选择）
 *  4. 雪花 id 是 string，不做任何数值转换
 */
import Axios, {
  type AxiosInstance,
  type AxiosRequestConfig,
  type CustomParamsSerializer
} from "axios";
import { stringify } from "qs";
import { ElMessage } from "element-plus";
import { useEnvStoreHook } from "@/store/modules/env";

/** 后端统一响应包装 */
export interface R<T = unknown> {
  code: number;
  msg: string;
  data: T;
}

/** 分页响应（对应后端 TableDataInfo） */
export interface TableDataInfo<T> {
  total: number;
  rows: Array<T>;
}

export class ApiError extends Error {
  /** 后端业务码，如 400 / 502 */
  code: number;
  /** 链路 id，排查用 */
  traceId?: string;
  /** 三方返回的 HTTP 状态（PROVIDER_HTTP_4XX 等场景有用） */
  providerStatus?: number;

  constructor(
    message: string,
    code: number,
    traceId?: string,
    providerStatus?: number
  ) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.traceId = traceId;
    this.providerStatus = providerStatus;
  }
}

const defaultConfig: AxiosRequestConfig = {
  // 网关与编排可能较慢，比基座默认的 10s 放宽
  timeout: 60000,
  headers: {
    Accept: "application/json, text/plain, */*",
    "Content-Type": "application/json",
    "X-Requested-With": "XMLHttpRequest"
  },
  paramsSerializer: {
    // 数组参数序列化为 a=1&a=2
    serialize: stringify as unknown as CustomParamsSerializer
  }
};

class HttpClient {
  private readonly instance: AxiosInstance = Axios.create(defaultConfig);

  constructor() {
    this.instance.interceptors.request.use(config => {
      // 环境选择器：只影响需要环境上下文的接口（试调、渲染预览）
      const env = useEnvStoreHook().current;
      if (env) config.headers["X-Carbon-Env"] = env;
      return config;
    });

    this.instance.interceptors.response.use(
      // 这里故意返回**已解开包装**的 data，类型上就是 AxiosResponse，只能放宽成 any：
      // 拦截器把响应体换成了业务数据，`http.get<T>` 的 T 才是真正的返回类型。
      (response): any => {
        const body = response.data as R;
        // 非包装响应（比如 actuator/health）原样返回
        if (body == null || typeof body !== "object" || !("code" in body)) {
          return body;
        }
        if (body.code === 200) return body.data;
        const traceId =
          response.headers["x-trace-id"] ??
          (body.data as any)?.traceId ??
          undefined;
        throw new ApiError(body.msg || "请求失败", body.code, traceId);
      },
      error => {
        // 后端异常也走 R 包装（GlobalExceptionHandler），优先取它
        const body = error?.response?.data as R | undefined;
        const traceId = error?.response?.headers?.["x-trace-id"];
        const providerStatus = (body?.data as any)?.providerStatus;
        const code = body?.code ?? error?.response?.status ?? -1;
        let message = body?.msg || error?.message || "网络异常";
        if (!body && error?.code === "ECONNABORTED") {
          message = "请求超时：后端未在 60 秒内响应";
        }
        if (code === 429) message = `触发限流：${message}`;
        return Promise.reject(
          new ApiError(message, code, traceId, providerStatus)
        );
      }
    );
  }

  request<T = unknown>(config: AxiosRequestConfig): Promise<T> {
    return this.instance.request<unknown, T>(config);
  }

  get<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return this.request<T>({ ...config, url, method: "get" });
  }

  post<T = unknown>(
    url: string,
    data?: unknown,
    config?: AxiosRequestConfig
  ): Promise<T> {
    return this.request<T>({ ...config, url, method: "post", data });
  }

  put<T = unknown>(
    url: string,
    data?: unknown,
    config?: AxiosRequestConfig
  ): Promise<T> {
    return this.request<T>({ ...config, url, method: "put", data });
  }

  delete<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return this.request<T>({ ...config, url, method: "delete" });
  }
}

export const http = new HttpClient();

/**
 * 统一的失败提示。页面里 `catch (e) { showError(e) }` 即可，
 * 不重复弹（提示去重由元素尺寸/关键字判断，避免表格里 N 行各弹一次）。
 */
let lastMessage = "";
let lastAt = 0;
export function showError(e: unknown): ApiError {
  const err =
    e instanceof ApiError
      ? e
      : new ApiError((e as Error)?.message ?? "未知错误", -1);
  const text = err.traceId
    ? `${err.message}（traceId: ${err.traceId}）`
    : err.message;
  const now = Date.now();
  if (text !== lastMessage || now - lastAt > 2000) {
    ElMessage.error({ message: text, grouping: true });
    lastMessage = text;
    lastAt = now;
  }
  return err;
}
