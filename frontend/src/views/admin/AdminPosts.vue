<script setup lang="ts">
import { onMounted, ref } from "vue";
import { message } from "ant-design-vue";
import { useRouter } from "vue-router";

import type { PostItem } from "../../types";
import { approvePost, getReviewPosts, hidePost } from "../../services/api";

/** 帖子治理（设计方案 §3.3：已发布可下架 / 已隐藏可恢复；批量语义仅在行级确认） */
const router = useRouter();
const seg = ref<"PUBLISHED" | "HIDDEN">("PUBLISHED");
const posts = ref<PostItem[]>([]);
const loading = ref(true);

async function load() {
  loading.value = true;
  try {
    const resp = await getReviewPosts(seg.value, 1, 100);
    posts.value = resp.items;
  } catch {
    posts.value = [];
  } finally {
    loading.value = false;
  }
}

function switchSeg(next: "PUBLISHED" | "HIDDEN") {
  seg.value = next;
  void load();
}

async function hide(p: PostItem) {
  if (!window.confirm(`确认下架「${p.title}」？下架后不在公开流展示，可随时恢复。`)) return;
  try {
    await hidePost(p.id);
    message.success("已下架");
    await load();
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败");
  }
}

async function restore(p: PostItem) {
  if (!window.confirm(`确认恢复「${p.title}」为公开？`)) return;
  try {
    await approvePost(p.id);
    message.success("已恢复公开");
    await load();
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败");
  }
}

function goDetail(p: PostItem) {
  void router.push({ name: "admin-post-review", params: { id: String(p.id) } });
}

onMounted(load);
</script>

<template>
  <div class="ad-card">
    <div class="ad-form-row" style="justify-content: space-between">
      <p class="ad-title">帖子治理</p>
      <div class="ad-seg">
        <button
          type="button"
          :class="['ad-seg__btn', { 'ad-seg__btn--on': seg === 'PUBLISHED' }]"
          @click="switchSeg('PUBLISHED')"
        >
          已发布
        </button>
        <button
          type="button"
          :class="['ad-seg__btn', { 'ad-seg__btn--on': seg === 'HIDDEN' }]"
          @click="switchSeg('HIDDEN')"
        >
          已隐藏
        </button>
      </div>
    </div>
    <p class="ad-sub">违规内容下架、误隐藏恢复；点标题可进入审核证据页复核。</p>

    <div v-if="loading" class="ad-empty">加载中…</div>
    <div v-else-if="posts.length === 0" class="ad-empty">
      {{ seg === "PUBLISHED" ? "当前没有已发布帖子" : "没有被隐藏的帖子" }}
    </div>
    <div v-else class="ad-list">
      <div v-for="p in posts" :key="p.id" class="ad-item">
        <div class="ad-item__main">
          <p class="ad-item__title" role="button" tabindex="0" @click="goDetail(p)">
            {{ p.title }}
            <span v-if="p.low_quality" class="ad-badge ad-badge--no">低质</span>
            <span v-if="p.quality_score != null" class="ad-badge ad-badge--info">{{ p.quality_score }} 分</span>
          </p>
          <p class="ad-item__meta">
            作者 @{{ p.author?.nickname || p.author?.id }} · ❤️{{ p.like_count || 0 }} ⭐{{ p.favorite_count || 0 }} 💬{{
              p.comment_count || 0
            }} · {{ (p.published_at || p.created_at || "").slice(0, 16) }}
          </p>
        </div>
        <div class="ad-ops">
          <button type="button" class="ad-btn" @click="goDetail(p)">证据 ›</button>
          <button v-if="seg === 'PUBLISHED'" type="button" class="ad-btn ad-btn--no" @click="hide(p)">下架</button>
          <button v-else type="button" class="ad-btn ad-btn--ok" @click="restore(p)">恢复公开</button>
        </div>
      </div>
    </div>
  </div>
</template>
