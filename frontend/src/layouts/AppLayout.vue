<script setup lang="ts">
import { onMounted } from "vue";
import { message } from "ant-design-vue";
import { useRoute, useRouter } from "vue-router";

import { clearAuth, displayName, syncRole } from "../stores/session";
import { clearAll } from "../stores/trip";

const route = useRoute();
const router = useRouter();

/**
 * 主导航 5 项（产品化：首页/规划/发现/社区/我的，对齐 PRODUCT_EVOLUTION_PLAN §3.2）。
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

// 启动即同步最新用户态（补齐 role，供「我的/审核」入口显隐）
onMounted(() => {
  void syncRole();
});
</script>

<template>
  <div class="app-shell">
    <header class="nav-bar">
      <div class="nav-bar__inner">
        <span class="nav-bar__title" role="button" tabindex="0" @click="go('dashboard')">智能旅行助手</span>
        <div class="nav-bar__right">
          <div class="nav-bar__tabs">
            <button
              v-for="item in navItems"
              :key="item.name"
              type="button"
              :class="['nav-tab', { 'nav-tab--active': isActive(item.name) }]"
              @click="go(item.name)"
            >
              {{ item.label }}
            </button>
          </div>
          <div class="nav-bar__user">
            <span class="nav-bar__username">{{ displayName }}</span>
            <button type="button" class="nav-bar__logout" @click="handleLogout">退出</button>
          </div>
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
  padding-top: 56px;
}

.nav-bar {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 100;
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: saturate(180%) blur(20px);
  -webkit-backdrop-filter: saturate(180%) blur(20px);
  border-bottom: 0.5px solid rgba(0, 0, 0, 0.1);
}

.nav-bar__inner {
  max-width: 1280px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  height: 56px;
}

.nav-bar__title {
  font-size: 17px;
  font-weight: 600;
  color: #1C1C1E;
  cursor: pointer;
  user-select: none;
}

.nav-bar__right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.nav-bar__tabs {
  display: flex;
  gap: 2px;
  padding: 3px;
  border-radius: 10px;
  background: rgba(0, 0, 0, 0.04);
}

.nav-bar__user {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-left: 12px;
  border-left: 0.5px solid rgba(0, 0, 0, 0.1);
}

.nav-bar__username {
  font-size: 13px;
  font-weight: 500;
  color: #3C3C43;
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-bar__logout {
  border: none;
  border-radius: 8px;
  padding: 5px 12px;
  font-size: 13px;
  font-weight: 500;
  color: #FF3B30;
  background: rgba(255, 59, 48, 0.08);
  cursor: pointer;
  transition: all 0.2s ease;
}

.nav-bar__logout:hover {
  background: rgba(255, 59, 48, 0.14);
}

.nav-bar__logout:active {
  transform: scale(0.97);
}

.nav-tab {
  border: none;
  border-radius: 8px;
  padding: 6px 16px;
  background: transparent;
  color: #8E8E93;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.nav-tab:active {
  transform: scale(0.97);
}

.nav-tab--active {
  background: #FFFFFF;
  color: #1C1C1E;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.nav-tab--disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.page-content {
  max-width: 1280px;
  margin: 0 auto;
  padding: 20px 20px 40px;
}

@media (max-width: 768px) {
  .app-shell {
    padding-top: 52px;
  }

  .nav-bar__inner {
    height: 52px;
    padding: 0 16px;
  }

  .nav-bar__title {
    font-size: 15px;
  }

  .page-content {
    padding: 16px 16px 32px;
  }
}
</style>
