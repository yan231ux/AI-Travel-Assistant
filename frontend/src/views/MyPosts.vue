<script setup lang="ts">
import { onMounted, ref } from "vue";
import { message } from "ant-design-vue";
import { useRouter } from "vue-router";

import PostCard from "../components/PostCard.vue";
import { postStatusLabel } from "../constants/postMeta";
import { deletePost, getMyPosts, submitPost } from "../services/api";
import type { PostItem } from "../types";

/**
 * 我的帖子（阶段二 /my-posts）：全部状态管理 —— 草稿可编辑/提交，待审等结果，
 * 被拒可查看原因并修改后重提，已发布可编辑/删除。
 */
const router = useRouter();

const items = ref<PostItem[]>([]);
const total = ref(0);
const loading = ref(true);
const error = ref("");
const busyId = ref<number | null>(null);

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const resp = await getMyPosts(1, 50);
    items.value = resp.items;
    total.value = resp.total;
  } catch {
    error.value = "加载失败，请稍后重试。";
  } finally {
    loading.value = false;
  }
}

function goCreate() {
  void router.push({ name: "post-create" });
}

function goEdit(p: PostItem) {
  void router.push({ name: "post-edit", params: { id: String(p.id) } });
}

function goDetail(p: PostItem) {
  void router.push({ name: "post-detail", params: { id: String(p.id) } });
}

async function submit(p: PostItem) {
  busyId.value = p.id;
  try {
    const resp = await submitPost(p.id);
    if (!resp.success) {
      message.error(resp.message || "提交被拦截");
      return;
    }
    message.success("已提交审核");
    await load();
  } catch {
    message.error("提交失败，请稍后重试。");
  } finally {
    busyId.value = null;
  }
}

async function remove(p: PostItem) {
  if (!window.confirm(`确定删除「${p.title}」吗？删除后不可恢复。`)) return;
  busyId.value = p.id;
  try {
    await deletePost(p.id);
    message.success("已删除");
    await load();
  } catch {
    message.error("删除失败");
  } finally {
    busyId.value = null;
  }
}

onMounted(() => void load());
</script>

<template>
  <section class="mp-page">
    <div class="mp-head">
      <div>
        <h2 class="mp-head__title">📝 我的帖子</h2>
        <p class="mp-head__desc">共 {{ total }} 篇 · 草稿与未通过的内容只有自己可见</p>
      </div>
      <button type="button" class="btn btn--primary" @click="goCreate">＋ 发帖</button>
    </div>

    <div v-if="loading" class="mp-empty">加载中...</div>
    <div v-else-if="error" class="mp-empty">{{ error }}</div>
    <div v-else-if="items.length === 0" class="mp-empty">
      <p style="margin: 0 0 12px;">还没有发过帖子</p>
      <button type="button" class="btn btn--primary" @click="goCreate">写第一篇</button>
    </div>

    <div v-else class="mp-list">
      <article v-for="p in items" :key="p.id" class="mp-card">
        <div class="mp-row">
          <PostCard :post="p" class="mp-card__inner" @changed="load" />
        </div>
        <div class="mp-ops">
          <span :class="['mp-status', 'mp-status--' + p.status.toLowerCase()]">{{ postStatusLabel(p.status) }}</span>
          <span v-if="p.reject_reason" class="mp-reject" :title="p.reject_reason">未通过：{{ p.reject_reason }}</span>
          <div class="mp-ops__btns">
            <button
              v-if="['DRAFT', 'REJECTED'].includes(p.status)"
              type="button"
              class="btn"
              :disabled="busyId === p.id"
              @click="submit(p)"
            >
              提交审核
            </button>
            <button
              v-if="['DRAFT', 'REJECTED', 'PUBLISHED'].includes(p.status)"
              type="button"
              class="btn"
              @click="goEdit(p)"
            >
              编辑
            </button>
            <button type="button" class="btn btn--danger" @click="goDetail(p)">查看</button>
            <button type="button" class="btn btn--danger" :disabled="busyId === p.id" @click="remove(p)">删除</button>
          </div>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.mp-page {
  max-width: 960px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.mp-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.mp-head__title {
  margin: 0;
  font-size: 20px;
  color: var(--text-primary);
}
.mp-head__desc {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--text-muted);
}
.mp-empty {
  text-align: center;
  padding: 48px 0;
  color: var(--text-muted);
}
.mp-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.mp-card {
  background: #fff;
  border-radius: 16px;
  padding: 10px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}
.mp-ops {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px 2px;
  flex-wrap: wrap;
}
.mp-status {
  font-size: 12px;
  font-weight: 600;
  padding: 2px 10px;
  border-radius: 999px;
}
.mp-status--draft {
  background: #rgba(23, 33, 31, 0.05);
  color: var(--text-secondary);
}
.mp-status--pending_review {
  background: #rgba(201, 138, 45, 0.12);
  color: var(--warning);
}
.mp-status--published {
  background: #rgba(60, 140, 112, 0.1);
  color: var(--success);
}
.mp-status--rejected {
  background: #rgba(198, 93, 81, 0.08);
  color: var(--danger);
}
.mp-status--hidden {
  background: #rgba(23, 33, 31, 0.05);
  color: var(--text-secondary);
}
.mp-reject {
  font-size: 12px;
  color: var(--danger);
}
.mp-ops__btns {
  margin-left: auto;
  display: flex;
  gap: 8px;
}
.btn {
  border: none;
  border-radius: 10px;
  padding: 6px 14px;
  font-size: 13px;
  font-weight: 500;
  background: rgba(0, 0, 0, 0.05);
  color: var(--text-secondary);
  cursor: pointer;
}
.btn--primary {
  background: var(--brand-teal);
  color: #fff;
}
.btn--danger {
  color: var(--text-secondary);
}
.btn:disabled {
  opacity: 0.6;
}
</style>
