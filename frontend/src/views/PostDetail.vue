<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { message } from "ant-design-vue";
import { useRouter } from "vue-router";

import {
  addComment,
  createReport,
  deleteComment,
  deletePost,
  dislikePost,
  favoritePost,
  getComments,
  getPostDetail,
  likePost,
  resolveImageUrl,
  submitPost,
  undislikePost,
  unfavoritePost,
  unlikePost,
} from "../services/api";
import { postTypeLabel } from "../constants/postMeta";
import type { CommentItem, PostDetail as PostDetailType, PostSpotRef } from "../types";

/**
 * 帖子详情页（阶段二 /community/posts/:id）。
 * 公开帖所有人可见；本人可编辑/删除/提交审核；互动（点赞/收藏/不喜欢）幂等；
 * 评论区（发表/删除自己的评论）；举报入口（广告/虚假信息/辱骂/侵权/其他）。
 */
const props = defineProps<{ id: string }>();
const router = useRouter();

/** 点作者名 → 用户旅行主页 */
function goAuthor() {
  if (!detail.value?.author?.id) return;
  void router.push({ name: "user-home", params: { id: detail.value.author.id } });
}

const detail = ref<PostDetailType | null>(null);
const loading = ref(true);
const error = ref("");

/* 封面图：相对路径拼 API_BASE_URL；加载失败降级首字占位 */
const coverBroken = ref(false);
const coverSrc = computed(() => resolveImageUrl(detail.value?.cover_image ?? null));

/* 互动状态（以详情接口返回为准，操作后以后端返回为准） */
const liked = ref(false);
const favorited = ref(false);
const disliked = ref(false);
const likeCount = ref(0);
const favoriteCount = ref(0);
const busy = ref(false);

const REPORT_REASONS = ["广告", "虚假信息", "辱骂", "侵权", "其他"];

const mine = computed(() => !!detail.value?.mine);
const canEdit = computed(
  () => mine.value && ["DRAFT", "REJECTED", "PUBLISHED"].includes(detail.value?.status || "")
);
const canSubmit = computed(
  () => mine.value && ["DRAFT", "REJECTED"].includes(detail.value?.status || "")
);

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const idNum = Number(props.id);
    const d = await getPostDetail(idNum);
    detail.value = d;
    coverBroken.value = false;
    liked.value = !!d.liked;
    favorited.value = !!d.favorited;
    likeCount.value = d.like_count || 0;
    favoriteCount.value = d.favorite_count || 0;
    await loadComments();
  } catch {
    error.value = "帖子不存在或未公开。";
  } finally {
    loading.value = false;
  }
}

/* ---------- 互动 ---------- */
async function runAction(fn: () => Promise<{ liked?: boolean; favorited?: boolean; disliked?: boolean; likeCount?: number; favoriteCount?: number }>) {
  if (busy.value) return;
  busy.value = true;
  try {
    const r = await fn();
    if (r.liked !== undefined) liked.value = r.liked;
    if (r.favorited !== undefined) favorited.value = r.favorited;
    if (r.disliked !== undefined) disliked.value = r.disliked;
    if (r.likeCount !== undefined) likeCount.value = r.likeCount;
    if (r.favoriteCount !== undefined) favoriteCount.value = r.favoriteCount;
  } catch {
    message.error("操作失败，请稍后重试。");
  } finally {
    busy.value = false;
  }
}

function toggleLike() {
  const id = detail.value!.id;
  void runAction(() => (liked.value ? unlikePost(id) : likePost(id)));
}
function toggleFavorite() {
  const id = detail.value!.id;
  void runAction(() => (favorited.value ? unfavoritePost(id) : favoritePost(id)));
}
function toggleDislike() {
  const id = detail.value!.id;
  void runAction(() => (disliked.value ? undislikePost(id) : dislikePost(id)));
}

/* ---------- 评论 ---------- */
const comments = ref<CommentItem[]>([]);
const commentTotal = ref(0);
const commentText = ref("");
const submittingComment = ref(false);

async function loadComments() {
  try {
    const page = await getComments(Number(props.id), 1, 50);
    comments.value = page.items;
    commentTotal.value = page.total;
  } catch {
    comments.value = [];
    commentTotal.value = 0;
  }
}

async function sendComment() {
  const text = commentText.value.trim();
  if (!text) return message.warning("先写点什么再发送");
  submittingComment.value = true;
  try {
    await addComment(Number(props.id), text);
    commentText.value = "";
    message.success("评论已发布");
    await loadComments();
  } catch {
    message.error("评论发送失败，请稍后重试。");
  } finally {
    submittingComment.value = false;
  }
}

async function removeComment(c: CommentItem) {
  try {
    await deleteComment(c.id);
    message.success("评论已删除");
    await loadComments();
  } catch {
    message.error("删除失败");
  }
}

/* ---------- 作者操作 / 举报 ---------- */
async function submitForReview() {
  if (!detail.value) return;
  try {
    const resp = await submitPost(detail.value.id);
    if (!resp.success) {
      message.error(resp.message || "提交被拦截");
      return;
    }
    message.success("已提交审核");
    await load();
  } catch {
    message.error("提交失败，请稍后重试。");
  }
}

async function removePost() {
  if (!detail.value) return;
  try {
    await deletePost(detail.value.id);
    message.success("已删除");
    void router.replace({ name: "community" });
  } catch {
    message.error("删除失败，请稍后重试。");
  }
}

function goEdit() {
  if (!detail.value) return;
  void router.push({ name: "post-edit", params: { id: String(detail.value.id) } });
}

const reporting = ref(false);
const reportReason = ref("");
function startReport() {
  reportReason.value = "";
  reporting.value = true;
}
async function sendReport() {
  if (!detail.value || !reportReason.value) return;
  try {
    const resp = await createReport({
      target_type: "POST",
      target_id: detail.value.id,
      reason: reportReason.value,
    });
    reporting.value = false;
    message.success(resp.existed ? "你已举报过该内容，我们会尽快处理" : "举报已提交，感谢反馈");
  } catch {
    message.error("举报提交失败");
  }
}

function goSpot(ref: PostSpotRef) {
  if (ref.spot_id) {
    void router.push({ name: "spot-detail", params: { id: ref.spot_id } });
  }
}

onMounted(() => void load());
</script>

<template>
  <section class="pd-page">
    <div v-if="loading" class="pd-state">正在加载帖子...</div>
    <div v-else-if="error" class="pd-state">
      <p>{{ error }}</p>
      <button type="button" class="btn" @click="load">重新加载</button>
    </div>

    <template v-else-if="detail">
      <article class="pd-card">
        <div v-if="detail.cover_image" class="pd-cover">
          <img
            v-if="!coverBroken"
            :src="coverSrc"
            :alt="detail.title"
            class="pd-cover__img"
            @error="coverBroken = true"
          />
          <div v-else class="pd-cover__fb">{{ detail.title.slice(0, 1) }}</div>
        </div>
        <div class="pd-body">
          <div class="pd-head">
            <span class="pd-type">{{ postTypeLabel(detail.post_type) }}</span>
            <span v-if="detail.city" class="pd-city">📍 {{ detail.city }}</span>
            <span v-if="detail.status !== 'PUBLISHED'" class="pd-status">{{ detail.status }}</span>
            <span v-if="detail.reject_reason" class="pd-reject">拒绝原因：{{ detail.reject_reason }}</span>
          </div>
          <h1 class="pd-title">{{ detail.title }}</h1>
          <p class="pd-author">
            <span
              class="pd-author__link"
              role="button"
              tabindex="0"
              @click="goAuthor"
              @keyup.enter="goAuthor"
            >作者 @{{ detail.author?.nickname || detail.author?.id }}</span>
            · {{ detail.published_at || detail.created_at || "" }}
            · 浏览 {{ detail.view_count || 0 }}
          </p>

          <p v-if="detail.summary" class="pd-summary">{{ detail.summary }}</p>
          <div class="pd-content">{{ detail.content }}</div>

          <!-- 关联景点 -->
          <div v-if="detail.spots && detail.spots.length" class="pd-spots">
            <p class="pd-sec-title">📍 文中提到的景点</p>
            <div class="pd-spots__list">
              <button
                v-for="s in detail.spots"
                :key="s.spot_name"
                type="button"
                class="pd-spot-chip"
                @click="goSpot(s)"
              >
                {{ s.spot_name }}
              </button>
            </div>
          </div>

          <!-- 作者操作 -->
          <div v-if="mine" class="pd-owner-actions">
            <button v-if="canSubmit" type="button" class="btn btn--primary" @click="submitForReview">提交审核</button>
            <button v-if="canEdit" type="button" class="btn" @click="goEdit">编辑</button>
            <button type="button" class="btn btn--danger" @click="removePost">删除</button>
          </div>

          <!-- 互动条 -->
          <div class="pd-actions">
            <button
              type="button"
              :class="['act', liked ? 'act--like' : '']"
              :disabled="busy"
              @click="toggleLike"
            >
              {{ liked ? "♥" : "♡" }} {{ likeCount }}
            </button>
            <button
              type="button"
              :class="['act', favorited ? 'act--fav' : '']"
              :disabled="busy"
              @click="toggleFavorite"
            >
              {{ favorited ? "★" : "☆" }} {{ favoriteCount }}
            </button>
            <button
              type="button"
              :class="['act', disliked ? 'act--dis' : '']"
              :disabled="busy"
              @click="toggleDislike"
            >
              {{ disliked ? "✕ 已不感兴趣" : "✕ 不感兴趣" }}
            </button>
            <button type="button" class="act act--report" @click="startReport">🚩 举报</button>
          </div>
        </div>
      </article>

      <!-- 举报面板 -->
      <div v-if="reporting" class="pd-report">
        <p class="pd-report__title">举报这篇帖子</p>
        <div class="pd-report__chips">
          <button
            v-for="r in REPORT_REASONS"
            :key="r"
            type="button"
            :class="['chip', { 'chip--on': reportReason === r }]"
            @click="reportReason = r"
          >
            {{ r }}
          </button>
        </div>
        <div class="pd-report__ops">
          <button type="button" class="btn" @click="reporting = false">取消</button>
          <button type="button" class="btn btn--primary" :disabled="!reportReason" @click="sendReport">提交举报</button>
        </div>
      </div>

      <!-- 评论区 -->
      <section class="pd-comments">
        <div class="pd-sec-title">💬 评论（{{ commentTotal }}）</div>
        <div class="cmt-input-row">
          <input
            v-model="commentText"
            class="cmt-input"
            maxlength="1000"
            placeholder="说点什么…（友善评论）"
            @keyup.enter="sendComment"
          />
          <button type="button" class="btn btn--primary" :disabled="submittingComment" @click="sendComment">
            {{ submittingComment ? "发送中..." : "发送" }}
          </button>
        </div>
        <div v-if="comments.length === 0" class="cmt-empty">还没有评论，来抢沙发～</div>
        <ul v-else class="cmt-list">
          <li v-for="c in comments" :key="c.id" class="cmt-item">
            <div class="cmt-item__head">
              <span class="cmt-item__name">{{ c.deleted ? "系统" : c.author?.nickname || c.author?.id }}</span>
              <span class="cmt-item__time">{{ (c.created_at || "").slice(0, 16) }}</span>
              <button v-if="c.mine" type="button" class="cmt-item__del" @click="removeComment(c)">删除</button>
            </div>
            <p class="cmt-item__content">{{ c.content }}</p>
          </li>
        </ul>
      </section>
    </template>
  </section>
</template>

<style scoped>
.pd-page {
  max-width: 860px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.pd-state {
  text-align: center;
  padding: 48px 16px;
  color: var(--text-muted);
}
.pd-card {
  background: #fff;
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}
.pd-cover__img {
  width: 100%;
  max-height: 320px;
  object-fit: cover;
}
.pd-cover__fb {
  width: 100%;
  max-height: 320px;
  min-height: 140px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 64px;
  font-weight: 700;
  color: #rgba(47, 119, 112, 0.35);
  background: linear-gradient(135deg, #rgba(47, 119, 112, 0.06), #rgba(23, 33, 31, 0.03));
}
.pd-body {
  padding: 18px 20px 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.pd-head {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  flex-wrap: wrap;
}
.pd-type {
  background: rgba(47, 119, 112, 0.1);
  color: var(--brand-teal);
  padding: 3px 10px;
  border-radius: 999px;
  font-weight: 600;
}
.pd-city {
  color: var(--text-secondary);
}
.pd-status {
  background: #rgba(201, 138, 45, 0.12);
  color: var(--warning);
  padding: 2px 8px;
  border-radius: 999px;
}
.pd-reject {
  color: var(--danger);
}
.pd-title {
  font-size: 22px;
  margin: 0;
  color: var(--text-primary);
  line-height: 1.35;
}
.pd-author {
  font-size: 12px;
  color: var(--text-muted);
  margin: 0;
}

.pd-author__link {
  color: var(--brand-teal);
  font-weight: 500;
  cursor: pointer;
}

.pd-author__link:hover {
  text-decoration: underline;
}
.pd-summary {
  color: var(--text-secondary);
  font-size: 14px;
  margin: 0;
  background: rgba(0, 0, 0, 0.03);
  border-radius: 10px;
  padding: 10px 12px;
}
.pd-content {
  white-space: pre-wrap;
  line-height: 1.8;
  font-size: 15px;
  color: var(--text-primary);
}
.pd-sec-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 8px;
}
.pd-spots__list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.pd-spot-chip {
  border: 1px solid rgba(47, 119, 112, 0.25);
  background: rgba(47, 119, 112, 0.06);
  color: var(--brand-teal);
  border-radius: 999px;
  padding: 5px 12px;
  font-size: 13px;
  cursor: pointer;
}
.pd-owner-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.pd-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  padding-top: 4px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
}
.act {
  border: none;
  border-radius: 10px;
  padding: 7px 16px;
  font-size: 14px;
  background: rgba(0, 0, 0, 0.05);
  color: var(--text-secondary);
  cursor: pointer;
}
.act--like {
  background: rgba(198, 93, 81, 0.1);
  color: var(--danger);
}
.act--fav {
  background: rgba(255, 159, 10, 0.14);
  color: var(--warning);
}
.act--dis {
  background: rgba(0, 0, 0, 0.08);
  color: var(--text-secondary);
}
.act--report {
  margin-left: auto;
}
.btn {
  border: none;
  border-radius: 10px;
  padding: 7px 14px;
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
  background: rgba(198, 93, 81, 0.1);
  color: var(--danger);
}
.pd-report {
  background: #fff;
  border-radius: 14px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}
.pd-report__title {
  font-size: 14px;
  font-weight: 600;
  margin: 0;
}
.pd-report__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  border: 1px solid rgba(0, 0, 0, 0.1);
  background: #fff;
  color: var(--text-secondary);
  border-radius: 999px;
  padding: 5px 14px;
  font-size: 13px;
  cursor: pointer;
}
.chip--on {
  border-color: var(--brand-teal);
  background: rgba(47, 119, 112, 0.08);
  color: var(--brand-teal);
}
.pd-report__ops {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.pd-comments {
  background: #fff;
  border-radius: 16px;
  padding: 16px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}
.cmt-input-row {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.cmt-input {
  flex: 1;
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 10px;
  padding: 8px 12px;
  font-size: 14px;
}
.cmt-empty {
  color: var(--text-muted);
  font-size: 13px;
  text-align: center;
  padding: 18px 0;
}
.cmt-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
}
.cmt-item {
  padding: 10px 0;
  border-top: 1px solid rgba(0, 0, 0, 0.05);
}
.cmt-item__head {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
}
.cmt-item__name {
  font-weight: 600;
  color: var(--text-secondary);
}
.cmt-item__time {
  color: var(--text-muted);
}
.cmt-item__del {
  margin-left: auto;
  border: none;
  background: none;
  color: var(--danger);
  font-size: 12px;
  cursor: pointer;
}
.cmt-item__content {
  margin: 4px 0 0;
  font-size: 14px;
  line-height: 1.6;
  color: var(--text-primary);
  white-space: pre-wrap;
}
</style>
