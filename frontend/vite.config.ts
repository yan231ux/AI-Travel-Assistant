import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    host: "0.0.0.0",
    port: 5173,
  },
  build: {
    chunkSizeWarningLimit: 1600,
    rollupOptions: {
      output: {
        manualChunks: {
          // 三方库独立分包（缓存友好）；antd 全量注册体积较大，属已知取舍
          "vue-vendor": ["vue", "vue-router", "axios", "dayjs"],
          antd: ["ant-design-vue"],
        },
      },
    },
  },
});
