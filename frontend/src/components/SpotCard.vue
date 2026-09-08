<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { message } from "ant-design-vue";
import { useRouter } from "vue-router";

import { favoriteSpot, reportBehavior, unfavoriteSpot } from "../services/api";
import type { RecommendationItem } from "../types";

/**
 * 景点推荐卡片（产品化阶段一：首页"为你推荐" / 发现页 / 详情页相关推荐复用，PLAN §14.2）。
 *
 * 卡片自身承载三类轻交互（均有请求中状态，防重复提交）：
 * - 收藏 / 取消收藏 → POST|DELETE /spots/{id}/favorite（幂等，首次收藏会触发画像 SAVE 升权）
 * - 不感兴趣 → 先选原因（负反馈粒度与结果页一致：只有 TYPE 才泛化降画像权重，其余仅留痕）
 * - 加入行程 → /plan?city=<城市>（行程表单预填目的地）
 * 任意反馈成功后 emit('changed')，父页面按需刷新推荐流（"反馈影响下次推荐"闭环演示）。
 */

const props = defineProps<{
  item: RecommendationItem;
  /** 精简模式（相关推荐小卡）：隐藏简介与"加入行程" */
  compact?: boolean;
  /** 反馈后由父页面重新拉取推荐流（收藏/不感兴趣都会改变个性化排序） */
  reloadOnChange?: boolean;
}>();

const emit = defineEmits<{
  (e: "changed"): void;
  (e: "open", item: RecommendationItem): void;
}>();

const router = useRouter();

/* ---------- 数据可信度展示 ---------- */
const QUALITY_META: Record<string, { label: string; cls: string }> = {
  VERIFIED: { label: "已核验", cls: "badge--verified" },
  GUIDE_MATCHED: { label: "攻略收录", cls: "badge--guide" },
  POI_ONLY: { label: "高德数据", cls: "badge--poi" },
};
const quality = computed(() => QUALITY_META[props.item.data_quality || ""] || {
  label: "数据",
  cls: "badge--poi",
});

const heroImgFailed = ref(false); // 卡片图 URL 失效（404）→ 降级首字占位，避免破图/空白
watch(
  () => props.item.spot_id,
  () => {
    heroImgFailed.value = false;
  }
);
const showHeroImage = computed(() => !!props.item.image_url && !heroImgFailed.value);
const showTags = computed(() => (props.item.tags || []).slice(0, 3));

/* ---------- 收藏（幂等；busy 防重复点击） ---------- */
const collected = ref(!!props.item.is_collected);
const favBusy = ref(false);

async function toggleFavorite() {
  if (favBusy.value) return;
  favBusy.value = true;
  try {
    if (collected.value) {
      await unfavoriteSpot(props.item.spot_id);
      collected.value = false;
      message.success(`已取消收藏「${props.item.name}」`);
    } else {
      await favoriteSpot(props.item.spot_id);
      collected.value = true;
      message.success(`已收藏「${props.item.name}」，之后会优先推荐这类景点`);
      // 收藏 = 画像 SAVE 升权 → 刷新让排序变化可见
      if (props.reloadOnChange) emit("changed");
    }
  } catch {
    message.error("操作失败，请稍后重试。");
  } finally {
    favBusy.value = false;
  }
}

/* ---------- 不感兴趣（负反馈粒度：TYPE 才泛化降权，与结果页同口径） ---------- */
const DISLIKE_REASON_OPTIONS = [
  { value: "TYPE", label: "少推荐这类" },
  { value: "ITEM", label: "只是不喜欢这里" },
  { value: "DISTANCE", label: "位置太远" },
  { value: "CROWDED", label: "人太多" },
  { value: "PRICE", label: "偏贵" },
  { value: "PACE", label: "节奏不合" },
] as const;

const pendingDislike = ref(false);
const dislikeBusy = ref(false);
const ignored = ref(false);

function startDislike() {
  if (dislikeBusy.value || ignored.value) return;
  pendingDislike.value = !pendingDislike.value;
}

async function submitDislike(reason: string) {
  if (dislikeBusy.value) return;
  dislikeBusy.value = true;
  try {
    await reportBehavior({
      itemType: "SPOT",
      // P1-6：优先系统稳定 spot_id（poi_id 为空也能进反馈漏斗）
      itemId: props.item.spot_id ?? props.item.poi_id ?? null,
      itemName: props.item.name,
      poiType: props.item.category ?? null,
      actionType: "DISLIKE",
      reason,
    });
    ignored.value = true;
    pendingDislike.value = false;
    message.success(
      reason === "TYPE"
        ? `已记住：以后少推荐「${props.item.name}」这类景点`
        : reason === "ITEM"
          ? "已记录，不影响你对这类地点的偏好"
          : "已记录反馈，后续推荐会参考"
    );
    if (props.reloadOnChange) emit("changed");
  } catch {
    message.error("反馈提交失败，请稍后重试。");
  } finally {
    dislikeBusy.value = false;
  }
}

/* ---------- 去详情 / 加入行程 ---------- */
function openDetail() {
  emit("open", props.item);
  void router.push({
    name: "spot-detail",
    params: { id: props.item.spot_id },
    query: { reason: props.item.recommend_reason || undefined },
  });
}

function addToPlan() {
  void router.push({ name: "plan", query: { city: props.item.city } });
}
</script>

<template>
  <article class="spot-card">
    <!-- 图（或占位）+ 可信度角标 + 收藏角标 -->
    <div class="spot-card__hero" role="button" tabindex="0" @click="openDetail" @keyup.enter="openDetail">
      <img
        v-if="showHeroImage"
        :src="item.image_url ?? undefined"
        :alt="item.name"
        class="spot-card__img spot-card__img--photo"
        loading="lazy"
        @error="heroImgFailed = true"
      />
      <div v-else class="spot-card__img spot-card__img--fallback">
        <span>{{ item.name.slice(0, 1) }}</span>
      </div>
      <span :class="['badge', quality.cls]">{{ quality.label }}</span>
    </div>

    <!-- 标题区（点击进详情） -->
    <div class="spot-card__body" role="button" tabindex="0" @click="openDetail" @keyup.enter="openDetail">
      <div class="spot-card__title-row">
        <h3 class="spot-card__name">{{ item.name }}</h3>
        <span class="spot-card__city">{{ item.city }}</span>
      </div>

      <div v-if="showTags.length" class="spot-card__tags">
        <span v-for="t in showTags" :key="t" class="spot-card__tag">{{ t }}</span>
      </div>

      <p v-if="item.description && !compact" class="spot-card__desc">{{ item.description }}</p>

      <p v-if="item.recommend_reason" class="spot-card__reason">🎯 {{ item.recommend_reason }}</p>
    </div>

    <!-- 反馈操作条 -->
    <div class="spot-card__actions">
      <button v-if="!compact" type="button" class="act act--plan" @click="addToPlan">
        ✚ 加入行程
      </button>
      <button
        type="button"
        :class="['act', collected ? 'act--fav act--fav-on' : 'act--fav']"
        :disabled="favBusy"
        @click="toggleFavorite"
      >
        {{ favBusy ? "..." : collected ? "♥ 已收藏" : "♡ 收藏" }}
      </button>
      <button
        type="button"
        :class="['act', ignored ? 'act--ignored' : 'act--dislike']"
        :disabled="dislikeBusy || ignored"
        @click="startDislike"
      >
        {{ ignored ? "✓ 已忽略" : "✕ 不感兴趣" }}
      </button>
    </div>

    <!-- 不感兴趣 → 原因选择（就近展开） -->
    <div v-if="pendingDislike" class="dislike-box">
      <div class="dislike-box__title">为什么对「{{ item.name }}」不感兴趣？</div>
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
  </article>
</template>

<style scoped>
.spot-card {
  display: flex;
  flex-direction: column;
  background: #FFFFFF;
  border-radius: 14px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  transition: box-shadow 0.2s ease;
}

.spot-card:hover {
  box-shadow: 0 3px 12px rgba(0, 0, 0, 0.12);
}

.spot-card__hero {
  position: relative;
  height: 132px;
  cursor: pointer;
}

.spot-card__img {
  width: 100%;
  height: 100%;
}

.spot-card__img--photo {
  display: block;
  object-fit: cover;
}

.spot-card__img--fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #dbe7f5, #e8f0fb);
  font-size: 40px;
  font-weight: 700;
  color: #b7c9e0;
}

.badge {
  position: absolute;
  top: 10px;
  left: 10px;
  padding: 3px 8px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 600;
  color: #fff;
  backdrop-filter: blur(4px);
}

.badge--verified { background: rgba(52, 199, 89, 0.92); }
.badge--guide { background: rgba(0, 122, 255, 0.92); }
.badge--poi { background: rgba(142, 142, 147, 0.85); }

.spot-card__body {
  padding: 12px 14px 6px;
  cursor: pointer;
  flex: 1;
}

.spot-card__title-row {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.spot-card__name {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #1C1C1E;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.spot-card__city {
  font-size: 12px;
  color: #8E8E93;
  flex-shrink: 0;
}

.spot-card__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 8px;
}

.spot-card__tag {
  padding: 2px 8px;
  border-radius: 10px;
  background: rgba(0, 122, 255, 0.07);
  color: #185FA5;
  font-size: 11px;
}

.spot-card__desc {
  margin: 8px 0 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: #6E6E73;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.spot-card__reason {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: #185FA5;
  background: rgba(0, 122, 255, 0.05);
  border-radius: 8px;
  padding: 5px 8px;
}

.spot-card__actions {
  display: flex;
  gap: 6px;
  padding: 6px 12px 12px;
}

.act {
  border: 1px solid #E2E2E7;
  border-radius: 8px;
  padding: 5px 10px;
  font-size: 12px;
  background: #FFFFFF;
  color: #3C3C43;
  cursor: pointer;
  transition: all 0.15s ease;
}

.act:active { transform: scale(0.96); }
.act:disabled { opacity: 0.6; cursor: default; }

.act--plan { color: #007AFF; border-color: rgba(0, 122, 255, 0.35); }
.act--fav { color: #E8590C; }
.act--fav-on { background: rgba(232, 89, 12, 0.08); border-color: rgba(232, 89, 12, 0.3); }
.act--dislike { color: #8E8E93; }
.act--ignored { color: #34C759; border-color: rgba(52, 199, 89, 0.4); cursor: default; }

.dislike-box {
  margin: 0 12px 12px;
  padding: 10px;
  border-radius: 10px;
  background: #F7F7FA;
}

.dislike-box__title {
  font-size: 12px;
  color: #3C3C43;
  font-weight: 500;
  margin-bottom: 8px;
}

.dislike-box__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.dislike-chip {
  border: none;
  border-radius: 14px;
  padding: 4px 10px;
  background: #FFFFFF;
  border: 1px solid #E2E2E7;
  color: #3C3C43;
  font-size: 12px;
  cursor: pointer;
}

.dislike-chip:active { transform: scale(0.96); }

.dislike-cancel {
  margin-top: 8px;
  border: none;
  background: none;
  color: #8E8E93;
  font-size: 12px;
  cursor: pointer;
}
</style>
