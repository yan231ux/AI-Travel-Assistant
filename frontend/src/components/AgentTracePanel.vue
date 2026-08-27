<script setup lang="ts">
import type { AgentTraceStep } from "../types";

/**
 * Agent 推理轨迹展示组件（live 流式 / replay 回放 / Result 静态展示共用）
 *
 * - highlightStep：replay 模式下高亮当前步
 * - dimOthers：replay 模式下未播放到的步淡化显示
 */
defineProps<{
  steps: AgentTraceStep[];
  highlightStep?: number | null;
  dimOthers?: boolean;
}>();
</script>

<template>
  <div class="agent-trace">
    <div
      v-for="step in steps"
      :key="step.step"
      class="agent-step"
      :class="{
        'agent-step--active': highlightStep === step.step,
        'agent-step--dim': dimOthers && highlightStep != null && step.step > highlightStep,
      }"
    >
      <div class="agent-step__header">
        <span class="agent-step__num">Step {{ step.step }}</span>
        <span v-if="step.action" class="agent-step__action">{{ step.action }}</span>
      </div>
      <div v-if="step.thought" class="agent-step__thought">💭 {{ step.thought }}</div>
      <div v-if="step.tool_calls && step.tool_calls.length > 0" class="agent-step__tools">
        <span v-for="(tool, idx) in step.tool_calls" :key="idx" class="agent-tool">
          🔧 {{ (tool as { tool?: string }).tool || "tool" }}
        </span>
      </div>
      <div v-if="step.observation" class="agent-step__obs">
        <span class="agent-step__obs-label">观察：</span>{{ step.observation }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.agent-trace { display: grid; gap: 10px; }

.agent-step {
  border-radius: 10px;
  padding: 12px 14px;
  background: #F2F2F7;
  border-left: 3px solid #007AFF;
  transition: opacity 0.3s ease, box-shadow 0.3s ease;
}

.agent-step--active {
  box-shadow: 0 0 0 2px rgba(0, 122, 255, 0.35);
  background: #FFFFFF;
}

.agent-step--dim {
  opacity: 0.35;
}

.agent-step__header {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 6px;
}

.agent-step__num {
  font-size: 12px;
  font-weight: 600;
  color: #007AFF;
  background: rgba(0, 122, 255, 0.1);
  padding: 2px 8px;
  border-radius: 6px;
}

.agent-step__action {
  font-size: 12px;
  color: #8E8E93;
  font-style: italic;
}

.agent-step__thought {
  font-size: 14px;
  color: #1C1C1E;
  line-height: 1.5;
  margin-bottom: 6px;
}

.agent-step__tools {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}

.agent-tool {
  font-size: 12px;
  background: #FFFFFF;
  padding: 3px 8px;
  border-radius: 6px;
  border: 0.5px solid rgba(0, 0, 0, 0.08);
}

.agent-step__obs {
  font-size: 13px;
  color: #636366;
  background: #FFFFFF;
  padding: 8px 10px;
  border-radius: 8px;
  line-height: 1.5;
}

.agent-step__obs-label {
  font-weight: 600;
  color: #3C3C43;
}
</style>
