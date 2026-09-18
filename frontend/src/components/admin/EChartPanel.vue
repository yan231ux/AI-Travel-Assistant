<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
// echarts 与本组件一起被 /admin/charts 路由懒加载，不会进入主包
import * as echarts from "echarts";

/**
 * 管理端通用图表面板（设计方案 §5.4）：
 * loading / 空态 / 错误态 / 自适应窗口大小 / tooltip；数值表由页面切换展示。
 */
const props = withDefaults(
  defineProps<{
    option: Record<string, unknown> | null;
    height?: string;
    loading?: boolean;
    error?: string | null;
    emptyText?: string;
  }>(),
  { height: "300px", loading: false, error: null, emptyText: "暂无数据" }
);

const el = ref<HTMLDivElement | null>(null);
let chart: echarts.ECharts | null = null;
let resizeObserver: ResizeObserver | null = null;

function resize() {
  if (!chart || !el.value) return;
  chart.resize();
}

/**
 * 关键：ECharts 必须在容器可见、有真实宽高后再初始化，
 * 否则（首次进入时容器被 v-show 隐藏、宽度为 0）图表会按 0 宽渲染挤在一起。
 * 因此 setOption 前后都要 nextTick + resize，把尺寸强制重算。
 */
function render() {
  if (!el.value) return;
  if (!props.option) {
    chart?.clear();
    return;
  }
  if (!chart) {
    // 容器此刻若不可见（display:none）会拿到 0 宽，先在下一帧可见后 init
    chart = echarts.init(el.value);
  }
  chart.setOption(props.option, true);
  // 等 DOM 布局稳定后，按真实尺寸重算一次，修掉“首屏挤在一起”
  void nextTick(() => resize());
}

onMounted(() => {
  // 若一开始就有 option（同步数据），直接渲染；否则等 watch 触发
  if (props.option) {
    render();
  }
  window.addEventListener("resize", resize);
  // 容器尺寸变化（含从 v-show 隐藏 → 可见）时自适应
  if (typeof ResizeObserver !== "undefined" && el.value) {
    resizeObserver = new ResizeObserver(() => resize());
    resizeObserver.observe(el.value);
  }
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", resize);
  resizeObserver?.disconnect();
  resizeObserver = null;
  chart?.dispose();
  chart = null;
});

watch(
  () => props.option,
  () => render(),
  { deep: true }
);
</script>

<template>
  <div class="ecp-wrap">
    <div v-if="loading" class="ecp-state">加载中…</div>
    <div v-else-if="error" class="ecp-state ecp-state--error">
      ⚠️ {{ error }}
    </div>
    <div v-else-if="!option" class="ecp-state">{{ emptyText }}</div>
    <div v-show="option" ref="el" class="ecp-canvas" :style="{ height }" />
  </div>
</template>

<style scoped>
.ecp-wrap {
  position: relative;
  width: 100%;
}
.ecp-state {
  min-height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  opacity: 0.7;
}
.ecp-state--error {
  color: #d4380d;
  opacity: 1;
}
.ecp-canvas {
  width: 100%;
}
</style>
