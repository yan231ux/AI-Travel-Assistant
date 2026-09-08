<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";

import PostCard from "../components/PostCard.vue";
import SpotCard from "../components/SpotCard.vue";
import { POPULAR_CITIES } from "../constants/cities";
import { getCityTopic } from "../services/api";
import type { CityTopic } from "../types";

/**
 * 城市专题页（阶段四任务 1，/city/:name）。
 *
 * 一个城市的"一页式"入口：规模统计（已收录景点 / 已发布攻略）、精选景点（攻略质量优先）、
 * 最新公开攻略。数据来自 GET /city-topic 单接口聚合（复用推荐流与公开流语义，
 * 不新增算法口径）；顶部可切换城市，右侧入口直达"为该城生成行程"。
 */
const route = useRoute();
const router = useRouter();

const city = ref<string>((route.params.name as string) || "");
const topic = ref<CityTopic | null>(null);
const loading = ref(true);
const error = ref("");

const hotCities = POPULAR_CITIES.slice(0, 12);

async function load() {
  if (!city.value.trim()) return;
  loading.value = true;
  error.value = "";
  try {
    topic.value = await getCityTopic(city.value.trim());
  } catch {
    error.value = "城市专题加载失败，请稍后重试。";
  } finally {
    loading.value = false;
  }
}

function switchCity(c: string) {
  if (c === city.value) return;
  city.value = c;
  void router.replace({ name: "city-topic", params: { name: c } });
  void load();
}

function goPlan() {
  void router.push({ name: "plan", query: { city: city.value } });
}

function goDiscover() {
  void router.push({ name: "recommendations", query: { city: city.value } });
}

onMounted(() => {
  void load();
});
</script>

<template>
  <section class="ct-page">
    <template v-if="city">
      <div class="ct-head">
        <div class="ct-head__main">
          <h2 class="ct-title">🗺️ {{ city }}旅行专题</h2>
          <p class="ct-sub">
            <template v-if="topic">
              已收录 {{ topic.spot_total }} 个景点 · {{ topic.post_total }} 篇公开攻略
            </template>
            <template v-else>正在整理这座城市…</template>
          </p>
        </div>
        <div class="ct-head__ops">
          <button type="button" class="btn btn--primary" @click="goPlan">✈️ 生成{{ city }}行程</button>
          <button type="button" class="btn" @click="goDiscover">发现更多 ›</button>
        </div>
      </div>

      <!-- 城市切换 -->
      <div class="ct-cities">
        <button
          v-for="c in hotCities"
          :key="c"
          type="button"
          :class="['city-chip', { 'city-chip--on': c === city }]"
          @click="switchCity(c)"
        >
          {{ c }}
        </button>
      </div>

      <div v-if="loading" class="ct-empty">加载中…</div>
      <div v-else-if="error" class="ct-empty">{{ error }}</div>
      <template v-else-if="topic">
        <!-- 精选景点（口径=攻略质量优先，第一版无真实浏览量/收藏埋点，不冒充"热门"） -->
        <div class="ct-block">
          <div class="ct-block__head">
            <h3 class="ct-block__title">🔥 {{ city }}精选景点</h3>
            <button type="button" class="ct-block__more" @click="goDiscover">全部景点 ›</button>
          </div>
          <div v-if="topic.hot_spots.length" class="spot-grid">
            <SpotCard
              v-for="item in topic.hot_spots"
              :key="item.spot_id"
              :item="item"
              @changed="load"
            />
          </div>
          <div v-else class="ct-empty">该城市还没有可展示的景点，先去生成一次行程把它点亮吧。</div>
        </div>

        <!-- 最新攻略 -->
        <div class="ct-block">
          <div class="ct-block__head">
            <h3 class="ct-block__title">📖 {{ city }}最新攻略</h3>
            <button type="button" class="ct-block__more" @click="router.push({ name: 'community' })">进社区 ›</button>
          </div>
          <div v-if="topic.recent_posts.length" class="post-grid">
            <PostCard v-for="p in topic.recent_posts" :key="p.id" :post="p" @changed="load" />
          </div>
          <div v-else class="ct-empty">
            还没有人分享这座城市 —— 去发第一篇攻略吧。
            <button type="button" class="link-btn" @click="router.push({ name: 'post-create', query: { city } })">去发帖 ›</button>
          </div>
        </div>
      </template>
    </template>
  </section>
</template>

<style scoped>
.ct-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.ct-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  background: linear-gradient(135deg, var(--text-primary) 0%, var(--text-primary) 100%);
  border-radius: 16px;
  padding: 20px 22px;
  color: #fff;
}
.ct-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
}
.ct-sub {
  margin: 6px 0 0;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.72);
}
.ct-head__ops {
  display: flex;
  gap: 8px;
}
.btn {
  border: none;
  border-radius: 10px;
  padding: 8px 14px;
  font-size: 13px;
  font-weight: 500;
  background: rgba(0, 0, 0, 0.06);
  color: var(--text-secondary);
  cursor: pointer;
}
.ct-head .btn {
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
}
.ct-head .btn--primary {
  background: #fff;
  color: var(--text-primary);
}
.ct-cities {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.city-chip {
  border: none;
  border-radius: 999px;
  padding: 5px 14px;
  font-size: 13px;
  background: #fff;
  color: var(--text-secondary);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
  cursor: pointer;
}
.city-chip--on {
  background: var(--text-primary);
  color: #fff;
}
.ct-block {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.ct-block__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.ct-block__title {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  color: var(--text-primary);
}
.ct-block__more {
  border: none;
  background: none;
  color: var(--brand-teal);
  font-size: 13px;
  cursor: pointer;
}
.ct-empty {
  text-align: center;
  padding: 36px 0;
  color: var(--text-muted);
  background: #fff;
  border-radius: 14px;
  font-size: 13px;
  line-height: 1.8;
}
.link-btn {
  border: none;
  background: none;
  color: var(--brand-teal);
  font-size: 13px;
  cursor: pointer;
  padding: 0 2px;
}
.spot-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
}
.post-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
}
</style>
