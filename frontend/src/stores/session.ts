import { computed, ref } from "vue";

import { clearToken, fetchMe, getToken, getUser, setToken, setUser } from "../services/api";
import type { User } from "../types";

/**
 * 会话状态单例（无 Pinia，Vue reactive 即可跨页面共享）。
 * 登录态来源为 localStorage（token + user），模块加载即恢复，
 * 保证路由守卫在任何组件挂载前就能拿到真实登录态。
 */
const token = ref<string | null>(getToken());
const user = ref<User | null>(getUser());

export const isLoggedIn = computed(() => token.value !== null);

/** 是否管理员（阶段二社区：服务端仍会二次校验，这里只控制前端入口显隐） */
export const isAdmin = computed(() => user.value?.role === "ADMIN");

export const displayName = computed(
  () => user.value?.nickname || user.value?.username || ""
);

/** 当前登录用户 id（用户主页/关注等需要目标 id 的场景） */
export const currentUserId = computed(() => user.value?.id ?? "");

/** 登录 / 注册成功后写入（持久化到 localStorage + 更新内存态） */
export function setAuthed(payload: { token: string; user: User }) {
  token.value = payload.token;
  user.value = payload.user;
  setToken(payload.token);
  setUser(payload.user);
}

/**
 * 启动/登录后同步最新用户态（阶段二：补齐 role）。
 * 老会话的 localStorage 用户对象可能缺 role，调 /auth/me 一次性对齐。
 */
export async function syncRole(): Promise<void> {
  if (!token.value) return;
  try {
    const resp = await fetchMe();
    const role = resp.user?.role;
    if (role && user.value) {
      if (user.value.role !== role) {
        user.value = { ...user.value, role };
        setUser(user.value);
      }
    }
  } catch {
    /* 网络异常静默：role 缺失时仅影响前端入口显隐，服务端鉴权兜底 */
  }
}

/** 退出 / 登录失效时清空 */
export function clearAuth() {
  token.value = null;
  user.value = null;
  clearToken();
}
