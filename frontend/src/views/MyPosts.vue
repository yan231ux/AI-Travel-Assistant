<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { message } from "ant-design-vue";
import { useRouter } from "vue-router";

import PostCard from "../components/PostCard.vue";
import { postStatusLabel } from "../constants/postMeta";
import {
  deletePost,
  getMyPostModerationStatus,
  getMyPosts,
  submitPost,
} from "../services/api";
import type { MyPostModerationStatus } from "../services/api";
import type { PostItem } from "../types";

/**
 * 我的帖子（阶段二 /my-posts）：全部状态管理 —— 草稿可编辑/提交，待审等结果，
 * 被拒可查看原因并修改后重提，已发布可编辑/删除。
 *
 * 实时刷新：提交审核后 AI 异步处理（秒级~几十秒），页面停留期间若仍有「审核中」的帖子，
 * 自动轮询刷新列表，审核结果出来即自动更新为「已发布 / 未通过」，无需手动刷新。
 *
 * 细分审核状态：每个 PENDING_REVIEW 帖子的最新一条审核任务，会进一步展示成
 *   「AI 初筛中 / 待人工复核 / 已自动放行 / AI 失败」并附原因，给作者明确反馈。
 */
const router = useRouter();

const items = ref<PostItem[]>([]);
const total = ref(0);
const loading = ref(true);
const error = ref("");
const busyId = ref<number | null>(null);

/** postId → 细分审核状态（仅 PENDING_REVIEW 帖子的最新一条任务） */
const subStatus = ref<Record<number, MyPostModerationStatus | null>>({});

/** 是否存在仍在审核中的帖子（用于驱动轮询 + 顶部提示） */
const hasPending = computed(() => items.value.some((p) => p.status === "PENDING_REVIEW"));
let pollTimer: ReturnType<typeof setInterval> | null = null;

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

/** 取所有 PENDING_REVIEW 帖子的最新一条审核任务状态（并行） */
async function loadSubStatus() {
  const pendings = items.value.filter((p) => p.status === "PENDING_REVIEW");
  if (pendings.length === 0) {
    subStatus.value = {};
    return;
  }
  const results = await Promise.allSettled(
    pendings.map((p) => getMyPostModerationStatus(p.id))
  );
  const next: Record<number, MyPostModerationStatus | null> = { ...subStatus.value };
  pendings.forEach((p, i) => {
    const r = results[i];
    next[p.id] = r.status === "fulfilled" ? r.value : null;
  });
  subStatus.value = next;
}

function pollTick() {
  void load().then(() => {
    void loadSubStatus();
  });
}

/** 有审核中的帖子 → 轮询；没有了 → 停表（省请求，也避免空转） */
function syncPolling() {
  if (hasPending.value && !pollTimer) {
    pollTimer = setInterval(pollTick, 5000);
  } else if (!hasPending.value && pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
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
    message.success("已提交审核，结果出来会自动更新");
    await load();
    await loadSubStatus();
    syncPolling();
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

/** 把审核任务状态翻译成中文标签 + 副文案 */
function subStatusText(p: PostItem): { label: string; sub: string } | null {
  if (p.status !== "PENDING_REVIEW") return null;
  const s = subStatus.value[p.id];
  if (!s) return { label: "AI 初筛中", sub: "排队/正在分析，请稍候…" };
  switch (s.status) {
    case "PENDING":
    case "RUNNING":
      return { label: "AI 初筛中", sub: "正在分析内容，请稍候…" };
    case "REVIEW":
      return {
        label: "待人工复核",
        sub:
          (s.risk_level && (s.risk_level === "HIGH" || s.risk_level === "CRITICAL"))
            ? "高风险 / 命中规则，需管理员复核"
            : "需管理员复核",
      };
    case "FAILED":
      return {
        label: "AI 失败（已转人工）",
        sub: s.error_message || "AI 暂时不可用，已转入人工队列",
      };
    case "PASSED":
      // 任务 PASSED 不代表帖子已发布：自动放行才会落 PUBLISHED。
      return { label: "已自动放行", sub: "AI 判定通过，内容已上线" };
    default:
      return null;
  }
}

onMounted(() => {
  void load().then(async () => {
    await loadSubStatus();
    syncPolling();
  });
});

onBeforeUnmount(() => {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
});
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

    <div v-if="hasPending" class="mp-polling">
      🔄 有内容正在审核中（含修改稿），页面会自动刷新，结果出来后无需手动操作
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
          <span
            v-if="p.has_pending_revision"
            class="mp-rev"
            title="已发布内容的修改稿正在审核；审核期间线上仍展示原版本，通过后自动切换"
          >
            修改审核中 · v{{ p.pending_revision_no ?? 2 }}
          </span>
          <span v-if="p.reject_reason" class="mp-reject" :title="p.reject_reason">未通过：{{ p.reject_reason }}</span>
          <span
            v-if="subStatusText(p)"
            class="mp-sub"
            :class="'mp-sub--' + (subStatus[p.id]?.status || 'pending').toLowerCase()"
            :title="subStatusText(p)?.sub"
          >
            · {{ subStatusText(p)?.label }}：{{ subStatusText(p)?.sub }}
          </span>
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
.mp-polling {
  font-size: 13px;
  color: var(--warning, #c98a2d);
  background: rgba(201, 138, 45, 0.08);
  border: 1px dashed rgba(201, 138, 45, 0.35);
  border-radius: 10px;
  padding: 8px 14px;
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
  background: rgba(23, 33, 31, 0.05);
  color: var(--text-secondary);
}
.mp-status--pending_review {
  background: rgba(201, 138, 45, 0.12);
  color: var(--warning);
}
.mp-status--published {
  background: rgba(60, 140, 112, 0.1);
  color: var(--success);
}
.mp-status--rejected {
  background: rgba(198, 93, 81, 0.08);
  color: var(--danger);
}
.mp-status--hidden {
  background: rgba(23, 33, 31, 0.05);
  color: var(--text-secondary);
}
.mp-reject {
  font-size: 12px;
  color: var(--danger);
}
/* P1-1 版本化：已发布帖的待审修改版本标记 */
.mp-rev {
  font-size: 12px;
  font-weight: 600;
  color: var(--warning, #c98a2d);
  background: rgba(201, 138, 45, 0.12);
  border-radius: 999px;
  padding: 2px 10px;
  cursor: help;
}
.mp-sub {
  font-size: 12px;
  color: var(--text-secondary);
  background: rgba(0, 0, 0, 0.04);
  border-radius: 999px;
  padding: 1px 10px;
}
.mp-sub--pending,
.mp-sub--running {
  background: rgba(23, 59, 56, 0.08);
  color: var(--brand-deep, #1d4a45);
}
.mp-sub--review {
  background: rgba(201, 138, 45, 0.12);
  color: var(--warning, #c98a2d);
}
.mp-sub--failed {
  background: rgba(198, 93, 81, 0.1);
  color: var(--danger, #c65d51);
}
.mp-sub--passed {
  background: rgba(60, 140, 112, 0.12);
  color: var(--success, #2f7770);
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
