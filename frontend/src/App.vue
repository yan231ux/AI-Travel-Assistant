<script setup lang="ts">
import { message } from "ant-design-vue";
import { onMounted, onUnmounted } from "vue";
import { useRouter } from "vue-router";

import { clearAuth } from "./stores/session";
import { clearAll } from "./stores/trip";

const router = useRouter();

/** 登录失效（401）全局回登录页：清会话 + 清工作区 */
function handleUnauthorized() {
  clearAuth();
  clearAll();
  void router.replace({ name: "login" });
  message.warning("登录已过期，请重新登录");
}

onMounted(() => {
  window.addEventListener("auth:unauthorized", handleUnauthorized);
});
onUnmounted(() => {
  window.removeEventListener("auth:unauthorized", handleUnauthorized);
});
</script>

<template>
  <router-view />
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
</style>
