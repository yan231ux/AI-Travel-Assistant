<script setup lang="ts">
import { message } from "ant-design-vue";
import { reactive, ref } from "vue";

import { login, register, setToken, setUser } from "../services/api";
import type { User } from "../types";

const emit = defineEmits<{
  authed: [payload: { token: string; user: User }];
}>();

type Mode = "login" | "register";
const mode = ref<Mode>("login");
const submitting = ref(false);

const form = reactive({
  username: "",
  password: "",
  nickname: "",
});

const rules = {
  username: [
    { required: true, message: "请输入用户名" },
    { min: 2, max: 50, message: "用户名长度需在 2-50 位" },
  ],
  password: [
    { required: true, message: "请输入密码" },
    { min: 6, max: 100, message: "密码长度需在 6-100 位" },
  ],
  nickname: [{ max: 50, message: "昵称最长 50 位" }],
};

function switchMode(next: Mode) {
  mode.value = next;
  form.password = "";
}

function applyAuth(token: string, user: User, okMessage: string) {
  setToken(token);
  setUser(user);
  message.success(okMessage);
  emit("authed", { token, user });
}

async function handleSubmit() {
  if (submitting.value) return;
  if (!form.username.trim()) return message.warning("请输入用户名");
  if (!form.password) return message.warning("请输入密码");

  submitting.value = true;
  try {
    if (mode.value === "register") {
      if (!form.nickname.trim()) return message.warning("注册时请填写昵称");
      const resp = await register({
        username: form.username.trim(),
        password: form.password,
        nickname: form.nickname.trim(),
      });
      if (!resp.success) return message.error(resp.message || "注册失败");
      applyAuth(resp.token, resp.user, `注册成功，欢迎 ${resp.user.nickname || resp.user.username}！`);
    } else {
      const resp = await login({
        username: form.username.trim(),
        password: form.password,
      });
      if (!resp.success) return message.error(resp.message || "登录失败");
      applyAuth(resp.token, resp.user, `登录成功，欢迎回来 ${resp.user.nickname || resp.user.username}！`);
    }
  } catch (error: unknown) {
    const status = (error as { response?: { status?: number } })?.response?.status;
    if (status === 409) return message.error("用户名已存在，请直接登录或换一个用户名");
    if (status === 401) return message.error("用户名或密码错误");
    console.error(error);
    message.error(mode.value === "register" ? "注册失败，请稍后重试" : "登录失败，请稍后重试");
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <section class="login-page">
    <div class="login-card">
      <div class="login-card__brand">
        <div class="login-card__logo">🧭</div>
        <h1 class="login-card__title">智能旅行助手</h1>
        <p class="login-card__subtitle">登录后保存你的专属行程</p>
      </div>

      <div class="login-card__tabs">
        <button
          :class="['login-tab', { 'login-tab--active': mode === 'login' }]"
          @click="switchMode('login')"
        >
          登录
        </button>
        <button
          :class="['login-tab', { 'login-tab--active': mode === 'register' }]"
          @click="switchMode('register')"
        >
          注册
        </button>
      </div>

      <a-form layout="vertical" :rules="rules" :model="form" @finish="handleSubmit">
        <a-form-item label="用户名" name="username">
          <a-input
            v-model:value="form.username"
            size="large"
            placeholder="请输入用户名"
            :maxlength="50"
            autocomplete="username"
            allow-clear
          />
        </a-form-item>

        <a-form-item v-if="mode === 'register'" label="昵称" name="nickname">
          <a-input
            v-model:value="form.nickname"
            size="large"
            placeholder="展示给其他用户的名字（选填）"
            :maxlength="50"
          />
        </a-form-item>

        <a-form-item label="密码" name="password">
          <a-input-password
            v-model:value="form.password"
            size="large"
            placeholder="至少 6 位"
            autocomplete="current-password"
          />
        </a-form-item>

        <a-button
          type="primary"
          size="large"
          block
          :loading="submitting"
          @click="handleSubmit"
        >
          {{ mode === "login" ? "登 录" : "注册并登录" }}
        </a-button>
      </a-form>

      <p class="login-card__foot">
        {{ mode === "login" ? "还没有账号？" : "已有账号？" }}
        <a class="login-card__link" @click="switchMode(mode === 'login' ? 'register' : 'login')">
          {{ mode === "login" ? "立即注册" : "去登录" }}
        </a>
      </p>
    </div>
  </section>
</template>

<style scoped>
.login-page {
  min-height: calc(100vh - 56px);
  display: grid;
  place-items: center;
  padding: 40px 20px;
}

.login-card {
  width: 100%;
  max-width: 400px;
  padding: 32px 28px 24px;
  border-radius: 16px;
  background: #FFFFFF;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
}

.login-card__brand {
  text-align: center;
  margin-bottom: 20px;
}

.login-card__logo {
  font-size: 40px;
  line-height: 1;
  margin-bottom: 8px;
}

.login-card__title {
  margin: 0 0 4px;
  font-size: 22px;
  font-weight: 700;
  color: #1C1C1E;
}

.login-card__subtitle {
  margin: 0;
  font-size: 14px;
  color: #8E8E93;
}

.login-card__tabs {
  display: flex;
  gap: 2px;
  padding: 3px;
  border-radius: 10px;
  background: rgba(0, 0, 0, 0.04);
  margin-bottom: 20px;
}

.login-tab {
  flex: 1;
  border: none;
  border-radius: 8px;
  padding: 8px 0;
  background: transparent;
  color: #8E8E93;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.login-tab--active {
  background: #FFFFFF;
  color: #1C1C1E;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.login-card__foot {
  margin: 16px 0 0;
  text-align: center;
  font-size: 13px;
  color: #8E8E93;
}

.login-card__link {
  color: #007AFF;
  cursor: pointer;
}
</style>
