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

/**
 * 管理端角色码（阶段五角色细化）：除 USER 外都能进 /admin。
 * 历史值 ADMIN 保留兼容（服务端启动期会迁成 SUPER_ADMIN）。
 */
export const ADMIN_SIDE_ROLES = [
  "CONTENT_REVIEWER",
  "CITY_EDITOR",
  "RECOMMENDATION_OPERATOR",
  "SUPER_ADMIN",
  "ADMIN",
];

/** 任意角色码是否属于管理端（列表页判断"该账号是否受治理保护"也用它） */
export function isAdminSideRole(role: string | null | undefined): boolean {
  return ADMIN_SIDE_ROLES.includes((role ?? "").toUpperCase());
}

/** 是否管理端用户（服务端仍会二次校验，这里只控制前端入口显隐） */
export const isAdmin = computed(() => isAdminSideRole(user.value?.role));

/** 角色中文名（如"内容审核员"；旧会话缺省时回退空串） */
export const roleLabel = computed(() => user.value?.role_label ?? "");

/** 当前用户权限点集合（服务端下发；旧会话缺省时为空，菜单按"无权限"收敛） */
export const permissions = computed<string[]>(() => user.value?.permissions ?? []);

/** 是否拥有指定权限点（前端入口显隐用；真正拦截在服务端） */
export function hasPermission(point: string): boolean {
  return permissions.value.includes(point);
}

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
 * 启动/登录后同步最新用户态（阶段二补齐 role；阶段五补齐 role_label / permissions）。
 * 老会话的 localStorage 用户对象可能缺这些字段，调 /auth/me 一次性对齐。
 * 角色或权限变化时整块覆盖并回写 localStorage，保证刷新后菜单权限一致。
 */
export async function syncRole(): Promise<void> {
  if (!token.value) return;
  try {
    const resp = await fetchMe();
    const me = resp.user;
    if (!me || !user.value) return;
    const role = me.role ?? user.value.role ?? null;
    const roleLabelNext = me.role_label ?? null;
    const permissionsNext = me.permissions ?? [];
    const changed =
      user.value.role !== role ||
      user.value.role_label !== roleLabelNext ||
      (user.value.permissions ?? []).join(",") !== permissionsNext.join(",");
    if (changed) {
      user.value = {
        ...user.value,
        role,
        role_label: roleLabelNext,
        permissions: permissionsNext,
      };
      setUser(user.value);
    }
  } catch {
    /* 网络异常静默：角色/权限缺失时仅影响前端入口显隐，服务端鉴权兜底 */
  }
}

/** 退出 / 登录失效时清空 */
export function clearAuth() {
  token.value = null;
  user.value = null;
  clearToken();
}
