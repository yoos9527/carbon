import { defineStore } from "pinia";
import { type userType, store } from "../utils";
import { OPERATOR_NAME } from "@/utils/auth";

/**
 * 平台不做登录（设计文档 §7.4），当前操作人是一个固定值。
 * 保留这个 store 是因为 pure-admin 的 layout（头像、搜索、标签页）依赖它；
 * 接入 SSO 时只改这里与 utils/auth.ts，layout 不用动。
 */
export const useUserStore = defineStore("pure-user", {
  state: (): userType => ({
    avatar: "",
    username: OPERATOR_NAME,
    nickname: "平台运维",
    roles: ["admin"],
    permissions: ["*:*:*"],
    isRemembered: false,
    loginDay: 7
  }),
  actions: {
    SET_AVATAR(avatar: string) {
      this.avatar = avatar;
    },
    SET_USERNAME(username: string) {
      this.username = username;
    },
    SET_NICKNAME(nickname: string) {
      this.nickname = nickname;
    },
    SET_ROLES(roles: Array<string>) {
      this.roles = roles;
    },
    SET_PERMS(permissions: Array<string>) {
      this.permissions = permissions;
    },
    /** 无登录，登出只清空当前页面的本地状态 */
    logOut() {
      this.username = OPERATOR_NAME;
      this.roles = ["admin"];
      this.permissions = ["*:*:*"];
    }
  }
});

export function useUserStoreHook() {
  return useUserStore(store);
}
