<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";

import SpotCard from "../components/SpotCard.vue";
import { POPULAR_CITIES } from "../constants/cities";
import { getRecommendations, newFeedTrace } from "../services/api";
import type { RecommendationItem } from "../types";

/**
 * 发现页（产品化阶段一 /recommendations，PLAN §5/§11）。
 *
 * 城市选择器（高德按需同步首次会稍慢，后端在该城市 spot 不足 20 时自动补数据）
 * + 排序 Tab：为你推荐（画像个性化，默认）/ 热门 / 最新。
 * 卡片收藏/不感兴趣后自动刷新第一页 —— 画像版本变化 → 排序变化（闭环可演示）。
 */
const route = useRoute();

type SortKey = "personalized" | "popular" | "latest";

const SORT_TABS: Array<{ key: SortKey; label: string }> = [
  { key: "personalized", label: "为你推荐" },
  // popular 排序第一版=攻略质量优先（GUIDE_MATCHED/VERIFIED 在前），
  // 尚无真实浏览量/收藏量埋点，不叫"热门"以免冒充热度榜（PLAN §18.1）
  { key: "popular", label: "攻略优先" },
  // P1-3（排查报告）：DB 只有 updated_at（7 天重同步会刷新），"最新"名不副实 → "最近更新"
  { key: "latest", label: "最近更新" },
];

const city = ref("");
const sort = ref<SortKey>("personalized");

const items = ref<RecommendationItem[]>([]);
const total = ref(0);
const page = ref(1);
const PAGE_SIZE = 12;
const loading = ref(false);
const loadingMore = ref(false);
const error = ref("");
const personalized = ref(false);
// P1-5：当前推荐上下文（城市/排序变化会重建）的曝光幂等键，防重复请求双写曝光
let feedTrace = newFeedTrace();

/* 城市选择：query 预填 > 上次选择（组件存留）> 默认列表第一城。
   P1-4（排查报告）：不再限定 POPULAR_CITIES —— query 里的任意非空城市都接受，
   交由后端做城市校验/按需同步（spot 缓存不足会自动调高德补数据）；不在列表时把它插到最前展示。 */
const displayCities = computed(() =>
  city.value && city.value !== POPULAR_CITIES[0] && !POPULAR_CITIES.includes(city.value)
    ? [city.value, ...POPULAR_CITIES]
    : POPULAR_CITIES
);

async function loadFirst() {
  page.value = 1;
  feedTrace = newFeedTrace(); // 首屏/刷新/换城市/换排序 → 新内容上下文，曝光照常记录
  await fetchPage(true);
}

async function loadMore() {
  if (loading.value || loadingMore.value) return;
  if (items.value.length >= total.value) return;
  await fetchPage(false);
}

async function fetchPage(first: boolean) {
  if (first) {
    loading.value = true;
  } else {
    loadingMore.value = true;
  }
  error.value = "";
  try {
    const feed = await getRecommendations(city.value, first ? 1 : page.value + 1, PAGE_SIZE, sort.value, feedTrace);
    page.value = first ? 1 : page.value + 1;
    total.value = feed.total ?? 0;
    personalized.value = !!feed.personalized;
    items.value = first ? feed.items : [...items.value, ...feed.items];
  } catch (err: unknown) {
    // 非法城市由后端白名单返回 400 + 可读文案（含形近纠错建议）：直接透出，
    // 并清空列表 —— 绝不能把上一个城市的景点留在页面上冒充"该城市的结果"。
    const resp = (err as { response?: { status?: number; data?: { message?: string } } })?.response;
    if (resp?.status === 400) {
      error.value = resp.data?.message || "城市名不合法，请检查后重试。";
      items.value = [];
      total.value = 0;
    } else {
      error.value = "推荐加载失败，请稍后重试。";
      if (!first) items.value = items.value; // 保持已有内容
    }
  } finally {
    loading.value = false;
    loadingMore.value = false;
  }
}

function switchCity(c: string) {
  if (c === city.value) return;
  city.value = c;
  void loadFirst();
}

function switchSort(k: SortKey) {
  if (k === sort.value) return;
  sort.value = k;
  void loadFirst();
}

/* 卡片反馈后：重拉第一页（推荐排序按最新画像变化，可见反馈闭环）。
   P1-5（排查报告）：非第一页时不整表重载打断浏览位置 —— 卡片已就地更新收藏/忽略态，
   排序变化留到用户手动刷新/回到首页时生效。 */
function onChanged() {
  if (page.value === 1) {
    void loadFirst();
  }
}

const hasMore = () => items.value.length < total.value;

onMounted(() => {
  const q = route.query.city;
  if (q && typeof q === "string" && q.trim()) {
    city.value = q.trim();
  } else {
    city.value = POPULAR_CITIES[0];
  }
  void loadFirst();
});

/* 切到本页时若 URL 城市变了（从首页/城市专题"发现更多"进入），重新对齐 */
watch(
  () => route.query.city,
  (q) => {
    if (q && typeof q === "string" && q.trim() && q.trim() !== city.value) {
      switchCity(q.trim());
    }
  }
);

function skeletons(n: number) {
  return new Array(n).fill(0).map((_, i) => i);
}
</script>

<template>
  <section class="discover-page">
    <div class="discover-head">
      <div>
        <h2 class="discover-head__title">🗺️ 发现景点</h2>
        <p class="discover-head__desc">按你的偏好推荐，收藏 / 不感兴趣都会影响下次排序</p>
      </div>
      <button type="button" class="discover-head__refresh" :disabled="loading" @click="loadFirst">
        ⟳ 刷新
      </button>
    </div>

    <!-- 城市选择器（按需高德同步；query 带入的任意城市也会出现在最前面） -->
    <div class="city-bar">
      <button
        v-for="c in displayCities"
        :key="c"
        type="button"
        :class="['city-chip', { 'city-chip--active': c === city }]"
        @click="switchCity(c)"
      >
        {{ c }}
      </button>
    </div>

    <!-- 排序 Tab -->
    <div class="sort-tabs">
      <button
        v-for="t in SORT_TABS"
        :key="t.key"
        type="button"
        :class="['sort-tab', { 'sort-tab--active': sort === t.key }]"
        @click="switchSort(t.key)"
      >
        {{ t.label }}
      </button>
      <span v-if="personalized" class="sort-tabs__tip">已按你的画像排序</span>
    </div>

    <div v-if="loading" class="spot-grid">
      <div v-for="i in skeletons(12)" :key="i" class="skel" />
    </div>

    <div v-else-if="error" class="state-box">{{ error }}</div>

    <div v-else-if="items.length === 0" class="state-box">
      「{{ city }}」暂时没有可推荐的景点 —— 首次访问可能正在从地图同步数据，
      <button type="button" class="state-box__link" @click="loadFirst">再试一次</button>
    </div>

    <template v-else>
      <div class="spot-grid">
        <SpotCard
          v-for="item in items"
          :key="item.spot_id"
          :item="item"
          :reload-on-change="true"
          @changed="onChanged"
        />
      </div>

      <div class="load-more">
        <button
          v-if="hasMore()"
          type="button"
          class="load-more__btn"
          :disabled="loadingMore"
          @click="loadMore"
        >
          {{ loadingMore ? "加载中..." : `加载更多（${items.length}/${total}）` }}
        </button>
        <span v-else class="load-more__end">— 已经到底啦 —</span>
      </div>
    </template>
  </section>
</template>

<style scoped>
.discover-page {
  display: grid;
  gap: 14px;
}

.discover-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 4px 2px 0;
}

.discover-head__title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
}

.discover-head__desc {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--text-muted);
}

.discover-head__refresh {
  border: none;
  border-radius: 10px;
  padding: 7px 14px;
  background: var(--surface-white);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  color: var(--brand-teal);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}

.discover-head__refresh:disabled {
  opacity: 0.6;
}

.city-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.city-chip {
  border: 1px solid var(--border-soft);
  border-radius: 16px;
  padding: 6px 14px;
  background: var(--surface-white);
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.city-chip--active {
  background: var(--brand-teal);
  border-color: var(--brand-teal);
  color: var(--surface-white);
}

.sort-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 2px 0;
  padding: 3px;
  border-radius: 10px;
  background: rgba(0, 0, 0, 0.04);
  width: fit-content;
}

.sort-tab {
  border: none;
  border-radius: 8px;
  padding: 6px 16px;
  background: transparent;
  color: var(--text-muted);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}

.sort-tab--active {
  background: var(--surface-white);
  color: var(--text-primary);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.sort-tabs__tip {
  margin-left: 8px;
  font-size: 12px;
  color: var(--success);
}

.spot-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: 14px;
}

.skel {
  height: 300px;
  border-radius: 14px;
  background: linear-gradient(100deg, rgba(23, 33, 31, 0.04) 40%, rgba(23, 33, 31, 0.02) 50%, rgba(23, 33, 31, 0.04) 60%);
  background-size: 200% 100%;
  animation: shimmer 1.2s infinite;
}

@keyframes shimmer {
  from { background-position: 120% 0; }
  to { background-position: -80% 0; }
}

.state-box {
  padding: 48px 20px;
  text-align: center;
  border-radius: 14px;
  background: var(--surface-white);
  color: var(--text-muted);
  font-size: 14px;
  line-height: 1.8;
}

.state-box__link,
.load-more__end {
  border: none;
  background: none;
  color: var(--brand-teal);
  cursor: pointer;
  font-size: 14px;
}

.load-more {
  text-align: center;
  padding: 6px 0 2px;
}

.load-more__btn {
  border: 1px solid var(--border-soft);
  border-radius: 10px;
  padding: 9px 24px;
  background: var(--surface-white);
  color: var(--brand-teal);
  font-size: 14px;
  cursor: pointer;
}

.load-more__btn:disabled {
  opacity: 0.6;
}

.load-more__end {
  color: rgba(23, 33, 31, 0.18);
  font-size: 13px;
}
</style>
