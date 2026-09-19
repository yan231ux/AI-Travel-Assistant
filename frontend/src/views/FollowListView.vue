<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { message } from "ant-design-vue";
import { useRoute, useRouter } from "vue-router";

import { followUser, getFollowers, getFollowing, unfollowUser } from "../services/api";
import type { FollowUserVO } from "../types";
import { currentUserId } from "../stores/session";

/**
 * 关注 / 粉丝管理页（/users/:id/follows）。
 *
 * 双 Tab：关注（TA 关注了谁）、粉丝（谁关注了 TA）。
 * 每条可：① 点昵称/头像进对方主页；② 关注 / 取关（互关标识：粉丝里标「互相关注」）。
 * following 字段统一表示「当前登录者是否关注了这条记录的人」，故按钮逻辑两 Tab 共用。
 */
const props = defineProps<{ id?: string }>();
const route = useRoute();
const router = useRouter();

const targetId = ref<string>((props.id as string) || (route.params.id as string) || "");
const tab = ref<"following" | "followers">(
  route.query.tab === "followers" ? "followers" : "following"
);
const list = ref<FollowUserVO[]>([]);
const loading = ref(false);
const error = ref("");
const busyId = ref<string>("");

const isSelf = computed(() => targetId.value === currentUserId.value);

async function load() {
  if (!targetId.value.trim()) return;
  loading.value = true;
  error.value = "";
  try {
    list.value = tab.value === "following"
      ? await getFollowing(targetId.value.trim())
      : await getFollowers(targetId.value.trim());
  } catch {
    error.value = "列表加载失败，请稍后重试。";
  } finally {
    loading.value = false;
  }
}

function switchTab(t: "following" | "followers") {
  if (t === tab.value) return;
  tab.value = t;
  void load();
}

function openHome(id: string) {
  router.push({ name: "user-home", params: { id } });
}

function backToHome() {
  router.push({ name: "user-home", params: { id: targetId.value } });
}

async function toggle(item: FollowUserVO) {
  if (busyId.value || item.id === currentUserId.value) return;
  busyId.value = item.id;
  try {
    if (item.following) {
      await unfollowUser(item.id);
      item.following = false;
      message.success("已取消关注");
    } else {
      await followUser(item.id);
      item.following = true;
      message.success("关注成功");
    }
  } catch {
    message.error("操作失败，请稍后重试。");
  } finally {
    busyId.value = "";
  }
}

onMounted(() => {
  void load();
});
</script>

<template>
  <section class="fl-page">
    <div class="fl-head">
      <button type="button" class="fl-back" @click="backToHome">‹ 返回主页</button>
      <h2 class="fl-title">{{ isSelf ? "我的" : "TA 的" }}关注与粉丝</h2>
    </div>

    <!-- Tab 切换 -->
    <div class="fl-tabs">
      <button
        type="button"
        :class="['fl-tab', { 'fl-tab--on': tab === 'following' }]"
        @click="switchTab('following')"
      >
        关注
      </button>
      <button
        type="button"
        :class="['fl-tab', { 'fl-tab--on': tab === 'followers' }]"
        @click="switchTab('followers')"
      >
        粉丝
      </button>
    </div>

    <div v-if="loading" class="fl-empty">加载中…</div>
    <div v-else-if="error" class="fl-empty">{{ error }}</div>
    <div v-else-if="!list.length" class="fl-empty">
      {{ tab === "following"
        ? (isSelf ? "你还没有关注任何人，去社区发现有趣的旅行者吧。" : "TA 还没有关注任何人。")
        : (isSelf ? "还没有人关注你，多分享攻略会被更多人看到。" : "TA 还没有粉丝。") }}
    </div>

    <ul v-else class="fl-list">
      <li v-for="item in list" :key="item.id" class="fl-item">
        <div class="fl-avatar" @click="openHome(item.id)">
          {{ (item.nickname || "旅")[0].toUpperCase() }}
        </div>
        <div class="fl-meta" @click="openHome(item.id)">
          <span class="fl-name">{{ item.nickname || item.id }}</span>
          <span class="fl-tag" v-if="tab === 'followers' && item.following">互相关注</span>
        </div>
        <button
          v-if="item.id !== currentUserId"
          type="button"
          :class="['fl-btn', { 'fl-btn--on': item.following }]"
          :disabled="busyId === item.id"
          @click="toggle(item)"
        >
          {{ item.following ? "已关注" : "回关" }}
        </button>
        <button
          v-else
          type="button"
          class="fl-btn fl-btn--me"
          disabled
        >
          我
        </button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.fl-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 560px;
  margin: 0 auto;
}
.fl-head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.fl-back {
  border: none;
  background: none;
  color: var(--text-muted);
  font-size: 14px;
  cursor: pointer;
  padding: 0;
}
.fl-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
}
.fl-tabs {
  display: flex;
  gap: 8px;
}
.fl-tab {
  flex: 1;
  border: none;
  background: #fff;
  border-radius: 12px;
  padding: 10px 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-secondary);
  cursor: pointer;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}
.fl-tab--on {
  background: var(--text-primary);
  color: #fff;
}
.fl-empty {
  text-align: center;
  padding: 48px 0;
  color: var(--text-muted);
  background: #fff;
  border-radius: 14px;
  font-size: 13px;
  line-height: 1.9;
}
.fl-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.fl-item {
  display: flex;
  align-items: center;
  gap: 12px;
  background: #fff;
  border-radius: 14px;
  padding: 12px 14px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}
.fl-avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--brand-teal), var(--brand-teal));
  color: #fff;
  font-size: 18px;
  font-weight: 700;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  cursor: pointer;
}
.fl-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}
.fl-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.fl-tag {
  font-size: 11px;
  color: var(--brand-teal);
  border: 1px solid var(--brand-teal);
  border-radius: 6px;
  padding: 1px 6px;
  flex-shrink: 0;
}
.fl-btn {
  border: none;
  border-radius: 999px;
  padding: 7px 18px;
  font-size: 13px;
  font-weight: 600;
  background: var(--text-primary);
  color: #fff;
  cursor: pointer;
  flex-shrink: 0;
}
.fl-btn--on {
  background: rgba(0, 0, 0, 0.06);
  color: var(--text-secondary);
}
.fl-btn--me {
  background: rgba(0, 0, 0, 0.04);
  color: var(--text-muted);
}
.fl-btn:disabled {
  opacity: 0.6;
  cursor: default;
}
</style>
