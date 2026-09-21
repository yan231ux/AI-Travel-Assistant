<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";

import PostCard from "../components/PostCard.vue";
import SpotCard from "../components/SpotCard.vue";
import TrendingSpotList from "../components/TrendingSpotList.vue";
import { DASHBOARD_CITY_SHORTCUTS } from "../constants/cities";
import {
  getHomeTrendingSpots,
  getPosts,
  getProfileSummary,
  getRecommendations,
  newFeedTrace,
} from "../services/api";
import { displayName } from "../stores/session";
import type {
  PostItem,
  ProfileSummary,
  RecommendationFeed,
  TrendingSpotFeed,
} from "../types";

/**
 * 首页 Dashboard（UI 视觉升级方案 §6.2/6.3：首屏主任务 + 信息分层）。
 *
 * 职责：让个性化能力被用户看见 ——
 * 首屏 Hero（问候 + 画像胶囊 + 主任务"开始规划"）、四个入口、
 * 按城市预览的"为你推荐"、社会热度"大家最近在规划"、社区攻略预览。
 * 推荐卡收藏/不感兴趣后自动刷新对应流，演示"反馈 → 画像版本变化 → 排序变化"闭环。
 *
 * ⚠️ 分区纪律（数据运营方案 §6.1）：首页必须区分四种内容来源 —— 热门规划、个性化推荐、
 * 城市精选、攻略收录。本页把"大家最近在规划"（全站社会热度）与"为你推荐"（画像驱动）
 * 放在两个独立区块，且热门块只展示热度，**不显示匹配度**，避免把全站热门伪装成"适合你"。
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
/**
 * §6.3 运营干预对用户可见（缺口修复）：后端一直下发 interventions/featured_city，
 * 但用户端从未消费 → 运营置顶后用户只看到"它莫名排最前"。
 * 只消费 PIN（置顶）与城市精选；DEMOTE 是压制动作，不外显。
 */
const pinMeta = ref<Record<string, string>>({});
const featuredCity = ref(false);
const featuredReason = ref("");

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
    // 运营元信息（与算法分分离）：只把 PIN 转成对用户可见的"运营精选"标记
    const next: Record<string, string> = {};
    for (const m of feed.value.interventions || []) {
      if (m && m.action === "PIN" && m.spot_id) next[m.spot_id] = m.reason || "";
    }
    pinMeta.value = next;
    featuredCity.value = feed.value.featured_city === true;
    featuredReason.value = feed.value.featured_city ? feed.value.featured_reason || "" : "";
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

/* ---------- 大家最近在规划（全站社会热度，§6.1 与"为你推荐"分区） ---------- */
const trending = ref<TrendingSpotFeed | null>(null);
const trendingLoading = ref(true);
const trendingError = ref("");

// 固定全站口径（不传 city）：本模块表达的是"大家都在关注什么"，
// 若跟随城市切换就与上面的"为你推荐"城市流语义重叠、又容易看成个性化，故不做城市筛选。
async function loadTrending() {
  trendingLoading.value = true;
  trendingError.value = "";
  try {
    trending.value = await getHomeTrendingSpots(null, 7, 6);
  } catch {
    trendingError.value = "热度数据加载失败，请稍后重试。";
  } finally {
    trendingLoading.value = false;
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
  void loadTrending();
});

/* ---------- 骨架占位 ---------- */
function skeletons(n: number) {
  return new Array(n).fill(0).map((_, i) => i);
}
</script>

<template>
  <section class="dashboard">
    <!-- ===== 首屏 Hero：主任务「开始规划」 + 画像胶囊（方案 §6.2） ===== -->
    <div class="hero">
      <div class="hero__main">
        <p class="hero__eyebrow">AI 行程规划 · 个性化推荐</p>
        <h2 class="hero__greet font-serif">{{ greeting }}</h2>
        <p class="hero__sub">
          <template v-if="summaryLoaded && hasNoProfile">先告诉我你的偏好，推荐会更懂你</template>
          <template v-else>准备好去哪里了吗？告诉 AI，剩下的交给它。</template>
        </p>

        <div class="hero__actions">
          <button type="button" class="hero__cta" @click="go('plan')">开始规划一次旅行</button>
          <button
            type="button"
            class="hero__secondary"
            role="button"
            tabindex="0"
            @click="go('profile')"
          >
            完善偏好 ›
          </button>
        </div>

        <div v-if="summaryChips.length" class="hero__chips">
          <span v-for="chip in summaryChips" :key="chip" class="hero__chip">✓ {{ chip }}</span>
        </div>
      </div>

      <!-- 个人状态（三级信息：历史/城市沉淀，点击去我的） -->
      <div v-if="summary" class="hero__stats" role="button" tabindex="0" @click="go('profile')">
        <div class="stat">
          <div class="stat__value font-num">{{ summary.trip_count }}</div>
          <div class="stat__label">历史行程</div>
        </div>
        <div class="stat__sep" />
        <div class="stat">
          <div class="stat__value font-num">{{ summary.visited_cities?.length ?? 0 }}</div>
          <div class="stat__label">去过的城市</div>
        </div>
        <div class="stat stat--cities">
          <div class="stat__value stat__value--sm">{{ (recentCities || []).join(" · ") || "—" }}</div>
          <div class="stat__label">最近去过</div>
        </div>
      </div>
    </div>

    <!-- ===== 四个入口（一级任务延伸） ===== -->
    <div class="dash-cta">
      <button type="button" class="cta" @click="go('plan')">
        <span class="cta__title">生成我的行程</span>
        <span class="cta__desc">AI 实时规划 · 结合你的偏好</span>
      </button>
      <button type="button" class="cta" @click="go('recommendations')">
        <span class="cta__title">发现景点</span>
        <span class="cta__desc">个性化推荐 + 城市攻略</span>
      </button>
      <button type="button" class="cta" @click="go('history')">
        <span class="cta__title">历史行程</span>
        <span class="cta__desc">回看已保存的计划</span>
      </button>
      <button type="button" class="cta" @click="go('favorites')">
        <span class="cta__title">我的收藏</span>
        <span class="cta__desc">收藏过的景点</span>
      </button>
    </div>

    <!-- ===== 为你推荐（城市切换 + 个性化排序流） ===== -->
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

      <!-- 城市精选（§6.3 运营标记对用户可见）：把"城市精选"与"为你推荐"明确区分开 -->
      <p v-if="featuredCity" class="rec-featured">
        ⭐ 城市精选 · {{ previewCity }}<span v-if="featuredReason">：{{ featuredReason }}</span>
      </p>

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
          :operation-tag="pinMeta[item.spot_id] !== undefined ? '运营精选' : undefined"
          :operation-reason="pinMeta[item.spot_id] || null"
          @changed="onFeedChanged"
        />
      </div>
      <div v-else class="rec-empty">
        该城市暂时没有可推荐的景点，<button type="button" class="rec-empty__link" @click="loadFeed">再试一次</button>
      </div>
    </div>

    <!-- ===== 大家最近在规划（§6.1：全站社会热度，独立分区；只给热度不给匹配度） ===== -->
    <div class="rec-block">
      <div class="rec-block__head">
        <div class="rec-block__titles">
          <h3 class="rec-block__title">大家最近在规划</h3>
          <span class="rec-block__badge">社会热度</span>
        </div>
        <button type="button" class="rec-block__more" @click="go('recommendations')">
          去发现更多 ›
        </button>
      </div>
      <p class="rec-block__note">
        全站近 {{ trending?.window_days ?? 7 }} 天的规划热度 —— 只看大家都在关注什么，与你的偏好无关。
      </p>

      <div v-if="trendingLoading" class="trend-skel">
        <div v-for="i in skeletons(4)" :key="i" class="skel skel--row" />
      </div>
      <div v-else-if="trendingError" class="rec-empty">
        {{ trendingError }}
        <button type="button" class="rec-empty__link" @click="loadTrending">再试一次</button>
      </div>
      <div v-else-if="trending && trending.degraded" class="rec-empty">
        热度数据暂时不可用（统计查询异常），不影响上方的个性化推荐。
        <button type="button" class="rec-empty__link" @click="loadTrending">再试一次</button>
      </div>
      <template v-else-if="trending && trending.items.length">
        <TrendingSpotList
          :items="trending.items"
          :window-days="trending.window_days"
          @changed="onFeedChanged"
        />
        <p class="trend-foot">
          热度 = 规划人数 40% + 保存行程 25% + 收藏 15% + 详情点击 10% − 不感兴趣 10%，并按天衰减。
        </p>
      </template>
      <div v-else class="rec-empty">
        最近还没有足够的规划数据 —— 你先去规划一次，这里就有内容了。
        <button type="button" class="rec-empty__link" @click="go('plan')">去规划 ›</button>
      </div>
    </div>

    <!-- ===== 社区攻略 · 为你推荐 ===== -->
    <div class="rec-block">
      <div class="rec-block__head">
        <h3 class="rec-block__title">社区攻略 · 为你推荐</h3>
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
  gap: 18px;
}

/* ===== Hero（局部深色智能模块：明亮内容区 + 局部深色，方案三节） ===== */
.hero {
  position: relative;
  overflow: hidden;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 24px;
  align-items: end;
  padding: 30px 32px 26px;
  border-radius: var(--radius-lg);
  background: var(--brand-deep);
  color: #f7f5ef;
}

/* 极简装饰：低透明度同心圆 + 珊瑚小点，呼应"地图/日出" */
.hero::before {
  content: "";
  position: absolute;
  right: -90px;
  top: -130px;
  width: 340px;
  height: 340px;
  border-radius: 50%;
  background:
    radial-gradient(circle, transparent 0 54%, rgba(247, 245, 239, 0.06) 55% 56%, transparent 57%),
    radial-gradient(circle, transparent 0 74%, rgba(247, 245, 239, 0.05) 75% 76%, transparent 77%);
  pointer-events: none;
}

.hero__main {
  position: relative;
  min-width: 0;
}

.hero__eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  letter-spacing: 0.16em;
  color: var(--brand-sun);
}

.hero__greet {
  margin: 0;
  font-size: 30px;
  font-weight: 700;
  line-height: 1.3;
  color: #f7f5ef;
}

.hero__sub {
  margin: 8px 0 0;
  font-size: 14px;
  color: rgba(247, 245, 239, 0.72);
}

.hero__actions {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 20px;
  flex-wrap: wrap;
}

.hero__cta {
  border: none;
  border-radius: 999px;
  padding: 11px 26px;
  background: var(--brand-coral);
  color: #fff;
  font-size: 15px;
  font-weight: 650;
  cursor: pointer;
  box-shadow: 0 6px 18px rgba(217, 119, 93, 0.35);
  transition: transform 0.15s var(--ease), box-shadow 0.2s var(--ease);
}
.hero__cta:hover {
  box-shadow: 0 8px 22px rgba(217, 119, 93, 0.45);
  transform: translateY(-1px);
}
.hero__cta:active {
  transform: scale(0.98);
}

.hero__secondary {
  border: none;
  background: none;
  padding: 8px 2px;
  font-size: 13.5px;
  color: rgba(247, 245, 239, 0.8);
  cursor: pointer;
  border-bottom: 1px solid rgba(247, 245, 239, 0.35);
  transition: color 0.2s var(--ease);
}
.hero__secondary:hover {
  color: #fff;
  border-color: var(--brand-coral);
}

.hero__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 18px;
}

.hero__chip {
  padding: 4px 12px;
  border-radius: 999px;
  background: rgba(247, 245, 239, 0.12);
  border: 1px solid rgba(247, 245, 239, 0.2);
  color: rgba(247, 245, 239, 0.92);
  font-size: 12px;
}

/* 个人状态（Hero 右下） */
.hero__stats {
  position: relative;
  display: flex;
  align-items: flex-end;
  gap: 18px;
  padding: 14px 18px;
  border-radius: var(--radius-md);
  background: rgba(247, 245, 239, 0.08);
  border: 1px solid rgba(247, 245, 239, 0.12);
  cursor: pointer;
  transition: background 0.2s var(--ease);
  max-width: 320px;
}
.hero__stats:hover {
  background: rgba(247, 245, 239, 0.14);
}

.stat__value {
  font-size: 22px;
  font-weight: 700;
  color: #f7f5ef;
  line-height: 1.1;
}

.stat__value--sm {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stat__label {
  margin-top: 4px;
  font-size: 11px;
  color: rgba(247, 245, 239, 0.6);
}

.stat--cities {
  min-width: 0;
  flex: 1;
}

.stat__sep {
  width: 1px;
  align-self: stretch;
  background: rgba(247, 245, 239, 0.15);
}

/* ===== 入口 ===== */
.dash-cta {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}

.cta {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 16px 18px;
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-md);
  background: var(--surface-white);
  box-shadow: var(--shadow-sm);
  text-align: left;
  cursor: pointer;
  transition: transform 0.15s var(--ease), box-shadow 0.2s var(--ease), border-color 0.2s var(--ease);
}
.cta:hover {
  box-shadow: var(--shadow-md);
  border-color: rgba(47, 119, 112, 0.35);
  transform: translateY(-1px);
}
.cta:active {
  transform: scale(0.98);
}

.cta__title {
  font-size: 15px;
  font-weight: 650;
  color: var(--text-primary);
}

.cta__desc {
  font-size: 12px;
  color: var(--text-muted);
}

/* ===== 推荐/攻略块 ===== */
.rec-block__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 6px 2px 12px;
}

.rec-block__ops {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-shrink: 0;
}

.rec-block__title {
  margin: 0;
  font-size: 19px;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: 0.01em;
}

.rec-block__more {
  border: none;
  background: none;
  color: var(--brand-teal);
  font-size: 13px;
  font-weight: 550;
  cursor: pointer;
  transition: color 0.2s var(--ease);
}
.rec-block__more:hover {
  color: var(--brand-deep);
}

/* 「大家最近在规划」：标题 + 来源徽标，与"为你推荐"做视觉区分（§6.1） */
.rec-block__titles {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.rec-block__badge {
  flex-shrink: 0;
  padding: 2px 9px;
  border-radius: 999px;
  background: rgba(230, 184, 92, 0.16);
  color: #9a7420;
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.02em;
}

.rec-block__note {
  margin: -4px 2px 14px;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-muted);
}

.trend-skel {
  display: grid;
  gap: 8px;
}

.skel--row {
  height: 82px;
}

.trend-foot {
  margin: 10px 2px 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--text-muted);
}

.rec-block__cities {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}

.city-chip {
  border: 1px solid var(--border-soft);
  border-radius: 999px;
  padding: 5px 14px;
  background: var(--surface-white);
  color: var(--text-secondary);
  font-size: 12.5px;
  cursor: pointer;
  transition: all 0.18s var(--ease);
}
.city-chip:hover {
  border-color: var(--brand-teal);
  color: var(--brand-teal);
}
.city-chip--active {
  background: var(--brand-coral);
  border-color: var(--brand-coral);
  color: #fff;
  font-weight: 600;
}

.spot-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
}

.post-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 16px;
}

.skel {
  height: 220px;
  border-radius: var(--radius-lg);
  background: linear-gradient(100deg, rgba(23, 33, 31, 0.05) 40%, rgba(23, 33, 31, 0.02) 50%, rgba(23, 33, 31, 0.05) 60%);
  background-size: 200% 100%;
  animation: shimmer 1.6s linear infinite;
  pointer-events: none;
}

@keyframes shimmer {
  from { background-position: 120% 0; }
  to { background-position: -80% 0; }
}

.rec-empty {
  padding: 40px 20px;
  text-align: center;
  border-radius: var(--radius-lg);
  background: var(--surface-white);
  color: var(--text-muted);
  font-size: 14px;
}

/* 城市精选提示条：§6.1 要求首页把"城市精选"与"个性化推荐"分开标注 */
.rec-featured {
  margin: 10px 0 0;
  padding: 8px 12px;
  border-radius: 10px;
  background: rgba(217, 119, 93, 0.09);
  border: 1px solid rgba(217, 119, 93, 0.22);
  color: var(--brand-coral);
  font-size: 12.5px;
  font-weight: 550;
}

.rec-empty__link {
  border: none;
  background: none;
  color: var(--brand-teal);
  cursor: pointer;
  font-size: 14px;
}

/* ===== 响应式（方案 §6.2/§8） ===== */
@media (max-width: 1024px) {
  .hero {
    grid-template-columns: 1fr;
    align-items: start;
  }

  .hero__stats {
    max-width: none;
    align-items: center;
  }
}

@media (max-width: 900px) {
  .dash-cta {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 560px) {
  .hero {
    padding: 24px 20px 20px;
  }

  .hero__greet {
    font-size: 24px;
  }

  .hero__stats {
    gap: 12px;
    padding: 12px 14px;
  }

  .dash-cta {
    grid-template-columns: 1fr 1fr;
    gap: 10px;
  }

  .cta {
    padding: 13px 14px;
  }

  .rec-block__ops {
    gap: 8px;
  }
}
</style>
