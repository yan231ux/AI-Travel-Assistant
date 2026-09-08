<script setup lang="ts">
/**
 * 品牌标识组件（UI 升级方案：登录页/导航共用）。
 * 视觉：墨黑圆角方标 + 衬线「旅」字 + 品牌名与短副标题。
 * tone=light 用于深色品牌区（如登录页左栏）；dark 用于浅色导航。
 */
withDefaults(
  defineProps<{
    size?: number;
    showText?: boolean;
    subtitle?: string;
    tone?: "dark" | "light";
  }>(),
  {
    size: 30,
    showText: true,
    subtitle: "AI 行程规划 · 旅行灵感社区",
    tone: "dark",
  },
);
</script>

<template>
  <span class="brand" :class="`brand--${tone}`">
    <span
      class="brand__mark"
      :style="{ width: `${size}px`, height: `${size}px`, borderRadius: `${Math.max(6, size * 0.3)}px`, fontSize: `${size * 0.55}px` }"
      aria-hidden="true"
    >
      旅
    </span>
    <span v-if="showText" class="brand__words">
      <span class="brand__name">智能旅行助手</span>
      <span v-if="subtitle" class="brand__subtitle">{{ subtitle }}</span>
    </span>
  </span>
</template>

<style scoped>
.brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  user-select: none;
}

.brand__mark {
  display: inline-grid;
  place-items: center;
  flex: none;
  background: var(--brand-ink);
  color: #f7f5ef;
  font-family: var(--font-serif);
  font-weight: 600;
  line-height: 1;
}

.brand__words {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
  gap: 1px;
}

.brand__name {
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--brand-ink);
  font-family: var(--font-sans);
}

.brand__subtitle {
  font-size: 10.5px;
  color: var(--text-muted);
  white-space: nowrap;
}

/* 深色底（登录页左栏等）反白 */
.brand--light .brand__mark {
  background: #f7f5ef;
  color: var(--brand-ink);
}
.brand--light .brand__name {
  color: #f7f5ef;
}
.brand--light .brand__subtitle {
  color: rgba(247, 245, 239, 0.62);
}
</style>
