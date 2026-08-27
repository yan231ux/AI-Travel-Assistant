<script setup lang="ts">
import { message } from "ant-design-vue";
import { onMounted, onUnmounted, ref } from "vue";

import { clearToken, getUser } from "./services/api";
import type { AgentTraceStep, Itinerary, TripRequestPayload, User } from "./types";
import AgentProcess from "./views/AgentProcess.vue";
import History from "./views/History.vue";
import Home from "./views/Home.vue";
import Login from "./views/Login.vue";
import Result from "./views/Result.vue";

const currentView = ref<"home" | "agent" | "result" | "history">("home");
const latestItinerary = ref<Itinerary | null>(null);
const latestTrace = ref<AgentTraceStep[] | undefined>(undefined);
const latestTokenUsage = ref<Record<string, number> | null>(null);

/* ---------- 登录态 ---------- */
const token = ref<string | null>(null);
const user = ref<User | null>(null);

function initAuth() {
  token.value = localStorage.getItem("ai_travel_token");
  user.value = getUser();
  if (!token.value) currentView.value = "home";
}

function handleAuthed(payload: { token: string; user: User }) {
  token.value = payload.token;
  user.value = payload.user;
  currentView.value = "home";
}

/** 登录失效（401）全局回登录页 */
function handleUnauthorized() {
  token.value = null;
  user.value = null;
  currentView.value = "home";
  message.warning("登录已过期，请重新登录");
}

function handleLogout() {
  clearToken();
  token.value = null;
  user.value = null;
  latestItinerary.value = null;
  latestTrace.value = undefined;
  latestTokenUsage.value = null;
  currentView.value = "home";
  message.success("已退出登录");
}

onMounted(() => {
  initAuth();
  window.addEventListener("auth:unauthorized", handleUnauthorized);
});
onUnmounted(() => {
  window.removeEventListener("auth:unauthorized", handleUnauthorized);
});

const displayName = () => user.value?.nickname || user.value?.username || "";

// Agent 过程视图参数（live 实时生成 / replay 历史回放）
const agentMode = ref<"live" | "replay">("live");
const agentPayload = ref<TripRequestPayload | null>(null);
const replayItinerary = ref<Itinerary | null>(null);
const replayTrace = ref<AgentTraceStep[] | null>(null);

function handleGenerated(itinerary: Itinerary, trace?: AgentTraceStep[]) {
  latestItinerary.value = itinerary;
  latestTrace.value = trace;
  latestTokenUsage.value = null;
  currentView.value = "result";
}

/** Home 提交 → 进入 agent live 视图，边生成边展示思考过程 */
function startGenerate(payload: TripRequestPayload) {
  agentMode.value = "live";
  agentPayload.value = payload;
  replayItinerary.value = null;
  replayTrace.value = null;
  currentView.value = "agent";
}

/** History 打开行程 → 直接展示完整行程信息；推理流程由用户决定是否观看（Result 页提供"回放"入口） */
function openTripWithReplay(itinerary: Itinerary, trace?: AgentTraceStep[]) {
  latestItinerary.value = itinerary;
  latestTrace.value = trace;
  latestTokenUsage.value = null;
  currentView.value = "result";
}

/** Result 页点击"🎬 动画回放"→ 进入 agent 回放视图 */
function handleViewReplay() {
  if (!latestItinerary.value) return;
  agentMode.value = "replay";
  agentPayload.value = null;
  replayItinerary.value = latestItinerary.value;
  replayTrace.value = latestTrace.value || [];
  currentView.value = "agent";
}

/** Agent 视图完成（live 生成完 / replay 播完点"查看完整行程"）→ 进 result */
function handleAgentFinished(itinerary: Itinerary, trace: AgentTraceStep[], tokenUsage?: Record<string, number> | null) {
  latestItinerary.value = itinerary;
  latestTrace.value = trace;
  latestTokenUsage.value = tokenUsage ?? null;
  currentView.value = "result";
}
</script>

<template>
  <Login v-if="!token" @authed="handleAuthed" />

  <div v-else class="app-shell">
    <header class="nav-bar">
      <div class="nav-bar__inner">
        <span class="nav-bar__title">智能旅行助手</span>
        <div class="nav-bar__right">
          <div class="nav-bar__tabs">
            <button
              type="button"
              :class="['nav-tab', { 'nav-tab--active': currentView === 'home' }]"
              @click="currentView = 'home'"
            >
              规划
            </button>
            <button
              type="button"
              :class="[
                'nav-tab',
                { 'nav-tab--active': currentView === 'result' },
                { 'nav-tab--disabled': !latestItinerary }
              ]"
              :disabled="!latestItinerary"
              @click="currentView = 'result'"
            >
              结果
            </button>
            <button
              type="button"
              :class="['nav-tab', { 'nav-tab--active': currentView === 'history' }]"
              @click="currentView = 'history'"
            >
              历史
            </button>
          </div>
          <div class="nav-bar__user">
            <span class="nav-bar__username">{{ displayName() }}</span>
            <button type="button" class="nav-bar__logout" @click="handleLogout">退出</button>
          </div>
        </div>
      </div>
    </header>

    <main class="page-content">
      <Home
        v-if="currentView === 'home'"
        @start-generate="startGenerate"
      />
      <AgentProcess
        v-else-if="currentView === 'agent'"
        :mode="agentMode"
        :payload="agentPayload"
        :itinerary="replayItinerary"
        :trace="replayTrace"
        @finished="handleAgentFinished"
        @back-home="currentView = 'home'"
      />
      <Result
        v-else-if="currentView === 'result'"
        :itinerary="latestItinerary"
        :agent-trace="latestTrace"
        :token-usage="latestTokenUsage"
        @back-home="currentView = 'home'"
        @view-history="currentView = 'history'"
        @view-replay="handleViewReplay"
      />
      <History
        v-else
        :active="currentView === 'history'"
        @open-trip="openTripWithReplay"
      />
    </main>
  </div>
</template>

<style scoped>
:global(body) {
  margin: 0;
  min-width: 320px;
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", "PingFang SC", "Microsoft YaHei", sans-serif;
  background: #F2F2F7;
  color: #1C1C1E;
  -webkit-font-smoothing: antialiased;
}

:global(*) {
  box-sizing: border-box;
}

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
