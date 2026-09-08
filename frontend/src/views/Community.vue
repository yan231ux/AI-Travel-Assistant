<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";

import PostCard from "../components/PostCard.vue";
import { POPULAR_CITIES } from "../constants/cities";
import { getPosts, newFeedTrace } from "../services/api";
import type { PostSort } from "../services/api";
import { isAdmin } from "../stores/session";
import type { PostItem } from "../types";

/**
 * 社区帖子流（阶段二 /community；阶段三起提供"为你推荐"）。
 * 排序 Tab：为你推荐（画像个性化，无画像自动降级热门）/ 热门 / 最新；
 * 类型 chips：全部/城市攻略/景点推荐/行程分享/随笔；城市 chips 快速筛选。
 * 右上：发帖（人人可发）；内容审核（仅 ADMIN 显示，服务端仍会二次校验）。
 */
const router = useRouter();

const items = ref<PostItem[]>([]);
const total = ref(0);
const page = ref(1);
const PAGE_SIZE = 12;
const loading = ref(true);
const loadingMore = ref(false);
const error = ref("");

const typeTabs = [
  { key: "", label: "全部" },
  { key: "GUIDE", label: "城市攻略" },
  { key: "SPOT_RECOMMENDATION", label: "景点推荐" },
  { key: "ITINERARY", label: "行程分享" },
  { key: "NOTE", label: "旅行随笔" },
];
const sortTabs = [
  { key: "recommended", label: "✨ 为你推荐" },
  { key: "popular", label: "热门" },
  { key: "latest", label: "最新" },
];

const currentType = ref("");
const currentSort = ref<PostSort>("recommended");
const currentCity = ref("");

async function fetchPage(first: boolean) {
  if (first) {
    loading.value = true;
  } else {
    loadingMore.value = true;
  }
  error.value = "";
  try {
    const resp = await getPosts({
      city: currentCity.value || undefined,
      postType: currentType.value || undefined,
      sort: currentSort.value,
      page: first ? 1 : page.value + 1,
      pageSize: PAGE_SIZE,
    });
    page.value = first ? 1 : page.value + 1;
    total.value = resp.total;
    items.value = first ? resp.items : [...items.value, ...resp.items];
  } catch {
    error.value = "帖子加载失败，请稍后重试。";
  } finally {
    loading.value = false;
    loadingMore.value = false;
  }
}

function changeTab() {
  void fetchPage(true);
}

function loadMore() {
  if (loading.value || loadingMore.value) return;
  if (items.value.length >= total.value) return;
  void fetchPage(false);
}

function goCreate() {
  void router.push({ name: "post-create" });
}

function goMy() {
  void router.push({ name: "my-posts" });
}

function goModeration() {
  void router.push({ name: "moderation" });
}

function skeletons(n: number) {
  return new Array(n).fill(0).map((_, i) => i);
}

onMounted(() => {
  void fetchPage(true);
});

// 排序/类型切换刷新（watch 避免同值触发）
watch(currentType, () => changeTab());
watch(currentSort, () => changeTab());
watch(currentCity, () => changeTab());
</script>

<template>
  <section class="cm-page">
    <div class="cm-head">
      <div>
        <h2 class="cm-head__title">🌏 旅行社区</h2>
        <p class="cm-head__desc">真实用户攻略与行程分享 · 发布需管理员审核后公开展示</p>
      </div>
      <div class="cm-head__actions">
        <button type="button" class="btn" @click="goMy">我的帖子</button>
        <button v-if="isAdmin" type="button" class="btn btn--ghost" @click="goModeration">内容审核</button>
        <button type="button" class="btn btn--primary" @click="goCreate">＋ 发帖</button>
      </div>
    </div>

    <!-- 类型 Tab -->
    <div class="cm-tabs">
      <button
        v-for="t in typeTabs"
        :key="t.key"
        type="button"
        :class="['cm-tab', { 'cm-tab--active': currentType === t.key }]"
        @click="currentType = t.key"
      >
        {{ t.label }}
      </button>
    </div>

    <!-- 排序 + 城市 -->
    <div class="cm-filter-row">
      <div class="cm-sorts">
        <button
          v-for="s in sortTabs"
          :key="s.key"
          type="button"
          :class="['cm-sort', { 'cm-sort--active': currentSort === s.key }]"
          @click="currentSort = s.key as PostSort"
        >
          {{ s.label }}
        </button>
      </div>
      <select v-model="currentCity" class="cm-city-select" aria-label="选择城市">
        <option value="">全部城市</option>
        <option v-for="c in POPULAR_CITIES" :key="c" :value="c">{{ c }}</option>
      </select>
    </div>

    <div v-if="loading" class="cm-grid">
      <div v-for="i in skeletons(6)" :key="i" class="skel" />
    </div>

    <div v-else-if="error" class="cm-empty">{{ error }}</div>

    <div v-else-if="items.length === 0" class="cm-empty">
      <p style="margin: 0 0 12px;">还没有符合条件的帖子</p>
      <p class="cm-empty__hint">成为第一个分享的人吧 —— 发一篇你的旅行经验</p>
      <button type="button" class="btn btn--primary" @click="goCreate">＋ 发帖</button>
    </div>

    <template v-else>
      <div class="cm-grid">
        <PostCard v-for="p in items" :key="p.id" :post="p" @changed="changeTab" />
      </div>
      <div v-if="items.length < total" class="cm-more">
        <button type="button" class="btn btn--block" :disabled="loadingMore" @click="loadMore">
          {{ loadingMore ? "加载中..." : "加载更多" }}
        </button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.cm-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.cm-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.cm-head__title {
  margin: 0;
  font-size: 22px;
  color: #1c1c1e;
}
.cm-head__desc {
  margin: 4px 0 0;
  font-size: 13px;
  color: #8e8e93;
}
.cm-head__actions {
  display: flex;
  gap: 8px;
}
.btn {
  border: none;
  border-radius: 10px;
  padding: 7px 14px;
  font-size: 13px;
  font-weight: 500;
  background: rgba(0, 0, 0, 0.05);
  color: #3c3c43;
  cursor: pointer;
}
.btn--primary {
  background: #3478f6;
  color: #fff;
}
.btn--ghost {
  background: transparent;
  border: 1px solid rgba(0, 0, 0, 0.12);
}
.btn--block {
  width: 100%;
  padding: 10px;
}
.btn:disabled {
  opacity: 0.6;
}
.cm-tabs {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.cm-tab {
  border: none;
  border-radius: 999px;
  padding: 6px 14px;
  font-size: 13px;
  background: rgba(0, 0, 0, 0.04);
  color: #6b7280;
  cursor: pointer;
}
.cm-tab--active {
  background: #1c1c1e;
  color: #fff;
}
.cm-filter-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.cm-sorts {
  display: inline-flex;
  gap: 2px;
  padding: 3px;
  border-radius: 10px;
  background: rgba(0, 0, 0, 0.04);
}
.cm-sort {
  border: none;
  border-radius: 8px;
  padding: 5px 14px;
  font-size: 13px;
  background: transparent;
  color: #8e8e93;
  cursor: pointer;
}
.cm-sort--active {
  background: #ffffff;
  color: #1c1c1e;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}
.cm-city-select {
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 10px;
  padding: 6px 10px;
  font-size: 13px;
  background: #fff;
  color: #1c1c1e;
}
.cm-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 14px;
}
.skel {
  height: 220px;
  border-radius: 14px;
  background: linear-gradient(90deg, #eef0f4 25%, #f7f8fa 50%, #eef0f4 75%);
  background-size: 200% 100%;
  animation: shimmer 1.2s infinite;
}
@keyframes shimmer {
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}
.cm-empty {
  text-align: center;
  padding: 48px 16px;
  color: #8e8e93;
  font-size: 14px;
  background: #fff;
  border-radius: 14px;
}
.cm-empty__hint {
  margin: 0 0 14px;
  font-size: 13px;
}
.cm-more {
  padding: 4px 0 8px;
}
</style>
