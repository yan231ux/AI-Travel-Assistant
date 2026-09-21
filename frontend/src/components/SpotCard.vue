<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { message } from "ant-design-vue";
import { useRouter } from "vue-router";

import { favoriteSpot, reportBehavior, unfavoriteSpot } from "../services/api";
import type { RecommendationItem } from "../types";
import { visibleMatchPercent } from "../utils/personalization";

/**
 * 景点推荐卡片（产品化阶段一：首页"为你推荐" / 发现页 / 详情页相关推荐复用，PLAN §14.2）。
 *
 * 卡片自身承载三类轻交互（均有请求中状态，防重复提交）：
 * - 收藏 / 取消收藏 → POST|DELETE /spots/{id}/favorite（幂等，首次收藏会触发画像 SAVE 升权）
 * - 不感兴趣 → 先选原因（负反馈粒度与结果页一致：只有 TYPE 才泛化降画像权重，其余仅留痕）
 * - 加入行程 → /plan?city=<城市>&spot=<景点名>&spot_id=<id>&poi_id=<id>
 *   （排查报告 P0-2：必须携带具体景点，让规划页能显性化并让生成器优先安排）
 * 任意反馈成功后 emit('changed')，父页面按需刷新推荐流（"反馈影响下次推荐"闭环演示）。
 */

const props = defineProps<{
  item: RecommendationItem;
  /** 精简模式（相关推荐小卡）：隐藏简介与"加入行程" */
  compact?: boolean;
  /** 反馈后由父页面重新拉取推荐流（收藏/不感兴趣都会改变个性化排序） */
  reloadOnChange?: boolean;
  /**
   * 运营精选标签（该卡片命中运营置顶时由父页面传入）。
   * 缺口修复：运营置顶原本只改顺序、用户端看不到任何标识，用户不知道"为什么它在最前"。
   */
  operationTag?: string;
  /** 运营精选原因（展示给用户，回答"凭什么是它"；为空则只显示标签） */
  operationReason?: string | null;
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

/**
 * 个性化匹配度百分比（排查报告 P0-1）：只认后端下发的真实命中语义
 * personalized + match_score + matched_preferences 三件套，不再拿综合排序分 score 充数；
 * 无画像/未命中/攻略优先/最近更新 的卡片后端不给这三件套 → 这里自然返回 null 隐藏徽标。
 */
const matchPercent = computed<number | null>(() => visibleMatchPercent(props.item));

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
  // P0-2（排查报告）："加入行程"必须携带具体景点，不能再只丢一个城市
  void router.push({
    name: "plan",
    query: {
      city: props.item.city,
      spot: props.item.name,
      spot_id: props.item.spot_id,
      poi_id: props.item.poi_id || undefined,
    },
  });
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
      <!-- 运营精选（右上）：运营置顶是排序层动作、不改算法分，所以这里只用独立角标标注，不混进匹配度 -->
      <span v-if="operationTag" class="badge badge--ops" :title="operationReason || undefined">
        ★ {{ operationTag }}
      </span>
    </div>

    <!-- 标题区（点击进详情） -->
    <div class="spot-card__body" role="button" tabindex="0" @click="openDetail" @keyup.enter="openDetail">
      <div class="spot-card__title-row">
        <h3 class="spot-card__name">{{ item.name }}</h3>
        <span class="spot-card__city">{{ item.city }}</span>
      </div>

      <div v-if="matchPercent !== null" class="spot-card__match">
        与你的偏好匹配度 {{ matchPercent }}%
      </div>

      <div v-if="showTags.length" class="spot-card__tags">
        <span v-for="t in showTags" :key="t" class="spot-card__tag">{{ t }}</span>
      </div>

      <p v-if="item.description && !compact" class="spot-card__desc">{{ item.description }}</p>

      <p v-if="item.recommend_reason" class="spot-card__reason">{{ item.recommend_reason }}</p>
      <p v-if="operationTag && operationReason" class="spot-card__ops-reason">运营精选：{{ operationReason }}</p>
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
  background: var(--surface-white);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-lg);
  overflow: hidden;
  box-shadow: var(--shadow-sm);
  transition: box-shadow 0.2s ease, transform 0.15s var(--ease);
}

.spot-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
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
  background: linear-gradient(135deg, rgba(47, 119, 112, 0.1), rgba(230, 184, 92, 0.09));
  font-size: 40px;
  font-weight: 700;
  color: rgba(47, 119, 112, 0.35);
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

.badge--verified { background: var(--success); }
.badge--guide { background: var(--brand-teal); }
.badge--poi { background: rgba(23, 33, 31, 0.55); }
/* 运营精选：与可信度角标分居两侧，颜色用珊瑚色区别于"数据质量"语义 */
.badge--ops {
  left: auto;
  right: 10px;
  background: var(--brand-coral);
}

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
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.spot-card__city {
  font-size: 12px;
  color: var(--text-muted);
  flex-shrink: 0;
}

/* 个性化匹配度徽标（仅 personalized 排序产物展示，UI 方案 §6.4） */
.spot-card__match {
  display: inline-flex;
  align-items: center;
  margin-top: 8px;
  padding: 3px 10px;
  border-radius: 999px;
  background: rgba(217, 119, 93, 0.1);
  color: var(--brand-coral);
  font-size: 11.5px;
  font-weight: 650;
  letter-spacing: 0.01em;
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
  background: rgba(47, 119, 112, 0.07);
  color: var(--brand-deep);
  font-size: 11px;
}

.spot-card__desc {
  margin: 8px 0 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.spot-card__reason {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--brand-deep);
  background: rgba(47, 119, 112, 0.06);
  border-radius: 8px;
  padding: 5px 8px;
}

/* 运营精选原因：与"推荐理由"区分开，避免用户把运营标记误读成算法推荐理由 */
.spot-card__ops-reason {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--brand-coral);
  background: rgba(217, 119, 93, 0.08);
  border-radius: 8px;
  padding: 5px 8px;
}

.spot-card__actions {
  display: flex;
  gap: 6px;
  padding: 6px 12px 12px;
}

.act {
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  padding: 5px 10px;
  font-size: 12px;
  background: var(--surface-white);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.15s ease;
}

.act:active { transform: scale(0.96); }
.act:disabled { opacity: 0.6; cursor: default; }

.act--plan { color: var(--brand-teal); border-color: rgba(47, 119, 112, 0.35); }
.act--fav { color: var(--brand-coral); }
.act--fav-on { background: rgba(217, 119, 93, 0.08); border-color: rgba(217, 119, 93, 0.3); }
.act--dislike { color: var(--text-muted); }
.act--ignored { color: var(--success); border-color: rgba(60, 140, 112, 0.4); cursor: default; }

.dislike-box {
  margin: 0 12px 12px;
  padding: 10px;
  border-radius: var(--radius-md);
  background: rgba(23, 33, 31, 0.04);
}

.dislike-box__title {
  font-size: 12px;
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
  border: none;
  border-radius: 14px;
  padding: 4px 10px;
  background: var(--surface-white);
  border: 1px solid var(--border-soft);
  color: var(--text-secondary);
  font-size: 12px;
  cursor: pointer;
}

.dislike-chip:active { transform: scale(0.96); }

.dislike-cancel {
  margin-top: 8px;
  border: none;
  background: none;
  color: var(--text-muted);
  font-size: 12px;
  cursor: pointer;
}
</style>
