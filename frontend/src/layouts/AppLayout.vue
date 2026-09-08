<script setup lang="ts">
import { onMounted } from "vue";
import { message } from "ant-design-vue";
import { useRoute, useRouter } from "vue-router";

import BrandMark from "../components/BrandMark.vue";
import { clearAuth, displayName, isAdmin, syncRole } from "../stores/session";
import { clearAll } from "../stores/trip";

const route = useRoute();
const router = useRouter();

/**
 * 主导航 5 项（产品化：首页/规划/发现/社区/我的，对齐 PRODUCT_EVOLUTION_PLAN §3.2）。
 * 文案保留原语义（不强行改名词，避免用户习惯与路由耦合风险）；
 * “结果”不作为固定主导航（依赖内存中的最新行程）；历史/我的帖子从「我的」进入。
 */
const navItems: Array<{ name: string; label: string }> = [
  { name: "dashboard", label: "首页" },
  { name: "plan", label: "规划" },
  { name: "recommendations", label: "发现" },
  { name: "community", label: "社区" },
  { name: "profile", label: "我的" },
];

function go(name: string) {
  void router.push({ name });
}

const isActive = (name: string) => route.name === name;

function handleLogout() {
  clearAuth();
  clearAll();
  void router.replace({ name: "login" });
  message.success("已退出登录");
}

// 启动即同步最新用户态（补齐 role，供「管理后台」入口显隐）
onMounted(() => {
  void syncRole();
});
</script>

<template>
  <div class="app-shell">
    <header class="nav-bar">
      <div class="nav-bar__inner">
        <button type="button" class="nav-bar__brand" @click="go('dashboard')">
          <BrandMark :size="30" />
        </button>

        <nav class="nav-bar__tabs" aria-label="主导航">
          <button
            v-for="item in navItems"
            :key="item.name"
            type="button"
            :class="['nav-tab', { 'nav-tab--active': isActive(item.name) }]"
            @click="go(item.name)"
          >
            {{ item.label }}
          </button>
        </nav>

        <div class="nav-bar__user">
          <button
            v-if="isAdmin"
            type="button"
            :class="['nav-chip', { 'nav-chip--active': route.name === 'moderation' }]"
            @click="go('moderation')"
          >
            管理后台
          </button>
          <button type="button" class="nav-bar__profile" @click="go('profile')">
            <span class="nav-bar__avatar" aria-hidden="true">{{ (displayName || "U").slice(0, 1) }}</span>
            <span class="nav-bar__username">{{ displayName }}</span>
          </button>
          <button type="button" class="nav-bar__logout" @click="handleLogout">退出</button>
        </div>
      </div>
    </header>

    <main class="page-content">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  padding-top: var(--nav-height);
}

.nav-bar {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 100;
  background: rgba(247, 245, 239, 0.82);
  backdrop-filter: saturate(180%) blur(16px);
  -webkit-backdrop-filter: saturate(180%) blur(16px);
  border-bottom: 1px solid var(--border-soft);
}

.nav-bar__inner {
  max-width: var(--content-max);
  margin: 0 auto;
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 0 20px;
  height: var(--nav-height);
}

/* 品牌区（去掉原生按钮样式） */
.nav-bar__brand {
  border: none;
  background: none;
  padding: 0;
  cursor: pointer;
  flex: none;
  border-radius: 8px;
}

.nav-bar__tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1;
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: none;
}
.nav-bar__tabs::-webkit-scrollbar {
  display: none;
}

.nav-tab {
  position: relative;
  border: none;
  background: none;
  padding: 8px 14px;
  color: var(--text-secondary);
  font-size: 14px;
  font-weight: 550;
  white-space: nowrap;
  cursor: pointer;
  transition: color 0.2s var(--ease);
  border-radius: 8px;
}
.nav-tab:hover {
  color: var(--brand-ink);
}
.nav-tab--active {
  color: var(--brand-ink);
  font-weight: 700;
}
/* 杂志式下划线 active，替代 iOS 分段控件 */
.nav-tab--active::after {
  content: "";
  position: absolute;
  left: 14px;
  right: 14px;
  bottom: 2px;
  height: 2.5px;
  border-radius: 2px;
  background: var(--brand-coral);
}

.nav-bar__user {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}

.nav-chip {
  border: 1px solid var(--border-soft);
  background: var(--surface-white);
  border-radius: 999px;
  padding: 5px 12px;
  font-size: 12px;
  font-weight: 600;
  color: var(--brand-deep);
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.2s var(--ease);
}
.nav-chip:hover {
  border-color: var(--brand-teal);
  color: var(--brand-teal);
}
.nav-chip--active {
  background: var(--brand-deep);
  border-color: var(--brand-deep);
  color: #f7f5ef;
}

.nav-bar__profile {
  display: flex;
  align-items: center;
  gap: 8px;
  border: none;
  background: none;
  padding: 2px 4px;
  cursor: pointer;
  border-radius: 999px;
  transition: background 0.2s var(--ease);
}
.nav-bar__profile:hover {
  background: rgba(23, 33, 31, 0.05);
}

.nav-bar__avatar {
  display: inline-grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--brand-deep);
  color: #f7f5ef;
  font-size: 13px;
  font-weight: 600;
}

.nav-bar__username {
  font-size: 13px;
  font-weight: 550;
  color: var(--text-primary);
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-bar__logout {
  border: none;
  border-radius: 8px;
  padding: 5px 10px;
  font-size: 13px;
  font-weight: 500;
  color: var(--danger);
  background: rgba(198, 93, 81, 0.08);
  cursor: pointer;
  transition: all 0.2s ease;
}
.nav-bar__logout:hover {
  background: rgba(198, 93, 81, 0.15);
}

.page-content {
  max-width: var(--content-max);
  margin: 0 auto;
  padding: 20px 20px 48px;
}

/* 窄屏：压紧品牌副标题与用户名，主 tab 区允许横向滚动不换行 */
@media (max-width: 900px) {
  .nav-bar__inner {
    gap: 12px;
    padding: 0 14px;
  }
  .nav-bar__username {
    display: none;
  }
}

@media (max-width: 640px) {
  .nav-bar__brand :deep(.brand__subtitle) {
    display: none;
  }
  .nav-tab {
    padding: 8px 10px;
    font-size: 13.5px;
  }
  .nav-bar__logout {
    font-size: 12px;
    padding: 4px 8px;
  }
  .nav-chip {
    padding: 4px 9px;
    font-size: 11px;
  }
  .page-content {
    padding: 16px 14px 40px;
  }
}
</style>
