<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { message } from "ant-design-vue";
import { useRoute, useRouter } from "vue-router";

import SpotCard from "../components/SpotCard.vue";
import { fetchSpotDetail, favoriteSpot, reportBehavior, unfavoriteSpot } from "../services/api";
import type { RecommendationItem, SpotDetail as SpotDetailType } from "../types";

/**
 * 景点详情页（产品化阶段一 /spots/:id，PLAN §6）。
 *
 * 承载：图文 + 城市/地址 + 数据可信度标识 + 为什么推荐（来源卡片的确定性理由经
 * query.reason 透传；无来源时只展示事实型提示，不编造）+ 收藏 / 不感兴趣（负反馈粒度
 * 与结果页同口径）+ 同城相关推荐（SpotCard 复用）。
 */
const props = defineProps<{ id: string }>();
const route = useRoute();
const router = useRouter();

const detail = ref<SpotDetailType | null>(null);
const loading = ref(true);
const error = ref("");
const heroImgFailed = ref(false); // 主图 URL 失效（404/加载失败）→ 降级为首字占位，避免破图

/* 收藏 / 不感兴趣 状态（必须在 load() 之前声明：watch({ immediate:true }) 会同步触发 load，TDZ 会直接抛） */
const collected = ref(false);
const favBusy = ref(false);
const pendingDislike = ref(false);
const dislikeBusy = ref(false);
const ignored = ref(false);

const QUALITY_META: Record<string, { label: string; desc: string }> = {
  VERIFIED: { label: "已核验", desc: "内容经本地攻略人工核实，可信度高" },
  GUIDE_MATCHED: { label: "攻略收录", desc: "简介来自本地 RAG 攻略库，非模型编造" },
  POI_ONLY: { label: "高德基础数据", desc: "暂无本地攻略核实，仅展示地图数据，未写 AI 简介" },
};

const quality = computed(() => QUALITY_META[detail.value?.data_quality || ""] || {
  label: "数据",
  desc: "",
});

async function load() {
  loading.value = true;
  error.value = "";
  detail.value = null;
  heroImgFailed.value = false;
  collected.value = false;
  ignored.value = false;
  try {
    const d = await fetchSpotDetail(props.id);
    detail.value = d;
    collected.value = !!d.is_collected;
  } catch {
    error.value = "景点不存在或加载失败。";
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.id,
  () => void load(),
  { immediate: true }
);

/* ---------- 收藏 ---------- */
async function toggleFavorite() {
  if (favBusy.value || !detail.value) return;
  favBusy.value = true;
  try {
    if (collected.value) {
      await unfavoriteSpot(detail.value.spot_id);
      collected.value = false;
      message.success("已取消收藏");
    } else {
      await favoriteSpot(detail.value.spot_id);
      collected.value = true;
      message.success("已收藏，之后会优先推荐这类景点");
    }
  } catch {
    message.error("操作失败，请稍后重试。");
  } finally {
    favBusy.value = false;
  }
}

/* ---------- 不感兴趣（先选原因；TYPE 才泛化降画像权重） ---------- */
const DISLIKE_REASON_OPTIONS = [
  { value: "TYPE", label: "少推荐这类景点" },
  { value: "ITEM", label: "只是不喜欢这里" },
  { value: "DISTANCE", label: "位置太远" },
  { value: "CROWDED", label: "人太多" },
  { value: "PRICE", label: "门票/消费偏高" },
  { value: "PACE", label: "不适合我的节奏" },
] as const;

function toggleDislikePanel() {
  if (dislikeBusy.value || ignored.value) return;
  pendingDislike.value = !pendingDislike.value;
}

async function submitDislike(reason: string) {
  const d = detail.value;
  if (!d || dislikeBusy.value) return;
  dislikeBusy.value = true;
  try {
    await reportBehavior({
      itemType: "SPOT",
      // P1-6：优先系统稳定 spot_id（poi_id 为空也能进反馈漏斗）
      itemId: d.spot_id ?? d.poi_id ?? null,
      itemName: d.name,
      poiType: d.category ?? null,
      actionType: "DISLIKE",
      reason,
    });
    ignored.value = true;
    pendingDislike.value = false;
    message.success(
      reason === "TYPE"
        ? `已记住：以后少推荐「${d.name}」这类景点`
        : reason === "ITEM"
          ? "已记录，不影响你对这类地点的偏好"
          : "已记录反馈，后续推荐会参考"
    );
  } catch {
    message.error("反馈提交失败，请稍后重试。");
  } finally {
    dislikeBusy.value = false;
  }
}

/* ---------- 地图链接 ---------- */
const mapUrl = computed(() => {
  const d = detail.value;
  if (!d) return "";
  if (d.longitude && d.latitude) {
    return `https://uri.amap.com/marker?position=${d.longitude},${d.latitude}&name=${encodeURIComponent(d.name)}`;
  }
  return `https://uri.amap.com/search?keyword=${encodeURIComponent(d.name)}&city=${encodeURIComponent(d.city)}`;
});

/* 地图在新窗口打开 */
function openMap() {
  if (mapUrl.value) window.open(mapUrl.value, "_blank", "noopener");
}

function addToPlan() {
  const d = detail.value;
  if (!d) return;
  // P0-2（排查报告）：详情页"加入行程"同样要带具体景点，让规划页能显性化并让生成器优先安排
  void router.push({
    name: "plan",
    query: {
      city: d.city,
      spot: d.name,
      spot_id: d.spot_id,
      poi_id: d.poi_id || undefined,
    },
  });
}

function goBack() {
  if (window.history.length > 1) {
    router.back();
  } else {
    void router.push({ name: "recommendations" });
  }
}

/* 卡片跳转详情时透传的"为什么推荐" */
const fromReason = computed(() => {
  const r = route.query.reason;
  return r && typeof r === "string" ? r : "";
});

function onRelatedChanged() {
  /* 相关推荐无反馈联动需求（详情页自身状态足够） */
}
</script>

<template>
  <section class="detail-page">
    <button type="button" class="back-btn" @click="goBack">‹ 返回</button>

    <div v-if="loading" class="detail-loading">正在加载景点...</div>

    <div v-else-if="error" class="detail-error">
      <p>{{ error }}</p>
      <button type="button" class="retry" @click="load">重新加载</button>
    </div>

    <template v-else-if="detail">
      <!-- 主图（URL 加载失败 → 降级首字占位） -->
      <div class="hero">
        <img
          v-if="detail.image_url && !heroImgFailed"
          :src="detail.image_url"
          :alt="detail.name"
          class="hero__img"
          @error="heroImgFailed = true"
        />
        <div v-else class="hero__img hero__img--fallback">{{ detail.name.slice(0, 1) }}</div>
        <span :class="['badge', `badge--${(detail.data_quality || 'poi').toLowerCase()}`]">
          {{ quality.label }}
        </span>
      </div>

      <!-- 信息 -->
      <div class="ios-card head-card">
        <div class="head-card__title-row">
          <h1 class="head-card__name">{{ detail.name }}</h1>
          <span class="head-card__city">{{ detail.city }}</span>
        </div>

        <div v-if="detail.tags && detail.tags.length" class="tag-row">
          <span v-for="t in detail.tags" :key="t" class="tag">{{ t }}</span>
        </div>

        <p v-if="detail.visited" class="flag flag--visited">✓ 你曾在历史行程中去过这里</p>
        <p v-else class="flag flag--new">新地点 · 你还没有去过</p>

        <!-- 为什么推荐（来源卡片理由透传；无则事实型提示） -->
        <div v-if="fromReason || detail.visited !== null" class="why-box">
          <div class="why-box__title">🎯 为什么推荐给你</div>
          <p v-if="fromReason" class="why-box__text">{{ fromReason }}</p>
          <p v-else-if="!detail.visited" class="why-box__text">为你发现的候选景点，结合了你的偏好与历史行程</p>
          <p v-else class="why-box__text">你曾体验过这里，适合作为熟悉选项安排</p>
        </div>

        <!-- 地址 / 坐标 / 地图 -->
        <div class="meta-list">
          <div v-if="detail.address" class="meta-row">
            <span class="meta-row__label">地址</span>
            <span class="meta-row__value">{{ detail.address }}</span>
          </div>
          <div v-if="detail.category" class="meta-row">
            <span class="meta-row__label">分类</span>
            <span class="meta-row__value">{{ detail.category }}</span>
          </div>
          <div class="meta-row">
            <span class="meta-row__label">数据</span>
            <span class="meta-row__value">{{ quality.label }} · {{ quality.desc }}</span>
          </div>
        </div>

        <!-- 操作 -->
        <div class="action-bar">
          <button
            type="button"
            :class="['big-btn', collected ? 'big-btn--fav-on' : 'big-btn--fav']"
            :disabled="favBusy"
            @click="toggleFavorite"
          >
            {{ favBusy ? "处理中..." : collected ? "♥ 已收藏" : "♡ 收藏" }}
          </button>
          <button
            type="button"
            :class="['big-btn', ignored ? 'big-btn--ignored' : 'big-btn--ghost']"
            :disabled="dislikeBusy || ignored"
            @click="toggleDislikePanel"
          >
            {{ ignored ? "✓ 已忽略" : "✕ 不感兴趣" }}
          </button>
          <button type="button" class="big-btn big-btn--plan" @click="addToPlan">✚ 加入行程</button>
          <button type="button" class="big-btn big-btn--ghost" @click="openMap">🗺 地图</button>
        </div>

        <!-- 不感兴趣原因 -->
        <div v-if="pendingDislike" class="dislike-box">
          <div class="dislike-box__title">为什么对「{{ detail.name }}」不感兴趣？</div>
          <div class="dislike-box__chips">
            <button
              v-for="opt in DISLIKE_REASON_OPTIONS"
              :key="opt.value"
              type="button"
              class="dislike-chip"
              :disabled="dislikeBusy"
              @click="submitDislike(opt.value)"
            >
              {{ opt.label }}
            </button>
          </div>
          <button type="button" class="dislike-cancel" :disabled="dislikeBusy" @click="pendingDislike = false">
            取消
          </button>
        </div>
      </div>

      <!-- 简介 -->
      <div class="ios-card">
        <div class="ios-card__header">📖 景点简介</div>
        <p v-if="detail.description" class="intro">{{ detail.description }}</p>
        <p v-else class="intro intro--muted">
          暂无可信简介：该景点尚未被本地攻略收录，为避免编造内容，仅展示地图基础数据。
        </p>
      </div>

      <!-- 相关推荐 -->
      <div v-if="detail.related_spots && detail.related_spots.length" class="related">
        <h3 class="related__title">同城相关推荐</h3>
        <div class="related__grid">
          <SpotCard
            v-for="item in (detail.related_spots as RecommendationItem[])"
            :key="item.spot_id"
            :item="item"
            compact
            @changed="onRelatedChanged"
          />
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.detail-page {
  display: grid;
  gap: 14px;
}

.back-btn {
  border: none;
  background: none;
  color: var(--brand-teal);
  font-size: 15px;
  cursor: pointer;
  justify-self: start;
  padding: 2px 4px;
}

.ios-card {
  border-radius: 14px;
  background: var(--surface-white);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  padding: 18px;
}

.ios-card__header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.detail-loading,
.detail-error {
  padding: 60px 20px;
  text-align: center;
  border-radius: 14px;
  background: var(--surface-white);
  color: var(--text-muted);
  font-size: 14px;
}

.retry {
  margin-top: 12px;
  border: none;
  border-radius: 10px;
  padding: 8px 20px;
  background: var(--brand-teal);
  color: var(--surface-white);
  font-size: 14px;
  cursor: pointer;
}

/* hero */
.hero {
  position: relative;
  height: 240px;
  border-radius: 14px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.hero__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.hero__img--fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, rgba(47, 119, 112, 0.12), rgba(47, 119, 112, 0.08));
  font-size: 72px;
  font-weight: 700;
  color: rgba(47, 119, 112, 0.35);
}

.badge {
  position: absolute;
  top: 14px;
  left: 14px;
  padding: 4px 10px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 600;
  color: #fff;
  backdrop-filter: blur(4px);
}

.badge--verified { background: rgba(60, 140, 112, 0.92); }
.badge--guide_matched { background: rgba(47, 119, 112, 0.92); }
.badge--poi_only, .badge--poi { background: rgba(142, 142, 147, 0.85); }

/* head card */
.head-card__title-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.head-card__name {
  margin: 0;
  font-size: 21px;
  font-weight: 700;
  color: var(--text-primary);
}

.head-card__city {
  font-size: 13px;
  color: var(--text-muted);
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.tag {
  padding: 3px 10px;
  border-radius: 12px;
  background: rgba(47, 119, 112, 0.07);
  color: var(--brand-deep);
  font-size: 12px;
}

.flag {
  margin: 12px 0 0;
  font-size: 12.5px;
}

.flag--visited { color: var(--brand-coral); }
.flag--new { color: var(--success); }

.why-box {
  margin-top: 14px;
  padding: 12px;
  border-radius: 10px;
  background: rgba(47, 119, 112, 0.05);
}

.why-box__title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--brand-deep);
  margin-bottom: 6px;
}

.why-box__text {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-secondary);
}

.meta-list {
  margin-top: 14px;
  display: grid;
  gap: 6px;
}

.meta-row {
  display: flex;
  gap: 10px;
  font-size: 13px;
}

.meta-row__label {
  flex-shrink: 0;
  color: var(--text-muted);
}

.meta-row__value {
  color: var(--text-secondary);
  line-height: 1.5;
}

/* actions */
.action-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}

.big-btn {
  border: 1px solid var(--border-soft);
  border-radius: 10px;
  padding: 9px 16px;
  background: var(--surface-white);
  color: var(--text-secondary);
  font-size: 14px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.big-btn:active { transform: scale(0.97); }
.big-btn:disabled { opacity: 0.6; cursor: default; }

.big-btn--fav { color: var(--brand-coral); }
.big-btn--fav-on { background: rgba(217, 119, 93, 0.08); border-color: rgba(217, 119, 93, 0.3); }
.big-btn--plan { color: var(--brand-teal); border-color: rgba(47, 119, 112, 0.35); }
.big-btn--ignored { color: var(--success); border-color: rgba(60, 140, 112, 0.4); cursor: default; }

.dislike-box {
  margin-top: 12px;
  padding: 12px;
  border-radius: 10px;
  background: rgba(23, 33, 31, 0.03);
}

.dislike-box__title {
  font-size: 13px;
  color: var(--text-secondary);
  font-weight: 500;
  margin-bottom: 8px;
}

.dislike-box__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.dislike-chip {
  border: 1px solid var(--border-soft);
  border-radius: 14px;
  padding: 5px 12px;
  background: var(--surface-white);
  color: var(--text-secondary);
  font-size: 12.5px;
  cursor: pointer;
}

.dislike-cancel {
  margin-top: 8px;
  border: none;
  background: none;
  color: var(--text-muted);
  font-size: 12.5px;
  cursor: pointer;
}

/* intro */
.intro {
  margin: 0;
  font-size: 14px;
  line-height: 1.8;
  color: var(--text-secondary);
  white-space: pre-line;
}

.intro--muted {
  color: var(--text-muted);
}

/* related */
.related__title {
  margin: 4px 2px 12px;
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
}

.related__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
  gap: 12px;
}
</style>
