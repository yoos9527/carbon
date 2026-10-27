/**
 * Carbon 平台不做登录（见设计文档 §7.4），本文件只保留 storage key 常量与
 * 给基座布局组件用的空实现，避免大范围改动 pure-admin 的 layout。
 *
 * 一旦将来接入 SSO，把 getToken/setToken/formatToken 换回真实实现即可，
 * 其余代码无需改动（http 层只依赖 formatToken 的存在）。
 */
import { storageLocal } from "@pureadmin/utils";

export interface DataInfo<T> {
  /** token */
  accessToken?: string;
  /** `accessToken`的过期时间（时间戳） */
  expires?: T;
  /** 用于刷新 accessToken 的 token */
  refreshToken?: string;
  /** 头像 */
  avatar?: string;
  /** 用户名 */
  username?: string;
  /** 昵称 */
  nickname?: string;
  /** 角色 */
  roles?: Array<string>;
  /** 按钮级别权限 */
  permissions?: Array<string>;
}

/** 当前操作人（平台无登录，固定值；可在 settings 页做成可配置） */
export const OPERATOR_NAME = "molei.you";

export const userKey = "user-info";
export const TokenKey = "authorized-token";
export const multipleTabsKey = "multiple-tabs";

/** 读取本地保存的用户信息（登录后才有，当前恒为空） */
export function getToken(): DataInfo<number> | null {
  return storageLocal().getItem<DataInfo<number>>(userKey);
}

/** 预留：接入 SSO 时写入 token */
export function setToken(_data?: DataInfo<Date>): void {
  /* no-op：平台不做登录 */
}

/** 预留：接入 SSO 时清除 token */
export function removeToken(): void {
  /* no-op：平台不做登录 */
}

/** 预留：拼装 `Authorization` 头 */
export function formatToken(_token?: string): string {
  return "";
}
