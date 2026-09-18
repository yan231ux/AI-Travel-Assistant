<script setup lang="ts">
import { computed, onMounted } from "vue";
import { message } from "ant-design-vue";
import { useRoute, useRouter } from "vue-router";

import BrandMark from "../components/BrandMark.vue";
import { clearAuth, displayName, hasPermission, permissions, roleLabel, syncRole } from "../stores/session";
import { clearAll } from "../stores/trip";

/**
 * 独立管理后台布局（管理员后台与内容运营中心设计方案 P0-1/§8：与普通用户端彻底区分）。
 * - 左侧固定运营导航，顶部当前管理员 + 返回用户端；
 * - 不承载普通用户的收藏/点赞/加入行程等操作语义；
 * - 所有子页（/admin/**）共享本布局，路由守卫 ensuresAdmin + 后端按权限点校验双保险。
 *
 * <p>角色细化（§11 第五阶段）：导航按服务端下发的权限点过滤 —— 内容审核员看不到攻略运营、
 * 城市内容编辑看不到用户治理。前端只做入口显隐，真正拦截在服务端 requirePermission。
 * 权限点为空（旧会话 / /auth/me 未返回）时不做过滤：宁可多显示，也不能把有权限的人挡在门外。
 */
const route = useRoute();
const router = useRouter();

interface AdminMenu {
  name: string;
  label: string;
  icon: string;
  /** 需要的权限点；缺省表示"任何管理端角色都可见"（如运营总览） */
  permission?: string;
}

const menus: AdminMenu[] = [
  { name: "admin-dashboard", label: "运营总览", icon: "📊" },
  { name: "admin-charts", label: "数据看板", icon: "📈", permission: "ANALYTICS_VIEW" },
  { name: "admin-review", label: "内容审核", icon: "🛡️", permission: "CONTENT_REVIEW" },
  { name: "admin-ai-review", label: "AI 初筛", icon: "🤖", permission: "CONTENT_REVIEW" },
  { name: "admin-reports", label: "举报中心", icon: "🚩", permission: "CONTENT_REVIEW" },
  { name: "admin-posts", label: "帖子治理", icon: "🗂️", permission: "CONTENT_REVIEW" },
  { name: "admin-guides", label: "攻略内容", icon: "📖", permission: "GUIDE_MANAGE" },
  { name: "admin-spots", label: "景点数据", icon: "📍", permission: "SPOT_GOVERN" },
  { name: "admin-users", label: "用户治理", icon: "👥", permission: "USER_GOVERN" },
  { name: "admin-recommendations", label: "推荐运营", icon: "🧪", permission: "RECOMMEND_OPS" },
  { name: "admin-audit", label: "审计日志", icon: "🧾", permission: "AUDIT_VIEW" },
];

/** 权限点已知时严格过滤；未知（空数组）时全部显示，交给服务端兜底拦截 */
const visibleMenus = computed<AdminMenu[]>(() => {
  if (permissions.value.length === 0) return menus;
  return menus.filter((m) => !m.permission || hasPermission(m.permission));
});

/** 顶栏角色显示：优先服务端角色中文名（如"内容审核员"） */
const currentRoleLabel = computed(() => roleLabel.value || "管理端");

function go(name: string) {
  void router.push({ name });
}

function handleLogout() {
  clearAuth();
  clearAll();
  void router.replace({ name: "login" });
  message.success("已退出登录");
}

onMounted(() => {
  void syncRole();
});
</script>

<template>
  <div class="ad-shell">
    <aside class="ad-sider">
      <div class="ad-sider__brand" @click="go('admin-dashboard')">
        <BrandMark :size="28" :show-text="false" />
        <div class="ad-sider__brand-text">
          <b>内容运营后台</b>
          <span>智旅助手 · Admin</span>
        </div>
      </div>
      <nav class="ad-sider__menu" aria-label="运营导航">
        <button
          v-for="m in visibleMenus"
          :key="m.name"
          type="button"
          :class="['ad-sider__item', { 'ad-sider__item--on': route.name === m.name }]"
          @click="go(m.name)"
        >
          <span class="ad-sider__icon" aria-hidden="true">{{ m.icon }}</span>
          {{ m.label }}
        </button>
        <p v-if="visibleMenus.length === 0" class="ad-sider__empty">
          当前角色暂无可用的运营模块
        </p>
      </nav>
      <div class="ad-sider__foot">管理操作全部记入审计日志</div>
    </aside>

    <div class="ad-body">
      <header class="ad-top">
        <div class="ad-top__title">
          {{ visibleMenus.find((m) => m.name === route.name)?.label || "运营后台" }}
        </div>
        <div class="ad-top__user">
          <span class="ad-top__role">{{ currentRoleLabel }}</span>
          <span class="ad-top__name">{{ displayName }}</span>
          <button type="button" class="ad-top__logout" @click="handleLogout">退出</button>
        </div>
      </header>
      <main class="ad-main">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style>
/* 管理后台共享样式（非 scoped：所有 /admin 子页复用，避免每页重复一套） */
.ad-shell {
  display: flex;
  min-height: 100vh;
  background: #f5f4f0;
}
.ad-sider {
  width: 216px;
  flex-shrink: 0;
  background: linear-gradient(180deg, #173b38 0%, #1d4a45 100%);
  color: rgba(255, 255, 255, 0.86);
  display: flex;
  flex-direction: column;
  position: sticky;
  top: 0;
  height: 100vh;
}
.ad-sider__brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 16px;
  cursor: pointer;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}
.ad-sider__brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
  min-width: 0;
  overflow: hidden;
}
.ad-sider__brand-text b {
  font-size: 15px;
  color: #fff;
  white-space: nowrap;
}
.ad-sider__brand-text span {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.55);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ad-sider__menu {
  display: flex;
  flex-direction: column;
  padding: 12px 10px;
  gap: 2px;
  flex: 1;
}
.ad-sider__item {
  border: none;
  background: transparent;
  color: rgba(255, 255, 255, 0.72);
  text-align: left;
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 14px;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: inherit;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ad-sider__item:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
}
.ad-sider__item--on {
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  font-weight: 600;
}
.ad-sider__icon {
  font-size: 15px;
}
.ad-sider__empty {
  margin: 10px 12px 0;
  font-size: 12px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.5);
}
.ad-sider__foot {
  padding: 12px 16px;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.4);
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}
.ad-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.ad-top {
  height: 56px;
  background: #fff;
  border-bottom: 1px solid #ece8e0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 22px;
  position: sticky;
  top: 0;
  z-index: 50;
}
.ad-top__title {
  font-size: 16px;
  font-weight: 700;
  color: #1d1d1b;
  display: flex;
  align-items: center;
  gap: 10px;
}
.ad-top__crumb {
  font-size: 12px;
  font-weight: 400;
  color: #9a978f;
}
.ad-top__user {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}
.ad-top__role {
  font-size: 11px;
  color: #2f7770;
  background: rgba(47, 119, 112, 0.1);
  padding: 2px 10px;
  border-radius: 999px;
}
.ad-top__name {
  color: #1d1d1b;
  font-weight: 600;
}
.ad-chip {
  border: 1px solid #d8d4cb;
  background: #fff;
  color: #4a4a46;
  border-radius: 999px;
  padding: 4px 14px;
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
}
.ad-chip:hover {
  border-color: #2f7770;
  color: #2f7770;
}
.ad-top__logout {
  border: none;
  background: rgba(0, 0, 0, 0.04);
  border-radius: 10px;
  padding: 5px 12px;
  color: #6b6861;
  cursor: pointer;
  font-size: 12px;
  font-family: inherit;
}
.ad-top__logout:hover {
  background: rgba(198, 93, 81, 0.08);
  color: #c65d51;
}
.ad-main {
  padding: 20px 22px;
  max-width: 1240px;
  width: 100%;
}

/* ---------- 共享原子类 ---------- */
.ad-card {
  background: #fff;
  border-radius: 14px;
  padding: 16px 18px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}
.ad-title {
  font-size: 17px;
  font-weight: 700;
  color: #1d1d1b;
  margin: 0 0 4px;
}
.ad-sub {
  font-size: 12px;
  color: #8c8a83;
  margin: 0 0 14px;
}
.ad-grid {
  display: grid;
  gap: 14px;
}
.ad-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.ad-item {
  background: #fff;
  border-radius: 14px;
  padding: 13px 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}
.ad-item__main {
  flex: 1;
  min-width: 0;
}
.ad-item__title {
  margin: 0 0 4px;
  font-size: 14px;
  font-weight: 600;
  color: #1d1d1b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ad-item__meta {
  margin: 0;
  font-size: 12px;
  color: #8c8a83;
  line-height: 1.6;
  word-break: break-word;
}
.ad-ops {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  flex-shrink: 0;
  align-items: center;
}
.ad-btn {
  border: none;
  border-radius: 10px;
  padding: 6px 13px;
  font-size: 12px;
  font-weight: 500;
  background: rgba(0, 0, 0, 0.05);
  color: #4a4a46;
  cursor: pointer;
  font-family: inherit;
  white-space: nowrap;
}
.ad-btn:hover {
  opacity: 0.85;
}
.ad-btn--ok {
  background: rgba(60, 140, 112, 0.12);
  color: #2f7770;
}
.ad-btn--no {
  background: rgba(198, 93, 81, 0.1);
  color: #c65d51;
}
.ad-btn--link {
  background: transparent;
  color: #2f7770;
  padding: 4px 6px;
}
.ad-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.ad-badge {
  display: inline-block;
  margin: 0 4px 2px 0;
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
  line-height: 1.6;
}
.ad-badge--ok {
  background: rgba(60, 140, 112, 0.12);
  color: #2f7770;
}
.ad-badge--no {
  background: rgba(198, 93, 81, 0.1);
  color: #c65d51;
}
.ad-badge--warn {
  background: rgba(214, 158, 46, 0.14);
  color: #b07f12;
}
.ad-badge--info {
  background: rgba(47, 119, 112, 0.08);
  color: #2f7770;
}
.ad-badge--muted {
  background: rgba(0, 0, 0, 0.05);
  color: #6b6861;
}
.ad-empty {
  text-align: center;
  padding: 34px 0;
  color: #9a978f;
  background: #fff;
  border-radius: 14px;
  font-size: 13px;
}
/* 统计降级提示条：部分读数不可用时必须显式告警，不能伪装成 0（P0-1） */
.ad-degraded {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  border: 1px solid rgba(186, 117, 23, 0.35);
  border-radius: 8px;
  background: #faeeda;
  color: #854f0b;
  font-size: 12px;
}
.ad-retry {
  flex: none;
  border: 1px solid rgba(47, 119, 112, 0.4);
  border-radius: 6px;
  background: transparent;
  color: #2f7770;
  font-family: inherit;
  font-size: 12px;
  padding: 3px 10px;
  cursor: pointer;
}
.ad-retry:hover {
  background: rgba(47, 119, 112, 0.08);
}
.ad-seg {
  display: inline-flex;
  gap: 4px;
  background: rgba(0, 0, 0, 0.05);
  border-radius: 10px;
  padding: 3px;
}
.ad-seg__btn {
  border: none;
  background: transparent;
  border-radius: 8px;
  padding: 5px 14px;
  font-size: 12px;
  color: #6b6861;
  cursor: pointer;
  font-family: inherit;
}
.ad-seg__btn--on {
  background: #fff;
  color: #1d1d1b;
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08);
}
.ad-input,
.ad-textarea,
.ad-select {
  border: 1px solid #dcd8d0;
  border-radius: 10px;
  padding: 6px 12px;
  font-size: 13px;
  font-family: inherit;
  background: #fff;
  color: #1d1d1b;
  outline: none;
  box-sizing: border-box;
}
.ad-input:focus,
.ad-textarea:focus,
.ad-select:focus {
  border-color: #2f7770;
}
.ad-textarea {
  resize: vertical;
  width: 100%;
}
.ad-form-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
}
.ad-modal {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  display: grid;
  place-items: center;
  z-index: 300;
}
.ad-modal__card {
  width: min(480px, 92vw);
  background: #fff;
  border-radius: 16px;
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 86vh;
  overflow: auto;
}
.ad-modal__title {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
}
.ad-kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 10px;
}
.ad-kpi {
  background: #fff;
  border-radius: 12px;
  padding: 12px 10px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
}
.ad-kpi b {
  font-size: 20px;
  color: #1d1d1b;
}
.ad-kpi span {
  font-size: 11px;
  color: #8c8a83;
}
.ad-markdown {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.7;
  color: #3a3a37;
  font-family: "SF Mono", Consolas, Menlo, monospace;
  background: #fbfaf7;
  border: 1px solid #ece8e0;
  border-radius: 10px;
  padding: 12px;
}
</style>
