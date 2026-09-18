<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";

import type { PostItem } from "../../types";
import { getReviewPosts } from "../../services/api";

/** 内容审核中心（设计方案 §3.2：独立管理页，不套普通用户详情/点赞收藏等互动语义） */
const router = useRouter();
const posts = ref<PostItem[]>([]);
const total = ref(0);
const loading = ref(true);

function openPost(p: PostItem) {
  void router.push({ name: "admin-post-review", params: { id: String(p.id) } });
}

onMounted(async () => {
  try {
    const resp = await getReviewPosts("PENDING_REVIEW", 1, 50);
    posts.value = resp.items;
    total.value = resp.total;
  } catch {
    posts.value = [];
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <div class="ad-card">
    <p class="ad-title">内容审核 · 待审核帖子（{{ total }}）</p>
    <p class="ad-sub">
      点击条目进入审核工作区 —— 查看正文、质量分/低质规则、作者与举报上下文后，再做通过/拒绝决策。
    </p>
    <div v-if="loading" class="ad-empty">加载中…</div>
    <div v-else-if="posts.length === 0" class="ad-empty">🎉 没有待审核的帖子</div>
    <div v-else class="ad-list">
      <div v-for="p in posts" :key="p.id" class="ad-item">
        <div class="ad-item__main" role="button" tabindex="0" @click="openPost(p)">
          <p class="ad-item__title">
            {{ p.title }}
            <span v-if="p.has_pending_revision" class="ad-badge ad-badge--warn">
              修改审核中 · v{{ p.pending_revision_no ?? 2 }}
            </span>
            <span v-if="p.low_quality" class="ad-badge ad-badge--no">低质</span>
            <span v-if="p.quality_score != null" class="ad-badge ad-badge--info">{{ p.quality_score }} 分</span>
            <span class="ad-badge ad-badge--muted">{{ p.post_type }}</span>
          </p>
          <p class="ad-item__meta">
            作者 @{{ p.author?.nickname || p.author?.id }} · {{ p.city || "未填城市" }} ·
            <template v-if="p.has_pending_revision">
              已发布内容 · 提交修改 v{{ p.pending_revision_no ?? 2 }} 待审（线上版本不变）
            </template>
            <template v-else>创建于 {{ (p.created_at || "").slice(0, 16) }}</template>
          </p>
        </div>
        <div class="ad-ops">
          <button type="button" class="ad-btn ad-btn--ok" @click="openPost(p)">进入审核 ›</button>
        </div>
      </div>
    </div>
  </div>
</template>
