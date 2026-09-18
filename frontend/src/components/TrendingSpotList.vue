<script setup lang="ts">
import { ref } from "vue";
import { message } from "ant-design-vue";
import { useRouter } from "vue-router";

import { favoriteSpot, resolveImageUrl, unfavoriteSpot } from "../services/api";
import type { TrendingSpotItem } from "../types";

/**
 * 「大家最近在规划」热门榜单（设计方案 §6.2）。
 *
 * 定位边界（§6.1 硬要求）：本组件展示的是**社会热度**，不是"适合你"。
 * 因此：
 * - 不渲染任何匹配度/画像文案（后端也不下发），避免把全站热门伪装成个性化推荐；
 * - 标题文案由父页面写成"大家最近在规划"，与"为你推荐"分区独立。
 * 个性化若要叠加，必须由父页面另起一行说明（本组件不掺和）。
 *
 * 小样本保护（§6.5）：后端在规划人数不足阈值时下发 sample_hidden=true + planning_users=null，
 * 此时**只能**展示"近期有人规划过"，绝不能显示数字（否则会泄露个体行为）。
 */
const props = defineProps<{
  items: TrendingSpotItem[];
  /** 统计窗口天数（文案"近 N 天"用，来自后端 window_days，不在前端写死） */
  windowDays: number;
}>();

const emit = defineEmits<{ (e: "changed"): void }>();

const router = useRouter();

/* ---------- 名次徽标：前三名用落日金强调，其余用中性色 ---------- */
function rankClass(i: number): string {
  return i === 0 ? "rank--1" : i === 1 ? "rank--2" : i === 2 ? "rank--3" : "rank--n";
}

/* ---------- 趋势方向（UP 升温 / DOWN 降温 / FLAT 持平） ---------- */
const TREND_META: Record<string, { label: string; cls: string }> = {
  UP: { label: "↑ 升温", cls: "trend--up" },
  DOWN: { label: "↓ 降温", cls: "trend--down" },
  FLAT: { label: "— 持平", cls: "trend--flat" },
};

function trendOf(item: TrendingSpotItem) {
  return TREND_META[item.trend] || TREND_META.FLAT;
}

/**
 * 热度文案：人数按"窗口内单日去重后取峰值"口径（预聚合表无法跨天去重），
 * 所以仅在未触发小样本保护时展示具体数字，并用 title 说明口径，避免误读成"7 天累计人数"。
 */
const SAMPLE_NOTE = "人数为统计窗口内单日去重规划用户数的峰值";

/* ---------- 图片：URL 失效降级首字占位，避免破图 ---------- */
const failed = ref<Record<string, boolean>>({});
function imgOk(item: TrendingSpotItem): boolean {
  return !!item.image_url && !failed.value[item.spot_id];
}
function onImgError(id: string) {
  failed.value = { ...failed.value, [id]: true };
}

/* ---------- 收藏（幂等；本地记录本次点击后的状态） ---------- */
const collected = ref<Record<string, boolean>>({});
const favBusy = ref<Record<string, boolean>>({});

async function toggleFavorite(item: TrendingSpotItem) {
  const id = item.spot_id;
  if (favBusy.value[id]) return;
  favBusy.value = { ...favBusy.value, [id]: true };
  const wasCollected = !!collected.value[id];
  try {
    if (wasCollected) {
      await unfavoriteSpot(id);
      collected.value = { ...collected.value, [id]: false };
      message.success(`已取消收藏「${item.name}」`);
    } else {
      const resp = await favoriteSpot(id);
      collected.value = { ...collected.value, [id]: true };
      message.success(
        resp.existed
          ? `「${item.name}」已在你的收藏中`
          : `已收藏「${item.name}」，之后会优先推荐这类景点`
      );
      // 收藏是真实的用户偏好信号（画像 SAVE 升权）→ 通知父页面刷新"为你推荐"，
      // 让"社会热度里的一次收藏，改变了我的个性化排序"这个闭环可见（§6.1 分区但可联动）
      if (!resp.existed) emit("changed");
    }
  } catch {
    message.error("操作失败，请稍后重试。");
  } finally {
    favBusy.value = { ...favBusy.value, [id]: false };
  }
}

/* ---------- 查看详情 / 加入行程 ---------- */
function openDetail(item: TrendingSpotItem) {
  void router.push({ name: "spot-detail", params: { id: item.spot_id } });
}

function addToPlan(item: TrendingSpotItem) {
  // 与 SpotCard 同口径：必须携带具体景点，规划页才能显性化并优先安排。
  // 注意热门接口不单独下发 poi_id，只给 item_id（可能是 spot_id 也可能是 poi_id）：
  // 仅当 item_id 与 spot_id 不同才当作 poi_id 传，避免把 spot_id 误当 poi_id。
  void router.push({
    name: "plan",
    query: {
      city: item.city,
      spot: item.name,
      spot_id: item.spot_id,
      poi_id: item.item_id && item.item_id !== item.spot_id ? item.item_id : undefined,
    },
  });
}
</script>

<template>
  <div class="trend-list">
    <article v-for="(item, i) in props.items" :key="item.spot_id" class="trend-row">
      <span :class="['trend-row__rank font-num', rankClass(i)]">{{ i + 1 }}</span>

      <div class="trend-row__thumb" role="button" tabindex="0" @click="openDetail(item)" @keyup.enter="openDetail(item)">
        <img
          v-if="imgOk(item)"
          :src="resolveImageUrl(item.image_url)"
          :alt="item.name"
          class="trend-row__img"
          loading="lazy"
          @error="onImgError(item.spot_id)"
        />
        <span v-else class="trend-row__img trend-row__img--fallback">{{ item.name.slice(0, 1) }}</span>
      </div>

      <div class="trend-row__main">
        <div class="trend-row__title-row">
          <h3 class="trend-row__name" role="button" tabindex="0" @click="openDetail(item)" @keyup.enter="openDetail(item)">
            {{ item.name }}
          </h3>
          <span class="trend-row__city">{{ item.city }}</span>
          <span :class="['trend-tag', trendOf(item).cls]">{{ trendOf(item).label }}</span>
        </div>

        <p class="trend-row__hot">
          <template v-if="item.sample_hidden">
            近 {{ props.windowDays }} 天有人规划过
          </template>
          <template v-else>
            近 {{ props.windowDays }} 天有 <b class="font-num" :title="SAMPLE_NOTE">{{ item.planning_users }}</b> 人规划过
          </template>
          <span v-if="item.saved_trip_count > 0" class="trend-row__sub">
            · 已保存 {{ item.saved_trip_count }} 份行程
          </span>
          <span v-if="item.favorite_count > 0" class="trend-row__sub">
            · 被收藏 {{ item.favorite_count }} 次
          </span>
        </p>
      </div>

      <div class="trend-row__actions">
        <button type="button" class="tact tact--plan" @click="addToPlan(item)">✚ 加入行程</button>
        <button
          type="button"
          :class="['tact', collected[item.spot_id] ? 'tact--fav-on' : 'tact--fav']"
          :disabled="favBusy[item.spot_id]"
          @click="toggleFavorite(item)"
        >
          {{ favBusy[item.spot_id] ? "..." : collected[item.spot_id] ? "♥ 已收藏" : "♡ 收藏" }}
        </button>
        <button type="button" class="tact tact--detail" @click="openDetail(item)">查看详情 ›</button>
      </div>
    </article>
  </div>
</template>

<style scoped>
.trend-list {
  background: var(--surface-white);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.trend-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-soft);
  transition: background 0.18s var(--ease);
}
.trend-row:last-child {
  border-bottom: none;
}
.trend-row:hover {
  background: var(--surface-tint);
}

/* 名次 */
.trend-row__rank {
  flex-shrink: 0;
  width: 26px;
  text-align: center;
  font-size: 17px;
  font-weight: 700;
  color: var(--text-muted);
}
.rank--1 { color: #c98a2d; }
.rank--2 { color: #8c9a95; }
.rank--3 { color: #b9855a; }

/* 缩略图 */
.trend-row__thumb {
  flex-shrink: 0;
  width: 56px;
  height: 56px;
  border-radius: var(--radius-sm);
  overflow: hidden;
  cursor: pointer;
}

.trend-row__img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.trend-row__img--fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, rgba(47, 119, 112, 0.1), rgba(230, 184, 92, 0.09));
  font-size: 22px;
  font-weight: 700;
  color: rgba(47, 119, 112, 0.35);
}

/* 主体 */
.trend-row__main {
  flex: 1;
  min-width: 0;
}

.trend-row__title-row {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 8px;
}

.trend-row__name {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 42vw;
}
.trend-row__name:hover {
  color: var(--brand-teal);
}

.trend-row__city {
  font-size: 12px;
  color: var(--text-muted);
  flex-shrink: 0;
}

.trend-tag {
  flex-shrink: 0;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
}
.trend--up {
  background: rgba(217, 119, 93, 0.12);
  color: var(--brand-coral);
}
.trend--down {
  background: rgba(47, 119, 112, 0.1);
  color: var(--brand-teal);
}
.trend--flat {
  background: rgba(23, 33, 31, 0.06);
  color: var(--text-muted);
}

.trend-row__hot {
  margin: 5px 0 0;
  font-size: 12.5px;
  color: var(--text-secondary);
  line-height: 1.5;
}
.trend-row__hot b {
  color: var(--brand-coral);
  font-size: 14px;
  font-weight: 700;
}

.trend-row__sub {
  color: var(--text-muted);
  margin-left: 4px;
}

/* 操作条 */
.trend-row__actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 6px;
}

.tact {
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  padding: 5px 10px;
  font-size: 12px;
  background: var(--surface-white);
  color: var(--text-secondary);
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.15s ease;
}
.tact:active { transform: scale(0.96); }
.tact:disabled { opacity: 0.6; cursor: default; }

.tact--plan { color: var(--brand-teal); border-color: rgba(47, 119, 112, 0.35); }
.tact--fav { color: var(--brand-coral); }
.tact--fav-on {
  color: var(--brand-coral);
  background: rgba(217, 119, 93, 0.08);
  border-color: rgba(217, 119, 93, 0.3);
}
.tact--detail { color: var(--text-muted); }

/* 窄屏：操作条换行到下一行 */
@media (max-width: 720px) {
  .trend-row {
    flex-wrap: wrap;
  }
  .trend-row__main {
    flex-basis: calc(100% - 100px);
  }
  .trend-row__actions {
    flex-basis: 100%;
    justify-content: flex-end;
    margin-top: 4px;
  }
  .trend-row__name {
    max-width: none;
  }
}
</style>
