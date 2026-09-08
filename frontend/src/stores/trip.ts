import { ref } from "vue";

import type { AgentTraceStep, Itinerary, TripRequestPayload } from "../types";

/**
 * 行程工作区状态单例。
 * 原 App.vue 靠 currentView + 若干 ref 做跨页编排，现收敛到本 store：
 * - 最新行程产物（Result 页展示）
 * - Agent 视图入参（live 待生成参数 / replay 回放入参）
 */

/* ---------- 最新行程产物（Result / Agent finished 写入） ---------- */
export const latestItinerary = ref<Itinerary | null>(null);
export const latestTrace = ref<AgentTraceStep[] | undefined>(undefined);
export const latestTokenUsage = ref<Record<string, number> | null>(null);

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

/** Agent live 生成完 / replay 播完点“查看完整行程” → 写入产物并进 result */
export function setFinished(
  itinerary: Itinerary,
  trace: AgentTraceStep[],
  tokenUsage?: Record<string, number> | null
) {
  latestItinerary.value = itinerary;
  latestTrace.value = trace;
  latestTokenUsage.value = tokenUsage ?? null;
}

/** History 打开已保存行程 → 直接展示完整行程（无实时 token 统计） */
export function openSaved(itinerary: Itinerary, trace?: AgentTraceStep[]) {
  latestItinerary.value = itinerary;
  latestTrace.value = trace;
  latestTokenUsage.value = null;
}

/** 退出登录时清空工作区 */
export function clearAll() {
  latestItinerary.value = null;
  latestTrace.value = undefined;
  latestTokenUsage.value = null;
  agentMode.value = "live";
  pendingPayload.value = null;
  replayItinerary.value = null;
  replayTrace.value = null;
}
