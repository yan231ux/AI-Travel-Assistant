<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";

import AgentTracePanel from "../components/AgentTracePanel.vue";
import AmapTripMap from "../components/AmapTripMap.vue";
import { streamGenerateTrip } from "../services/api";
import type { AgentTraceStep, Itinerary, TripRequestPayload } from "../types";

/**
 * Agent 过程视图（双模式）
 * - live：生成时实时消费 SSE 流，逐步展示 AI 思考，完成自动跳结果页
 * - replay：历史行程轨迹逐条播放（播放/暂停/上一步/下一步），播完展示行程+地图
 */
const props = defineProps<{
  mode: "live" | "replay";
  payload?: TripRequestPayload | null;
  itinerary?: Itinerary | null;
  trace?: AgentTraceStep[] | null;
}>();

const emit = defineEmits<{
  finished: [itinerary: Itinerary, trace: AgentTraceStep[], tokenUsage?: Record<string, number> | null];
  backHome: [];
}>();

const steps = ref<AgentTraceStep[]>([]);
const error = ref("");
const done = ref(false);

// live 状态
const phase = ref("");
const progressMsg = ref("");

// replay 状态
// currentStep：当前已播放到的 step 序号（与 AgentTraceStep.step 对齐，1..N；0 = 未开始，无高亮）
const currentStep = ref(0);
const playing = ref(false);
const replayFinished = ref(false);
let timer: ReturnType<typeof setInterval> | null = null;
let controller: AbortController | null = null;

const phaseLabels: Record<string, string> = {
  think: "🔍 规划搜索方案",
  act: "🛠️ 并行执行工具",
  assess: "🧠 反思数据充分性",
  generate: "✍️ 生成行程",
};
const phaseLabel = computed(() => phaseLabels[phase.value] || "🤔 思考中");

const displayStepLabel = computed(() => String(currentStep.value));

// 地图点位（照抄 Result.vue 的 mapPoints 计算）
const mapPoints = computed(() => {
  const it = props.itinerary;
  if (!it) return [];
  return it.days.flatMap((day) =>
    day.spots.map((spot) => ({
      key: `${day.day_index}-${spot.name}`,
      dayIndex: day.day_index,
      date: day.date || "待定",
      theme: day.theme || "",
      name: spot.name,
      address: spot.address || spot.location || "待补充",
      latitude: spot.latitude,
      longitude: spot.longitude,
      poiId: spot.poi_id,
      imageUrl: spot.image_url,
      description: spot.description || "暂无说明",
    }))
  );
});

async function runLive() {
  if (!props.payload) {
    error.value = "缺少生成参数";
    return;
  }
  error.value = "";
  done.value = false;
  steps.value = [];
  phase.value = "think";
  progressMsg.value = "正在连接生成服务…";
  controller = new AbortController();
  try {
    const result = await streamGenerateTrip(
      props.payload,
      {
        onProgress: (p, msg) => {
          phase.value = p;
          progressMsg.value = msg;
        },
        onStep: (step) => {
          steps.value.push(step);
        },
      },
      controller.signal
    );
    done.value = true;
    if (result.itinerary) {
      emit("finished", result.itinerary, result.trace, result.token_usage);
    } else {
      error.value = "生成结束但未返回行程，请重试。";
    }
  } catch (e) {
    if ((e as Error).name === "AbortError") return; // 主动取消，不提示
    error.value = (e as Error).message || "行程生成失败";
    console.error(e);
  }
}

// ===== replay 控制 =====
// currentStep 与 AgentTraceStep.step（1..N）对齐；0 = 未开始
function initReplay() {
  steps.value = props.trace && props.trace.length > 0 ? props.trace : [];
  currentStep.value = 0;
  playing.value = false;
  replayFinished.value = false;
}

function stopPlayback() {
  playing.value = false;
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
}

function advance() {
  const total = steps.value.length;
  if (total === 0) return;
  if (currentStep.value >= total) {
    stopPlayback();
    replayFinished.value = true;
    return;
  }
  currentStep.value++;
}

function play() {
  if (steps.value.length === 0) return;
  if (replayFinished.value) {
    currentStep.value = 0;
    replayFinished.value = false;
  }
  playing.value = true;
  timer = setInterval(advance, 1200);
}

function pause() {
  stopPlayback();
}

function prev() {
  if (currentStep.value > 1) currentStep.value--;
  else if (currentStep.value === 0) currentStep.value = 1;
}

function next() {
  advance();
}

function replayAgain() {
  stopPlayback();
  currentStep.value = 0;
  replayFinished.value = false;
}

function viewFull() {
  if (props.itinerary) {
    // replay 路径无实时 token 统计，传 null，下游不展示
    emit("finished", props.itinerary, props.trace || [], null);
  }
}

onMounted(() => {
  if (props.mode === "live") {
    void runLive();
  } else {
    initReplay();
  }
});

onBeforeUnmount(() => {
  stopPlayback();
  if (controller) {
    controller.abort();
    controller = null;
  }
});
</script>

<template>
  <section class="agent-page">
    <!-- 顶部状态栏 -->
    <div class="ios-card agent-status">
      <div class="agent-status__head">
        <span v-if="mode === 'live'" class="agent-status__title">🤖 AI 正在思考</span>
        <span v-else class="agent-status__title">🎬 Agent 轨迹回放</span>
        <span v-if="done" class="agent-status__badge">✅ 已完成</span>
        <span v-else-if="mode === 'replay' && replayFinished" class="agent-status__badge">🎉 播放结束</span>
      </div>

      <!-- live：进度 -->
      <template v-if="mode === 'live'">
        <div v-if="!error && !done" class="agent-progress">
          <div class="agent-progress__bar" />
        </div>
        <div class="agent-status__line">
          <span class="agent-status__phase">{{ phaseLabel }}</span>
          <span class="agent-status__msg">{{ progressMsg }}</span>
          <span class="agent-status__count">已解析 {{ steps.length }} 步</span>
        </div>
        <div v-if="error" class="agent-error">
          <p class="agent-error__msg">{{ error }}</p>
          <div class="agent-error__actions">
            <button class="ios-btn ios-btn--primary ios-btn--sm" @click="runLive">重试</button>
            <button class="ios-btn ios-btn--sm" @click="emit('backHome')">返回规划</button>
          </div>
        </div>
      </template>

      <!-- replay：控制条 -->
      <template v-else>
        <div class="agent-controls">
          <button class="ios-btn ios-btn--sm" :disabled="steps.length === 0" @click="prev">◀ 上一步</button>
          <button
            v-if="!playing"
            class="ios-btn ios-btn--primary ios-btn--sm"
            :disabled="steps.length === 0"
            @click="play"
          >
            ▶ 播放
          </button>
          <button v-else class="ios-btn ios-btn--sm" @click="pause">⏸ 暂停</button>
          <button class="ios-btn ios-btn--sm" :disabled="steps.length === 0" @click="next">下一步 ▶</button>
          <button class="ios-btn ios-btn--sm" :disabled="steps.length === 0" @click="replayAgain">↻ 重播</button>
          <span class="agent-controls__count">第 {{ displayStepLabel }} / {{ steps.length }} 步</span>
          <button class="ios-btn ios-btn--primary ios-btn--sm" :disabled="!itinerary" @click="viewFull">
            查看完整行程
          </button>
        </div>
      </template>
    </div>

    <!-- 轨迹面板 -->
    <div class="ios-card">
      <div class="ios-card__header">🤖 Agent 推理过程</div>
      <AgentTracePanel
        :steps="steps"
        :highlight-step="mode === 'replay' ? currentStep : null"
        :dim-others="mode === 'replay'"
      />
      <div v-if="mode === 'live' && steps.length === 0 && !error && !done" class="agent-pending">
        等待 AI 输出第一步…
      </div>
    </div>

    <!-- replay：播完展示行程 + 地图 -->
    <div v-if="mode === 'replay' && replayFinished && itinerary" class="ios-card">
      <div class="ios-card__header">📋 最终行程</div>
      <div class="replay-result">
        <div class="replay-result__dest">{{ itinerary.destination }}旅行计划</div>
        <p class="replay-result__summary">{{ itinerary.summary }}</p>
        <div class="replay-result__budget">
          预估总费用：<strong>¥{{ itinerary.estimated_budget.toFixed(0) }}</strong>
        </div>
        <div class="replay-result__days">
          <div v-for="day in itinerary.days" :key="day.day_index" class="replay-day">
            <span class="replay-day__title">第{{ day.day_index }}天 · {{ day.theme || "" }}</span>
            <span class="replay-day__spots">{{ day.spots.map((s) => s.name).join(" → ") || "未安排" }}</span>
          </div>
        </div>
        <div class="replay-result__map">
          <AmapTripMap :points="mapPoints" />
        </div>
        <button class="ios-btn ios-btn--primary" style="margin-top: 12px" @click="viewFull">
          查看完整行程
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.agent-page {
  display: grid;
  gap: 12px;
  max-width: 860px;
  margin: 0 auto;
}

.ios-card {
  padding: 20px;
  border-radius: 12px;
  background: #FFFFFF;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.ios-card__header {
  font-size: 15px;
  font-weight: 600;
  color: #1C1C1E;
  margin-bottom: 14px;
  padding-bottom: 10px;
  border-bottom: 0.5px solid rgba(0, 0, 0, 0.06);
}

/* 按钮 */
.ios-btn {
  border: none;
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.ios-btn:active { transform: scale(0.97); }
.ios-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.ios-btn--primary { background: #007AFF; color: #FFFFFF; }
.ios-btn--sm { padding: 6px 14px; font-size: 13px; }

/* 状态栏 */
.agent-status__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.agent-status__title {
  font-size: 17px;
  font-weight: 700;
  color: #1C1C1E;
}

.agent-status__badge {
  font-size: 13px;
  font-weight: 600;
  color: #34C759;
  background: rgba(52, 199, 89, 0.12);
  padding: 4px 10px;
  border-radius: 20px;
}

.agent-status__line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: #636366;
}

.agent-status__phase {
  font-weight: 600;
  color: #007AFF;
}

.agent-status__msg { color: #8E8E93; }
.agent-status__count { margin-left: auto; color: #8E8E93; }

/* 不确定型进度条 */
.agent-progress {
  height: 4px;
  border-radius: 2px;
  overflow: hidden;
  background: rgba(0, 122, 255, 0.12);
  margin-bottom: 12px;
}

.agent-progress__bar {
  width: 30%;
  height: 100%;
  border-radius: 2px;
  background: #007AFF;
  animation: agent-slide 1.2s ease-in-out infinite;
}

@keyframes agent-slide {
  0% { transform: translateX(-100%); }
  100% { transform: translateX(400%); }
}

/* 错误提示 */
.agent-error {
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: 10px;
  background: rgba(255, 59, 48, 0.08);
  border: 0.5px solid rgba(255, 59, 48, 0.2);
}

.agent-error__msg {
  margin: 0 0 10px;
  font-size: 13px;
  color: #FF3B30;
}

.agent-error__actions {
  display: flex;
  gap: 8px;
}

/* replay 控制条 */
.agent-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.agent-controls__count {
  margin-left: auto;
  font-size: 13px;
  color: #8E8E93;
  font-variant-numeric: tabular-nums;
}

/* 等待提示 */
.agent-pending {
  text-align: center;
  color: #8E8E93;
  font-size: 14px;
  padding: 24px 0;
}

/* replay 结果卡 */
.replay-result__dest {
  font-size: 20px;
  font-weight: 700;
  color: #1C1C1E;
  margin-bottom: 8px;
}

.replay-result__summary {
  margin: 0 0 12px;
  font-size: 14px;
  line-height: 1.7;
  color: #3C3C43;
}

.replay-result__budget {
  font-size: 14px;
  color: #3C3C43;
  margin-bottom: 12px;
}

.replay-result__budget strong {
  color: #007AFF;
  font-size: 18px;
}

.replay-result__days {
  display: grid;
  gap: 8px;
  margin-bottom: 14px;
}

.replay-day {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #F2F2F7;
}

.replay-day__title {
  font-size: 14px;
  font-weight: 600;
  color: #1C1C1E;
}

.replay-day__spots {
  font-size: 13px;
  color: #636366;
  line-height: 1.5;
}

.replay-result__map {
  min-height: 320px;
  border-radius: 10px;
  overflow: hidden;
}
</style>
