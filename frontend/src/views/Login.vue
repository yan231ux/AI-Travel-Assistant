<script setup lang="ts">
import { message } from "ant-design-vue";
import { reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";

import BrandMark from "../components/BrandMark.vue";
import { login, register, adminLogin } from "../services/api";
import { setAuthed, isAdminSideRole } from "../stores/session";
import type { User } from "../types";

/** 仅允许站内路径的登录后回跳（防开放重定向） */
function safeRedirect(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  if (!raw.startsWith("/") || raw.startsWith("//")) return null;
  return raw;
}

const route = useRoute();
const router = useRouter();

type Mode = "login" | "register" | "admin";
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
  setAuthed({ token, user });
  message.success(okMessage);
  // 回跳登录前想去的页面（站内白名单过滤）；
  // 管理员一律进 /admin 运营后台，普通用户进首页 Dashboard（设计方案 §3.1）
  const redirect = safeRedirect(route.query.redirect);
  if (redirect) {
    void router.replace({ path: redirect });
    return;
  }
  void router.replace({ name: isAdminSideRole(user.role) ? "admin-dashboard" : "dashboard" });
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
    } else if (mode.value === "admin") {
      // 管理员登录：独立入口，非管理端账号会被后端 403 拦截
      const resp = await adminLogin({
        username: form.username.trim(),
        password: form.password,
      });
      if (!resp.success) return message.error(resp.message || "管理员登录失败");
      applyAuth(resp.token, resp.user, "管理员登录成功");
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
    if (status === 403 && mode.value === "admin") return message.error("该账号不是管理员，请使用普通登录");
    console.error(error);
    message.error(mode.value === "register" ? "注册失败，请稍后重试" : "登录失败，请稍后重试");
  } finally {
    submitting.value = false;
  }
}

/* 登录/注册/管理员 左侧品牌叙事文案（方案 §5.2：随模式切换） */
const BRAND_COPY = {
  login: {
    headline: "你的下一段旅程，\n从这里开始",
    points: ["懂你：根据旅行偏好生成更贴合的路线", "省心：目的地、景点到每日安排一次整理", "有灵感：从旅行者分享中发现下一站"],
  },
  register: {
    headline: "创建你的旅行空间",
    points: ["完善偏好后，推荐会更懂你", "行程、收藏与灵感都会整理在这里", "分享攻略，成为他人的目的地灵感"],
  },
  admin: {
    headline: "内容运营，\n从这里接管",
    points: ["内容审核：帖子与评论的通过、拒绝、下线", "数据看板：热度、转化与审核漏斗一目了然", "治理留痕：每一次操作都记入审计日志"],
  },
} as const;
</script>

<template>
  <section class="login-page">
    <!-- 左：品牌与产品价值（深色区，方案 §5.1） -->
    <aside class="login-story">
      <div class="login-story__bg" aria-hidden="true" />
      <div class="login-story__inner">
        <BrandMark :size="34" tone="light" subtitle="让每一次出发，都更像为你准备的" />

        <div class="login-story__copy">
          <p class="login-story__eyebrow">AI 行程规划 · 个性化推荐 · 旅行社区</p>
          <h1 class="login-story__headline font-serif">{{ BRAND_COPY[mode].headline }}</h1>
          <ul class="login-story__points">
            <li v-for="p in BRAND_COPY[mode].points" :key="p" class="login-story__point">{{ p }}</li>
          </ul>
        </div>

        <p class="login-story__foot">从一次旅行开始，建立你的专属旅行记忆</p>
      </div>
    </aside>

    <!-- 右：登录注册表单 -->
    <main class="login-panel">
      <div class="login-panel__inner">
        <div class="login-panel__tabs" role="tablist">
          <button
            type="button"
            role="tab"
            :aria-selected="mode === 'login'"
            :class="['login-tab', { 'login-tab--active': mode === 'login' }]"
            @click="switchMode('login')"
          >
            登录
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="mode === 'register'"
            :class="['login-tab', { 'login-tab--active': mode === 'register' }]"
            @click="switchMode('register')"
          >
            注册
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="mode === 'admin'"
            :class="['login-tab', { 'login-tab--active': mode === 'admin' }]"
            @click="switchMode('admin')"
          >
            管理员登录
          </button>
        </div>

        <h2 class="login-panel__title">
          {{ mode === "login" ? "欢迎回来" : mode === "register" ? "加入我们" : "运营后台登录" }}
        </h2>
        <p class="login-panel__sub">
          {{
            mode === "login"
              ? "登录后继续你的专属旅行"
              : mode === "register"
                ? "注册即自动登录，马上开始第一次规划"
                : "仅限运营人员，登录后直接进入内容运营后台"
          }}
        </p>

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
            {{ mode === "login" ? "登 录" : mode === "register" ? "注册并登录" : "管理员登录" }}
          </a-button>
        </a-form>

        <p class="login-panel__foot">
          <template v-if="mode === 'admin'">
            <a class="login-panel__link" @click="switchMode('login')">返回用户登录</a>
          </template>
          <template v-else>
            {{ mode === "login" ? "还没有账号？" : "已有账号？" }}
            <a class="login-panel__link" @click="switchMode(mode === 'login' ? 'register' : 'login')">
              {{ mode === "login" ? "立即注册" : "去登录" }}
            </a>
            <span class="login-panel__divider">·</span>
            <a class="login-panel__link login-panel__link--dim" @click="switchMode('admin')">管理员登录</a>
          </template>
        </p>

        <p class="login-panel__privacy">登录即代表你同意仅将本系统用于课程设计演示，请勿提交真实敏感信息。</p>
      </div>
    </main>
  </section>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(380px, 1fr);
}

/* ===== 左：品牌叙事区（深色） ===== */
.login-story {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  background: var(--brand-deep);
  color: #f7f5ef;
  padding: 48px;
}

/* 极简装饰：低透明度同心圆（呼应"目的地/地平线"），非发光球体 */
.login-story__bg {
  position: absolute;
  inset: -20% -10% auto auto;
  width: 520px;
  height: 520px;
  background:
    radial-gradient(circle at 32% 30%, rgba(230, 184, 92, 0.16) 0 2px, transparent 3px),
    radial-gradient(circle at 70% 60%, rgba(247, 245, 239, 0.1) 0 2px, transparent 3px),
    radial-gradient(circle at 55% 45%, transparent 0 42%, rgba(247, 245, 239, 0.05) 43% 43.5%, transparent 44%),
    radial-gradient(circle at 55% 45%, transparent 0 58%, rgba(247, 245, 239, 0.05) 59% 59.5%, transparent 60%);
  pointer-events: none;
}

.login-story__inner {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 40px;
  max-width: 520px;
}

.login-story__eyebrow {
  margin: 0 0 12px;
  font-size: 12px;
  letter-spacing: 0.18em;
  color: var(--brand-sun);
  text-transform: uppercase;
}

.login-story__headline {
  margin: 0;
  font-size: clamp(30px, 4vw, 46px);
  line-height: 1.32;
  font-weight: 700;
  color: #f7f5ef;
  white-space: pre-line;
}

.login-story__points {
  list-style: none;
  margin: 18px 0 0;
  padding: 0;
  display: grid;
  gap: 12px;
}

.login-story__point {
  position: relative;
  padding-left: 18px;
  font-size: 14.5px;
  line-height: 1.6;
  color: rgba(247, 245, 239, 0.82);
}
.login-story__point::before {
  content: "";
  position: absolute;
  left: 0;
  top: 0.62em;
  width: 8px;
  height: 2px;
  border-radius: 2px;
  background: var(--brand-coral);
}

.login-story__foot {
  margin: auto 0 0;
  padding-top: 32px;
  font-size: 12.5px;
  color: rgba(247, 245, 239, 0.55);
}

/* ===== 右：表单区 ===== */
.login-panel {
  display: grid;
  place-items: center;
  padding: 40px 24px;
  background: var(--surface-paper);
}

.login-panel__inner {
  width: 100%;
  max-width: 380px;
}

.login-panel__tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 26px;
  border-bottom: 1px solid var(--border-soft);
}

.login-tab {
  position: relative;
  border: none;
  background: none;
  padding: 8px 2px 10px;
  margin-right: 20px;
  font-size: 14px;
  font-weight: 550;
  color: var(--text-muted);
  cursor: pointer;
  transition: color 0.2s var(--ease);
}
.login-tab:hover {
  color: var(--text-primary);
}
.login-tab--active {
  color: var(--brand-ink);
  font-weight: 700;
}
.login-tab--active::after {
  content: "";
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2.5px;
  border-radius: 2px;
  background: var(--brand-coral);
}

.login-panel__title {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
}

.login-panel__sub {
  margin: 6px 0 22px;
  font-size: 13.5px;
  color: var(--text-secondary);
}

.login-panel__foot {
  margin: 18px 0 0;
  text-align: center;
  font-size: 13px;
  color: var(--text-secondary);
}

.login-panel__link {
  font-weight: 600;
  cursor: pointer;
}

.login-panel__divider {
  margin: 0 8px;
  color: var(--text-muted);
}

.login-panel__link--dim {
  font-weight: 500;
  color: var(--text-secondary);
}

.login-panel__privacy {
  margin: 22px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  text-align: center;
  color: var(--text-muted);
}

/* ===== 移动端：上下结构（方案 §5.1/§8） ===== */
@media (max-width: 860px) {
  .login-page {
    grid-template-columns: 1fr;
  }

  .login-story {
    padding: 28px 24px 20px;
    align-items: flex-start;
    min-height: auto;
  }

  .login-story__inner {
    gap: 18px;
  }

  .login-story__copy {
    margin-top: 6px;
  }

  .login-story__eyebrow,
  .login-story__points,
  .login-story__foot {
    display: none;
  }

  .login-story__headline {
    font-size: 22px;
    white-space: normal;
  }

  .login-panel {
    padding: 28px 20px 36px;
  }
}
</style>
