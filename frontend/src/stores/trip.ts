import { ref } from "vue";

import type { AgentTraceStep, Itinerary, TripRequestPayload } from "../types";
import { clearPlannerDraft } from "./plannerDraft";

/**
 * 行程工作区状态单例。
 * 原 App.vue 靠 currentView + 若干 ref 做跨页编排，现收敛到本 store：
 * - 最新行程产物（Result 页展示）
 * - Agent 视图入参（live 待生成参数 / replay 回放入参）
 *
 * 跨页面 + 刷新都不丢（本次改动）：产物与轨迹写入**会话级存储**，口径与规划页草稿
 * （stores/plannerDraft）完全一致 —— 同一标签页内跳页面、F5 刷新都还在；
 * 只有退出登录（clearAll）、关闭标签页/重启浏览器才清空。
 * 之前产物只在内存里：一刷新 `latestItinerary` 就变 null，结果页的守卫又把用户踢回规划页，
 * 等于"生成完的行程看一眼就没了"。
 */

const WORKSPACE_KEY = "ai_travel_trip_workspace";

/**
 * 单份行程 JSON 实测 5~13KB（trip_record 最近 30 条），加轨迹也远小于会话存储上限。
 * 这个上限纯粹是兜底：万一产物异常庞大，就"这次不落盘"，绝不让存储异常打断页面渲染。
 */
const MAX_PERSIST_CHARS = 1024 * 1024;

interface PersistedWorkspace {
  itinerary: Itinerary;
  trace?: AgentTraceStep[] | null;
  tokenUsage?: Record<string, number> | null;
}

/**
 * 读取上次的产物。只做"够用"的校验：days 不是数组就直接当没有——
 * 宁可回到规划页，也不要让结果页拿半个对象渲染崩掉（懒加载页面报错最难查）。
 */
function loadWorkspace(): PersistedWorkspace | null {
  try {
    const raw = sessionStorage.getItem(WORKSPACE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as PersistedWorkspace;
    if (!parsed || typeof parsed !== "object") return null;
    const itinerary = parsed.itinerary as Itinerary | undefined;
    if (!itinerary || typeof itinerary !== "object" || !Array.isArray(itinerary.days)) {
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}

const restoredWorkspace = loadWorkspace();

/* ---------- 最新行程产物（Result / Agent finished 写入） ---------- */
export const latestItinerary = ref<Itinerary | null>(restoredWorkspace?.itinerary ?? null);
export const latestTrace = ref<AgentTraceStep[] | undefined>(
  restoredWorkspace?.trace ?? undefined
);
export const latestTokenUsage = ref<Record<string, number> | null>(
  restoredWorkspace?.tokenUsage ?? null
);

/* ---------- Agent 视图入参（Home / Result / History 写入） ---------- */
export const agentMode = ref<"live" | "replay">("live");
/** live：本次待提交给 SSE 流的规划参数 */
export const pendingPayload = ref<TripRequestPayload | null>(null);
/** replay：要回放的行程与轨迹 */
export const replayItinerary = ref<Itinerary | null>(null);
export const replayTrace = ref<AgentTraceStep[] | null>(null);

/** Home 提交规划 → 进入 agent live 视图，边生成边展示思考过程 */
export function beginLive(payload: TripRequestPayload) {
  agentMode.value = "live";
  pendingPayload.value = payload;
  replayItinerary.value = null;
  replayTrace.value = null;
}

/** Result 页“动画回放” → 进入 agent replay 视图 */
export function beginReplay(itinerary: Itinerary, trace?: AgentTraceStep[]) {
  agentMode.value = "replay";
  pendingPayload.value = null;
  replayItinerary.value = itinerary;
  replayTrace.value = trace && trace.length > 0 ? trace : [];
}

/**
 * 把当前产物写进会话存储。
 * 只在"产物真的变了"的入口显式调用（setFinished / openSaved / clearAll），
 * 不做 deep watch：行程对象很大，逐字段深度监听白耗一次遍历，而写入点其实只有这几处。
 */
function persistWorkspace() {
  try {
    if (!latestItinerary.value) {
      sessionStorage.removeItem(WORKSPACE_KEY);
      return;
    }
    const payload = JSON.stringify({
      itinerary: latestItinerary.value,
      trace: latestTrace.value ?? null,
      tokenUsage: latestTokenUsage.value ?? null,
    } as PersistedWorkspace);
    if (payload.length > MAX_PERSIST_CHARS) {
      sessionStorage.removeItem(WORKSPACE_KEY);
      return;
    }
    sessionStorage.setItem(WORKSPACE_KEY, payload);
  } catch {
    /* 存储不可用/超配额：降级为"本次不持久化"，结果页照常渲染 */
  }
}

function clearPersistedWorkspace() {
  try {
    sessionStorage.removeItem(WORKSPACE_KEY);
  } catch {
    /* 存储不可用时无所谓：内存态已经清空了 */
  }
}

/** Agent live 生成完 / replay 播完点"查看完整行程" → 写入产物并进 result */
export function setFinished(
  itinerary: Itinerary,
  trace: AgentTraceStep[],
  tokenUsage?: Record<string, number> | null
) {
  latestItinerary.value = itinerary;
  latestTrace.value = trace;
  latestTokenUsage.value = tokenUsage ?? null;
  persistWorkspace();
}

/** History 打开已保存行程 → 直接展示完整行程（无实时 token 统计） */
export function openSaved(itinerary: Itinerary, trace?: AgentTraceStep[]) {
  latestItinerary.value = itinerary;
  latestTrace.value = trace;
  latestTokenUsage.value = null;
  persistWorkspace();
}

/**
 * 退出登录 / 登录失效时清空工作区。
 * 规划页草稿（stores/plannerDraft）与最近一份行程产物同属"本账号的临时状态"，一并清掉：
 * 否则换个账号登录进来，还会看到上一个账号填了一半的规划条件、或直接打开上一个人的行程。
 */
export function clearAll() {
  latestItinerary.value = null;
  latestTrace.value = undefined;
  latestTokenUsage.value = null;
  agentMode.value = "live";
  pendingPayload.value = null;
  replayItinerary.value = null;
  replayTrace.value = null;
  clearPlannerDraft();
  clearPersistedWorkspace();
}
