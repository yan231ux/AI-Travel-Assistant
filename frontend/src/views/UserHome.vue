<script setup lang="ts">
import { onMounted, ref } from "vue";
import { message } from "ant-design-vue";
import { useRoute, useRouter } from "vue-router";

import PostCard from "../components/PostCard.vue";
import { followUser, getUserHome, unfollowUser } from "../services/api";
import type { UserHome } from "../types";

/**
 * 用户旅行主页（阶段四任务 3，/users/:id）。
 *
 * 展示目标用户的公开数据：昵称/注册时间、粉丝·关注·发帖统计、TA 的已发布攻略；
 * 非本人可关注/取关（幂等，服务端校验）。刻意不含收藏/历史行程等私有数据。
 */
const route = useRoute();
const router = useRouter();

const userId = ref<string>((route.params.id as string) || "");
const home = ref<UserHome | null>(null);
const loading = ref(true);
const error = ref("");
const busy = ref(false);

async function load() {
  if (!userId.value.trim()) return;
  loading.value = true;
  error.value = "";
  try {
    home.value = await getUserHome(userId.value.trim());
  } catch {
    error.value = "用户主页加载失败，请稍后重试。";
  } finally {
    loading.value = false;
  }
}

async function toggleFollow() {
  if (!home.value || busy.value) return;
  busy.value = true;
  try {
    if (home.value.following) {
      await unfollowUser(home.value.user_id);
      home.value.following = false;
      home.value.follower_count = Math.max(0, home.value.follower_count - 1);
      message.success("已取消关注");
    } else {
      await followUser(home.value.user_id);
      home.value.following = true;
      home.value.follower_count += 1;
      message.success("关注成功，TA 的新攻略会更容易被看到");
    }
  } catch {
    message.error("操作失败，请稍后重试。");
  } finally {
    busy.value = false;
  }
}

onMounted(() => {
  void load();
});

function openFollows(tab: "following" | "followers") {
  router.push({
    name: "user-follows",
    params: { id: home.value?.user_id },
    query: { tab },
  });
}
</script>

<template>
  <section class="uh-page">
    <div v-if="loading" class="uh-empty">加载中…</div>
    <div v-else-if="error" class="uh-empty">{{ error }}</div>
    <template v-else-if="home">
      <!-- 用户卡 -->
      <div class="uh-card">
        <div class="uh-card__avatar">{{ (home.nickname || "旅")[0].toUpperCase() }}</div>
        <div class="uh-card__info">
          <h2 class="uh-card__name">{{ home.nickname || home.user_id }}</h2>
          <p class="uh-card__sub">
            @{{ home.user_id }}<template v-if="home.created_at"> · {{ home.created_at.slice(0, 10) }} 加入</template>
          </p>
          <div class="uh-card__stats">
            <div class="uh-stat uh-stat--link" @click="openFollows('followers')">
              <b>{{ home.follower_count }}</b><span>粉丝</span>
            </div>
            <div class="uh-stat uh-stat--link" @click="openFollows('following')">
              <b>{{ home.following_count }}</b><span>关注</span>
            </div>
            <div class="uh-stat"><b>{{ home.post_count }}</b><span>攻略</span></div>
          </div>
        </div>
        <div v-if="!home.mine" class="uh-card__ops">
          <button
            type="button"
            :class="['uh-btn', { 'uh-btn--following': home.following }]"
            :disabled="busy"
            @click="toggleFollow"
          >
            {{ home.following ? "✓ 已关注" : "+ 关注" }}
          </button>
        </div>
      </div>

      <!-- TA 的攻略 -->
      <div class="uh-block">
        <h3 class="uh-block__title">📖 TA 的旅行攻略</h3>
        <div v-if="home.posts.length" class="post-grid">
          <PostCard v-for="p in home.posts" :key="p.id" :post="p" @changed="load" />
        </div>
        <div v-else class="uh-empty">
          {{ home.mine ? "你还没有发布攻略 —— 去分享第一篇吧。" : "TA 还没有发布公开攻略。" }}
          <button
            v-if="home.mine"
            type="button"
            class="uh-link"
            @click="router.push({ name: 'community' })"
          >
            去社区看看 ›
          </button>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.uh-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 860px;
  margin: 0 auto;
}
.uh-card {
  display: flex;
  align-items: center;
  gap: 16px;
  background: #fff;
  border-radius: 16px;
  padding: 18px 20px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}
.uh-card__avatar {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--brand-teal), var(--brand-teal));
  color: #fff;
  font-size: 24px;
  font-weight: 700;
  display: grid;
  place-items: center;
  flex-shrink: 0;
}
.uh-card__info {
  flex: 1;
  min-width: 0;
}
.uh-card__name {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
}
.uh-card__sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-muted);
}
.uh-card__stats {
  display: flex;
  gap: 18px;
  margin-top: 10px;
}
.uh-stat {
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.uh-stat b {
  font-size: 16px;
  color: var(--text-primary);
}
.uh-stat span {
  font-size: 12px;
  color: var(--text-muted);
}
.uh-stat--link {
  cursor: pointer;
}
.uh-stat--link:hover b {
  color: var(--brand-teal);
}
.uh-btn {
  border: none;
  border-radius: 999px;
  padding: 8px 20px;
  font-size: 14px;
  font-weight: 600;
  background: var(--text-primary);
  color: #fff;
  cursor: pointer;
  flex-shrink: 0;
}
.uh-btn--following {
  background: rgba(0, 0, 0, 0.06);
  color: var(--text-secondary);
}
.uh-btn:disabled {
  opacity: 0.6;
}
.uh-block {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.uh-block__title {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  color: var(--text-primary);
}
.uh-empty {
  text-align: center;
  padding: 40px 0;
  color: var(--text-muted);
  background: #fff;
  border-radius: 14px;
  font-size: 13px;
  line-height: 1.9;
}
.uh-link {
  border: none;
  background: none;
  color: var(--brand-teal);
  font-size: 13px;
  cursor: pointer;
  padding: 0 2px;
}
.post-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
}
</style>
