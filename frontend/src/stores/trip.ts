import { ref } from "vue";

import type { AgentTraceStep, Itinerary, TripRequestPayload } from "../types";
import { clearPlannerDraft } from "./plannerDraft";
import { clearTwoTier, readTwoTier, writeTwoTier } from "./scopedStorage";

/**
 * 行程工作区状态单例。
 * 原 App.vue 靠 currentView + 若干 ref 做跨页编排，现收敛到本 store：
 * - 最新行程产物（Result 页展示）
 * - Agent 视图入参（live 待生成参数 / replay 回放入参）
 *
 * "生成完的行程不能跳个页面就没了"（本次改动）：产物走**两级存储**（见 stores/scopedStorage）——
 * 同一标签页内跳页面 / F5 刷新靠会话级，关掉标签页 / 新开标签页 / 浏览器重启靠持久级；
 * 只有退出登录（clearAll → 两处一起清）才真正丢掉。
 * 持久级按登录账号隔离，换个账号登录不会看到上一个人的行程。
 *
 * 之前只在内存里：一刷新 `latestItinerary` 就变 null，结果页守卫把用户踢回规划页，
 * 等于"生成完的行程看一眼就没了"。
 */

const WORKSPACE_KEY = "ai_travel_trip_workspace";
/** 持久级镜像键（结构外面多包一层 ownerId / savedAt，见 scopedStorage） */
const WORKSPACE_DURABLE_KEY = "ai_travel_trip_workspace_durable";

/**
 * 单份行程 JSON 实测 5~13KB（trip_record 最近 30 条），加轨迹也远小于存储上限。
 * 这个上限纯粹是兜底：万一产物异常庞大，就"这次不落盘"，绝不让存储异常打断页面渲染。
 */
const MAX_PERSIST_CHARS = 1024 * 1024;

interface PersistedWorkspace {
  itinerary: Itinerary;
  trace?: AgentTraceStep[] | null;
  tokenUsage?: Record<string, number> | null;
}

/**
 * 校验一份产物"够不够用来渲染"：只认 days 是数组的那种。
 * 宁可回到规划页，也不要让结果页拿半个对象渲染崩掉（懒加载页面报错最难查）。
 */
function normalizeWorkspace(raw: unknown): PersistedWorkspace | null {
  if (!raw || typeof raw !== "object") return null;
  const itinerary = (raw as PersistedWorkspace).itinerary as Itinerary | undefined;
  if (!itinerary || typeof itinerary !== "object" || !Array.isArray(itinerary.days)) {
    return null;
  }
  return raw as PersistedWorkspace;
}

const restoredWorkspace = readTwoTier(
  WORKSPACE_KEY,
  WORKSPACE_DURABLE_KEY,
  normalizeWorkspace
);

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
 * 把当前产物写进两级存储。
 * 只在"产物真的变了"的入口显式调用（setFinished / openSaved / clearAll），
 * 不做 deep watch：行程对象很大，逐字段深度监听白耗一次遍历，而写入点其实只有这几处。
 */
function persistWorkspace() {
  if (!latestItinerary.value) {
    clearTwoTier(WORKSPACE_KEY, WORKSPACE_DURABLE_KEY);
    return;
  }
  const workspace: PersistedWorkspace = {
    itinerary: latestItinerary.value,
    trace: latestTrace.value ?? null,
    tokenUsage: latestTokenUsage.value ?? null,
  };
  try {
    if (JSON.stringify(workspace).length > MAX_PERSIST_CHARS) {
      // 产物异常庞大：这次不落盘，结果页照常渲染
      clearTwoTier(WORKSPACE_KEY, WORKSPACE_DURABLE_KEY);
      return;
    }
  } catch {
    // 循环引用等序列化异常：同样放弃本次持久化，不影响内存态与页面渲染
    return;
  }
  writeTwoTier(WORKSPACE_KEY, WORKSPACE_DURABLE_KEY, workspace);
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
  clearTwoTier(WORKSPACE_KEY, WORKSPACE_DURABLE_KEY);
}
