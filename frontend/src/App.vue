<script setup lang="ts">
import { message } from "ant-design-vue";
import zhCN from "ant-design-vue/es/locale/zh_CN";
import dayjs from "dayjs";
import "dayjs/locale/zh-cn";
import { onMounted, onUnmounted } from "vue";
import { useRouter } from "vue-router";

import { clearAuth } from "./stores/session";
import { clearAll } from "./stores/trip";
import { antdTheme } from "./styles/theme";

dayjs.locale("zh-cn");

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
  <a-config-provider :locale="zhCN" :theme="antdTheme">
    <router-view />
  </a-config-provider>
</template>
