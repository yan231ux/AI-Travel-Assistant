<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";

import PostCard from "../components/PostCard.vue";
import SpotCard from "../components/SpotCard.vue";
import { DASHBOARD_CITY_SHORTCUTS } from "../constants/cities";
import { getPosts, getProfileSummary, getRecommendations, newFeedTrace } from "../services/api";
import { displayName } from "../stores/session";
import type { PostItem, ProfileSummary, RecommendationFeed } from "../types";

/**
 * 首页 Dashboard（产品化阶段一，PRODUCT_EVOLUTION_PLAN §4；阶段三加社区攻略位）。
 *
 * 职责：让个性化能力被用户看见 —— 画像摘要卡（实时聚合 summary，Q5 修复数据源）、
 * 三个主入口（生成我的行程/发现景点 + 历史/收藏）、按城市预览的"为你推荐"、
 * 社区攻略"为你推荐"（阶段三：GET /community/posts?sort=recommended，无画像自动降级热门）。
 * 推荐卡收藏/不感兴趣后自动刷新对应流，演示"反馈 → 画像版本变化 → 排序变化"闭环。
 */
const router = useRouter();

/* ---------- 画像摘要（GET /user/profile/summary：实时聚合 trip_record，不用过期快照） ---------- */
const summary = ref<ProfileSummary | null>(null);
const summaryLoaded = ref(false);

const greeting = computed(() => {
  const nick = summary.value?.nickname || displayName.value || "旅行者";
  const hour = new Date().getHours();
  const period = hour < 6 ? "夜深了" : hour < 12 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
  return `${period}，${nick}`;
});

function splitCsv(s?: string | null): string[] {
  if (!s) return [];
  return s.split(",").map((x) => x.trim()).filter(Boolean);
}

const summaryChips = computed(() => {
  const s = summary.value;
  if (!s) return [];
  const out: string[] = [];
  const styles = splitCsv(s.travel_styles);
  if (styles.length) out.push(`风格 ${styles.slice(0, 2).join("/")}`);
  if (s.pace_preference) out.push(`节奏 ${s.pace_preference}`);
  const foods = splitCsv(s.food_preferences);
  if (foods.length) out.push(`爱 ${foods.slice(0, 2).join("/")}`);
  if (s.hotel_preference) out.push(`住 ${s.hotel_preference}`);
  return out.slice(0, 3);
});

const hasNoProfile = computed(() =>
  summary.value !== null
  && summaryChips.value.length === 0
  && (summary.value.trip_count || 0) === 0
);

const recentCities = computed(() => (summary.value?.visited_cities || []).slice(0, 6));

async function loadSummary() {
  try {
    summary.value = await getProfileSummary();
  } catch (e) {
    console.error(e);
  } finally {
    summaryLoaded.value = true;
  }
}

/* ---------- 为你推荐（城市快捷切换 + 个性化/热门排序流） ---------- */
const previewCity = ref("");
const feed = ref<RecommendationFeed | null>(null);
const feedLoading = ref(false);
const feedError = ref("");

const recommendedCities = computed(() => {
  const set = new Set(DASHBOARD_CITY_SHORTCUTS);
  for (const c of recentCities.value) {
    if (!set.has(c)) set.add(c);
  }
  return Array.from(set).slice(0, 10);
});

async function loadFeed() {
  if (!previewCity.value) return;
  feedLoading.value = true;
  feedError.value = "";
  try {
    feed.value = await getRecommendations(previewCity.value, 1, 8, "personalized");
  } catch {
    feedError.value = "推荐加载失败，请稍后重试。";
  } finally {
    feedLoading.value = false;
  }
}

function switchCity(city: string) {
  if (city === previewCity.value) return;
  previewCity.value = city;
  void loadFeed();
}

/* 卡片反馈（收藏/不感兴趣）后：画像/回避信号已变 → 刷新当前城市推荐（闭环可见） */
function onFeedChanged() {
  void loadFeed();
}

/* ---------- 社区攻略 · 为你推荐（阶段三：个性化帖子预览，无画像降级热门） ---------- */
const posts = ref<PostItem[]>([]);
const postsLoading = ref(true);
const postsError = ref("");
// P1-5：帖子推荐预览同样带上会话幂等键（页面存续期共享，防双写曝光）
const postFeedTrace = newFeedTrace();

async function loadPosts() {
  postsLoading.value = true;
  postsError.value = "";
  try {
    const resp = await getPosts({ sort: "recommended", pageSize: 4, feedTrace: postFeedTrace });
    posts.value = resp.items.slice(0, 4);
  } catch {
    postsError.value = "攻略加载失败，请稍后重试。";
  } finally {
    postsLoading.value = false;
  }
}

function go(name: string, query?: Record<string, string>) {
  void router.push(query ? { name, query } : { name });
}

function goCityTopic(c: string) {
  void router.push({ name: "city-topic", params: { name: c } });
}

onMounted(() => {
  void loadSummary().then(() => {
    // 默认城市：最近去过城市优先，否则热门第一城
    const first = summary.value?.visited_cities?.[0];
    previewCity.value = first && first.trim() ? first.trim() : DASHBOARD_CITY_SHORTCUTS[0];
    void loadFeed();
  });
  void loadPosts();
});

/* ---------- 骨架占位 ---------- */
function skeletons(n: number) {
  return new Array(n).fill(0).map((_, i) => i);
}
</script>

<template>
  <section class="dashboard">
    <!-- 欢迎 + 画像摘要卡（点击去偏好页完善） -->
    <div
      class="ios-card dash-profile"
      role="button"
      tabindex="0"
      @click="go('profile')"
      @keyup.enter="go('profile')"
    >
      <div class="dash-profile__head">
        <div>
          <h2 class="dash-profile__greet">{{ greeting }}</h2>
          <p class="dash-profile__sub" v-if="summaryLoaded">
            <template v-if="hasNoProfile">我还不了解你的偏好 —— 点这里告诉我，推荐会更懂你</template>
            <template v-else>系统记得你的偏好，行程与推荐会自动贴合</template>
          </p>
        </div>
        <span class="dash-profile__more">完善偏好 ›</span>
      </div>

      <div v-if="summaryChips.length" class="ios-chips dash-profile__chips">
        <span v-for="chip in summaryChips" :key="chip" class="ios-chip ios-chip--mem">{{ chip }}</span>
      </div>

      <div v-if="summary" class="dash-profile__stats">
        <div class="stat">
          <div class="stat__value">{{ summary.trip_count }}</div>
          <div class="stat__label">历史行程</div>
        </div>
        <div class="stat">
          <div class="stat__value">{{ summary.visited_cities?.length ?? 0 }}</div>
          <div class="stat__label">去过的城市</div>
        </div>
        <div class="stat stat--cities">
          <div class="stat__value stat__value--sm">{{ (recentCities || []).join(" · ") || "—" }}</div>
          <div class="stat__label">最近去过</div>
        </div>
      </div>
    </div>

    <!-- 快捷入口 -->
    <div class="dash-cta">
      <button type="button" class="cta cta--primary" @click="go('plan')">
        <span class="cta__icon">✈️</span>
        <span class="cta__title">生成我的行程</span>
        <span class="cta__desc">AI 实时规划 · 结合你的偏好</span>
      </button>
      <button type="button" class="cta" @click="go('recommendations')">
        <span class="cta__icon">🗺️</span>
        <span class="cta__title">发现景点</span>
        <span class="cta__desc">个性化推荐 + 城市攻略</span>
      </button>
      <button type="button" class="cta" @click="go('history')">
        <span class="cta__icon">🧳</span>
        <span class="cta__title">历史行程</span>
        <span class="cta__desc">回看已保存的计划</span>
      </button>
      <button type="button" class="cta" @click="go('favorites')">
        <span class="cta__icon">💛</span>
        <span class="cta__title">我的收藏</span>
        <span class="cta__desc">收藏过的景点</span>
      </button>
    </div>

    <!-- 为你推荐 -->
    <div class="rec-block">
      <div class="rec-block__head">
        <h3 class="rec-block__title">{{ previewCity ? `「${previewCity}」为你推荐` : "为你推荐" }}</h3>
        <div class="rec-block__ops">
          <button
            v-if="previewCity"
            type="button"
            class="rec-block__more"
            @click="goCityTopic(previewCity)"
          >
            城市专题 ›
          </button>
          <button
            v-if="previewCity"
            type="button"
            class="rec-block__more"
            @click="go('recommendations', { city: previewCity })"
          >
            去发现更多 ›
          </button>
        </div>
      </div>

      <div class="rec-block__cities">
        <button
          v-for="c in recommendedCities"
          :key="c"
          type="button"
          :class="['city-chip', { 'city-chip--active': c === previewCity }]"
          @click="switchCity(c)"
        >
          {{ c }}
        </button>
      </div>

      <div v-if="feedLoading" class="spot-grid">
        <div v-for="i in skeletons(4)" :key="i" class="skel" />
      </div>
      <div v-else-if="feedError" class="rec-empty">{{ feedError }}</div>
      <div v-else-if="feed && feed.items.length" class="spot-grid">
        <SpotCard
          v-for="item in feed.items"
          :key="item.spot_id"
          :item="item"
          :reload-on-change="true"
          @changed="onFeedChanged"
        />
      </div>
      <div v-else class="rec-empty">
        该城市暂时没有可推荐的景点，<button type="button" class="rec-empty__link" @click="loadFeed">再试一次</button>
      </div>
    </div>

    <!-- 社区攻略 · 为你推荐（阶段三：帖子行为闭环与社区入口，画像为空自动降级热门） -->
    <div class="rec-block">
      <div class="rec-block__head">
        <h3 class="rec-block__title">📖 社区攻略 · 为你推荐</h3>
        <button type="button" class="rec-block__more" @click="go('community')">进入社区 ›</button>
      </div>

      <div v-if="postsLoading" class="post-grid">
        <div v-for="i in skeletons(4)" :key="i" class="skel" />
      </div>
      <div v-else-if="postsError" class="rec-empty">{{ postsError }}</div>
      <div v-else-if="posts.length" class="post-grid">
        <PostCard
          v-for="p in posts"
          :key="p.id"
          :post="p"
          @changed="loadPosts"
        />
      </div>
      <div v-else class="rec-empty">
        还没有公开攻略 —— 先去发一篇你的旅行经验吧。
        <button type="button" class="rec-empty__link" @click="go('community')">去看看 ›</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.dashboard {
  display: grid;
  gap: 16px;
}

.ios-card {
  border-radius: 14px;
  background: #FFFFFF;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

/* 画像摘要卡 */
.dash-profile {
  padding: 20px;
  cursor: pointer;
  border: 0.5px solid rgba(0, 122, 255, 0.16);
  transition: box-shadow 0.2s ease;
}

.dash-profile:hover {
  box-shadow: 0 2px 10px rgba(0, 122, 255, 0.12);
}

.dash-profile__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.dash-profile__greet {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: #1C1C1E;
}

.dash-profile__sub {
  margin: 6px 0 0;
  font-size: 12.5px;
  color: #8E8E93;
}

.dash-profile__more {
  flex-shrink: 0;
  font-size: 12px;
  color: #007AFF;
}

.dash-profile__chips {
  margin-top: 14px;
}

.ios-chip {
  border: 1px solid #D1D1D6;
  border-radius: 20px;
  padding: 6px 14px;
  background: #FFFFFF;
  font-size: 13px;
  color: #1C1C1E;
}

.ios-chip--mem {
  background: rgba(0, 122, 255, 0.06);
  border-color: rgba(0, 122, 255, 0.18);
  color: #185FA5;
}

.dash-profile__stats {
  display: flex;
  gap: 28px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 0.5px solid rgba(0, 0, 0, 0.06);
}

.stat__value {
  font-size: 18px;
  font-weight: 700;
  color: #1C1C1E;
}

.stat__value--sm {
  font-size: 13px;
  font-weight: 600;
  line-height: 1.4;
  max-width: 420px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stat__label {
  margin-top: 2px;
  font-size: 11.5px;
  color: #8E8E93;
}

.stat--cities {
  flex: 1;
  min-width: 0;
}

/* 快捷入口 */
.dash-cta {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

.cta {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  padding: 16px;
  border: none;
  border-radius: 14px;
  background: #FFFFFF;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  text-align: left;
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.2s ease;
}

.cta:hover {
  box-shadow: 0 3px 12px rgba(0, 0, 0, 0.12);
}

.cta:active {
  transform: scale(0.98);
}

.cta--primary {
  background: linear-gradient(135deg, #007AFF, #4DA3FF);
  color: #FFFFFF;
}

.cta__icon {
  font-size: 18px;
}

.cta__title {
  font-size: 15px;
  font-weight: 600;
}

.cta__desc {
  font-size: 11.5px;
  opacity: 0.75;
}

/* 为你推荐 */
.rec-block__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 4px 2px 10px;
}

.rec-block__ops {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.rec-block__title {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  color: #1C1C1E;
}

.rec-block__more {
  border: none;
  background: none;
  color: #007AFF;
  font-size: 13px;
  cursor: pointer;
}

.rec-block__cities {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}

.city-chip {
  border: 1px solid #E2E2E7;
  border-radius: 16px;
  padding: 5px 13px;
  background: #FFFFFF;
  color: #3C3C43;
  font-size: 12.5px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.city-chip--active {
  background: #007AFF;
  border-color: #007AFF;
  color: #FFFFFF;
}

.spot-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 14px;
}

.post-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 14px;
}

.skel {
  height: 280px;
  border-radius: 14px;
  background: linear-gradient(100deg, #EFEFF4 40%, #F8F8FA 50%, #EFEFF4 60%);
  background-size: 200% 100%;
  animation: shimmer 1.2s infinite;
}

@keyframes shimmer {
  from { background-position: 120% 0; }
  to { background-position: -80% 0; }
}

.rec-empty {
  padding: 40px 20px;
  text-align: center;
  border-radius: 14px;
  background: #FFFFFF;
  color: #8E8E93;
  font-size: 14px;
}

.rec-empty__link {
  border: none;
  background: none;
  color: #007AFF;
  cursor: pointer;
  font-size: 14px;
}

@media (max-width: 900px) {
  .dash-cta {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
