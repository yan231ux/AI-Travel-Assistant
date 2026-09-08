<script setup lang="ts">
import { onMounted, ref } from "vue";
import { message } from "ant-design-vue";
import { useRouter } from "vue-router";

import { listFavorites, unfavoriteSpot } from "../services/api";
import type { SpotFavorite } from "../types";

/**
 * 我的收藏页（产品化阶段一 /favorites）。
 *
 * 数据源 GET /user/spot-favorites（收藏快照字段直出）：图片/名称/城市为收藏时刻快照；
 * 点卡片进详情 /spots/:id；取消收藏幂等，本地立即移除。
 */
const router = useRouter();

const items = ref<SpotFavorite[]>([]);
const total = ref(0);
const page = ref(1);
const PAGE_SIZE = 12;
const loading = ref(true);
const loadingMore = ref(false);
const error = ref("");
const removing = ref<string>("");
const brokenImgs = ref<Set<string>>(new Set()); // 缩略图 URL 加载失败（404）的卡片 → 降级首字占位

function markImgBroken(spotId: string) {
  if (brokenImgs.value.has(spotId)) return;
  brokenImgs.value = new Set(brokenImgs.value).add(spotId);
}

function imgOk(item: SpotFavorite) {
  return !!item.image_url && !brokenImgs.value.has(item.spot_id);
}

async function fetchPage(first: boolean) {
  if (first) {
    loading.value = true;
  } else {
    loadingMore.value = true;
  }
  error.value = "";
  try {
    const resp = await listFavorites(first ? 1 : page.value + 1, PAGE_SIZE);
    page.value = first ? 1 : page.value + 1;
    total.value = resp.total;
    items.value = first ? resp.items : [...items.value, ...resp.items];
  } catch {
    error.value = "收藏列表加载失败，请稍后重试。";
  } finally {
    loading.value = false;
    loadingMore.value = false;
  }
}

async function loadMore() {
  if (loading.value || loadingMore.value) return;
  if (items.value.length >= total.value) return;
  await fetchPage(false);
}

async function remove(item: SpotFavorite) {
  if (removing.value) return;
  removing.value = item.spot_id;
  try {
    await unfavoriteSpot(item.spot_id);
    items.value = items.value.filter((x) => x.spot_id !== item.spot_id);
    if (brokenImgs.value.has(item.spot_id)) {
      const next = new Set(brokenImgs.value);
      next.delete(item.spot_id);
      brokenImgs.value = next;
    }
    total.value = Math.max(0, total.value - 1);
    message.success(`已取消收藏「${item.name}」`);
  } catch {
    message.error("操作失败，请稍后重试。");
  } finally {
    removing.value = "";
  }
}

function openDetail(item: SpotFavorite) {
  void router.push({ name: "spot-detail", params: { id: item.spot_id } });
}

function goDiscover() {
  void router.push({ name: "recommendations" });
}

const hasMore = () => items.value.length < total.value;

onMounted(() => {
  void fetchPage(true);
});

function skeletons(n: number) {
  return new Array(n).fill(0).map((_, i) => i);
}
</script>

<template>
  <section class="fav-page">
    <div class="fav-head">
      <div>
        <h2 class="fav-head__title">💛 我的收藏</h2>
        <p v-if="!loading" class="fav-head__desc">共 {{ total }} 个收藏景点</p>
      </div>
    </div>

    <div v-if="loading" class="fav-grid">
      <div v-for="i in skeletons(6)" :key="i" class="skel" />
    </div>

    <div v-else-if="error" class="state-box">{{ error }}</div>

    <div v-else-if="items.length === 0" class="state-box">
      <p style="margin: 0 0 12px;">还没有收藏任何景点</p>
      <p class="state-box__hint">在首页或发现页点「♡ 收藏」，之后可以在这里快速找回</p>
      <button type="button" class="state-box__btn" @click="goDiscover">去发现景点</button>
    </div>

    <template v-else>
      <div class="fav-grid">
        <article
          v-for="item in items"
          :key="item.spot_id"
          class="fav-card"
          role="button"
          tabindex="0"
          @click="openDetail(item)"
          @keyup.enter="openDetail(item)"
        >
          <div class="fav-card__img-wrap">
            <img
              v-if="imgOk(item)"
              :src="item.image_url ?? undefined"
              :alt="item.name"
              class="fav-card__img"
              @error="markImgBroken(item.spot_id)"
            />
            <div v-else class="fav-card__img fav-card__img--fallback">{{ item.name.slice(0, 1) }}</div>
            <span class="fav-card__city">{{ item.city }}</span>
          </div>
          <div class="fav-card__body">
            <h3 class="fav-card__name">{{ item.name }}</h3>
            <p class="fav-card__date">
              {{ item.created_at ? `收藏于 ${String(item.created_at).slice(0, 10)}` : "已收藏" }}
            </p>
          </div>
          <button
            type="button"
            class="fav-card__remove"
            :disabled="removing === item.spot_id"
            @click.stop="remove(item)"
          >
            {{ removing === item.spot_id ? "..." : "取消收藏" }}
          </button>
        </article>
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
.fav-page {
  display: grid;
  gap: 14px;
}

.fav-head__title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
}

.fav-head__desc {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--text-muted);
}

.fav-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
  gap: 14px;
}

.fav-card {
  display: flex;
  flex-direction: column;
  background: var(--surface-white);
  border-radius: 14px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  cursor: pointer;
  transition: box-shadow 0.2s ease;
}

.fav-card:hover {
  box-shadow: 0 3px 12px rgba(0, 0, 0, 0.12);
}

.fav-card__img-wrap {
  position: relative;
  height: 108px;
}

.fav-card__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.fav-card__img--fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #rgba(217, 119, 93, 0.1), #rgba(201, 138, 45, 0.08));
  font-size: 34px;
  font-weight: 700;
  color: #rgba(217, 119, 93, 0.3);
}

.fav-card__city {
  position: absolute;
  top: 8px;
  right: 8px;
  padding: 2px 8px;
  border-radius: 6px;
  background: rgba(0, 0, 0, 0.45);
  color: var(--surface-white);
  font-size: 11px;
}

.fav-card__body {
  padding: 10px 12px 4px;
}

.fav-card__name {
  margin: 0;
  font-size: 14.5px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fav-card__date {
  margin: 4px 0 0;
  font-size: 11.5px;
  color: var(--text-muted);
}

.fav-card__remove {
  margin: 8px 12px 12px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  padding: 6px 0;
  background: var(--surface-white);
  color: var(--brand-coral);
  font-size: 12.5px;
  cursor: pointer;
}

.fav-card__remove:active { transform: scale(0.97); }
.fav-card__remove:disabled { opacity: 0.6; }

.skel {
  height: 210px;
  border-radius: 14px;
  background: linear-gradient(100deg, #rgba(23, 33, 31, 0.04) 40%, #rgba(23, 33, 31, 0.02) 50%, #rgba(23, 33, 31, 0.04) 60%);
  background-size: 200% 100%;
  animation: shimmer 1.2s infinite;
}

@keyframes shimmer {
  from { background-position: 120% 0; }
  to { background-position: -80% 0; }
}

.state-box {
  padding: 56px 20px;
  text-align: center;
  border-radius: 14px;
  background: var(--surface-white);
  color: var(--text-muted);
  font-size: 14px;
}

.state-box__hint {
  margin: 0 0 16px;
  font-size: 13px;
}

.state-box__btn {
  border: none;
  border-radius: 10px;
  padding: 9px 22px;
  background: var(--brand-teal);
  color: var(--surface-white);
  font-size: 14px;
  cursor: pointer;
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

.load-more__btn:disabled { opacity: 0.6; }

.load-more__end {
  color: #rgba(23, 33, 31, 0.18);
  font-size: 13px;
}
</style>
