<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { message } from "ant-design-vue";
import { useRouter } from "vue-router";

import {
  dislikePost,
  favoritePost,
  likePost,
  reportBehavior,
  resolveImageUrl,
  undislikePost,
  unfavoritePost,
  unlikePost,
} from "../services/api";
import type { PostItem, PostStatus } from "../types";
import { POST_STATUS_META, coverColor, isInteractiveStatus, postStatusLabel, postTypeLabel } from "../constants/postMeta";

function postStatusCls(status?: string | null): string {
  return (status && POST_STATUS_META[status as PostStatus]?.cls) || "";
}

/**
 * 帖子卡片（社区阶段二：公开流/我的帖子/审核队列复用；阶段三：进画像闭环）。
 * 承载：点赞 / 收藏 / 不喜欢（幂等、请求中防重复）+ 互动行为上报（POST→画像标签）：
 * 首次点赞/收藏/不喜欢生效时，后端已在互动服务内写 user_behavior（见
 * PostInteractionService.recordPersonalizationBehavior），前端无需重复上报；
 * 前端只需在「从推荐流点进详情」时上报 CLICK，作为帖子推荐点击率的分母分子。
 * 操作成功后 emit('changed') 供父页面刷新计数态。
 */
const props = defineProps<{
  post: PostItem;
  /** 精简模式（首页预览等）：不显示状态徽标与作者行 */
  compact?: boolean;
}>();

const emit = defineEmits<{ (e: "changed"): void }>();

const router = useRouter();

const liked = ref(!!props.post.liked);
const favorited = ref(!!props.post.favorited);
// 2026-09-18：从列表数据回填「不感兴趣」——此前恒为 false，刷新页面状态即丢，用户以为"点了没反应"
const disliked = ref(!!props.post.disliked);
const likeCount = ref(props.post.like_count || 0);
const favoriteCount = ref(props.post.favorite_count || 0);
const busy = ref(false);

// 列表重新拉取后同步互动状态：父页面收到 emit('changed') 会重新请求列表，
// 组件实例按 id 复用 → 仅靠 ref 初始值会停在旧状态，必须跟随 props 更新。
watch(() => props.post.liked, (v) => { liked.value = !!v; });
watch(() => props.post.favorited, (v) => { favorited.value = !!v; });
watch(() => props.post.disliked, (v) => { disliked.value = !!v; });

const showStatus = computed(() => !props.compact && props.post.status !== "PUBLISHED");

/** 封面：相对路径拼 API_BASE_URL；加载失败(404/防盗链) → 降级标题首字占位 */
const coverBroken = ref(false);
const coverSrc = computed(() => resolveImageUrl(props.post.cover_image));
watch(
  () => props.post.cover_image,
  () => {
    coverBroken.value = false;
  }
);

/** 是否展示"不感兴趣"（仅已发布内容可互动，且非本人帖子——自己不能对自己帖子表态；判定与详情页同源） */
const canFeedback = computed(
  () => isInteractiveStatus(props.post.status) && !props.post.mine
);

function open() {
  // 阶段三：从推荐流点进详情 → 上报 CLICK（帖子推荐点击率分子；失败静默不影响跳转）
  reportBehavior({
    itemType: "POST",
    itemId: String(props.post.id),
    itemName: props.post.title,
    actionType: "CLICK",
  }).catch(() => {});
  void router.push({ name: "post-detail", params: { id: String(props.post.id) } });
}

/** 点击作者名 → 用户旅行主页（阻止冒泡，避免触发整卡跳详情） */
function goAuthor() {
  if (!props.post.author?.id) return;
  void router.push({ name: "user-home", params: { id: props.post.author.id } });
}

async function toggleLike() {
  if (busy.value) return;
  busy.value = true;
  const target = !liked.value;
  try {
    const resp = target ? await likePost(props.post.id) : await unlikePost(props.post.id);
    liked.value = resp.liked;
    likeCount.value = resp.likeCount;
    emit("changed");
  } catch {
    message.error("操作失败，请稍后重试。");
  } finally {
    busy.value = false;
  }
}

async function toggleFavorite() {
  if (busy.value) return;
  busy.value = true;
  const target = !favorited.value;
  try {
    const resp = target ? await favoritePost(props.post.id) : await unfavoritePost(props.post.id);
    favorited.value = resp.favorited;
    favoriteCount.value = resp.favoriteCount;
    emit("changed");
  } catch {
    message.error("操作失败，请稍后重试。");
  } finally {
    busy.value = false;
  }
}

async function toggleDislike() {
  if (busy.value) return;
  busy.value = true;
  const target = !disliked.value;
  try {
    if (target) {
      const resp = await dislikePost(props.post.id);
      disliked.value = resp.disliked;
    } else {
      await undislikePost(props.post.id);
      disliked.value = false;
    }
    // 首次"不感兴趣"生效 → 父页面刷新（后端已把该帖子标签降权，推荐排序会立即变化）
    emit("changed");
    if (target) {
      message.success("已减少这类内容的推荐");
    } else {
      message.success("已撤销，恢复这类内容的推荐");
    }
  } catch {
    message.error("操作失败，请稍后重试。");
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <article class="p-card" role="button" tabindex="0" @click="open" @keyup.enter="open">
    <img
      v-if="post.cover_image && !coverBroken"
      :src="coverSrc"
      :alt="post.title"
      class="p-card__cover"
      loading="lazy"
      @error="coverBroken = true"
    />
    <div v-else class="p-card__cover p-card__cover--fallback" :style="{ background: coverColor(post.id) }">
      {{ post.title.slice(0, 1) }}
    </div>

    <div class="p-card__body">
      <div class="p-card__head">
        <span class="p-card__type">{{ postTypeLabel(post.post_type) }}</span>
        <span v-if="post.city" class="p-card__city">{{ post.city }}</span>
        <span v-if="showStatus" :class="['p-card__status', postStatusCls(post.status)]">
          {{ postStatusLabel(post.status) }}
        </span>
      </div>
      <h3 class="p-card__title">{{ post.title }}</h3>
      <p v-if="post.summary && !compact" class="p-card__summary">{{ post.summary }}</p>

      <div class="p-card__meta">
        <span
          v-if="!compact && post.author"
          class="p-card__author"
          role="button"
          tabindex="0"
          @click.stop="goAuthor"
          @keyup.enter.stop="goAuthor"
        >@{{ post.author.nickname || post.author.id }}</span>
        <span class="p-card__stat">❤️ {{ likeCount }}</span>
        <span class="p-card__stat">⭐ {{ favoriteCount }}</span>
        <span class="p-card__stat">💬 {{ post.comment_count || 0 }}</span>
        <span class="p-card__stat">👁 {{ post.view_count || 0 }}</span>
        <span v-if="!compact && post.published_at" class="p-card__date">{{ post.published_at.slice(0, 10) }}</span>
      </div>

      <!-- 阶段三：推荐理由（"为你推荐"排序由后端确定性生成） -->
      <p v-if="!compact && post.recommend_reason" class="p-card__reason">🎯 {{ post.recommend_reason }}</p>

      <div v-if="!compact" class="p-card__actions" @click.stop>
        <button type="button" :class="['act', liked ? 'act--on' : '']" :disabled="busy" @click="toggleLike">
          {{ liked ? "♥ 已赞" : "♡ 点赞" }}
        </button>
        <button type="button" :class="['act', favorited ? 'act--on' : '']" :disabled="busy" @click="toggleFavorite">
          {{ favorited ? "★ 已收藏" : "☆ 收藏" }}
        </button>
        <button
          v-if="canFeedback"
          type="button"
          :class="['act', 'act--dislike', disliked ? 'act--on' : '']"
          :disabled="busy"
          @click="toggleDislike"
        >
          {{ disliked ? "已减少同类" : "🙅 不感兴趣" }}
        </button>
      </div>
    </div>
  </article>
</template>

<style scoped>
.p-card {
  display: flex;
  flex-direction: column;
  background: #ffffff;
  border-radius: 14px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  cursor: pointer;
  transition: box-shadow 0.2s ease;
}
.p-card:hover {
  box-shadow: 0 3px 12px rgba(0, 0, 0, 0.12);
}
.p-card__cover {
  width: 100%;
  height: 132px;
  object-fit: cover;
  display: block;
  flex-shrink: 0;
}
.p-card__cover--fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 44px;
  font-weight: 700;
  color: #b7c9e0;
}
.p-card__body {
  padding: 12px 14px 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.p-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.p-card__type {
  color: var(--brand-teal);
  font-weight: 600;
}
.p-card__city {
  color: var(--text-muted);
}
.p-card__status {
  margin-left: auto;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
}
.st--draft {
  background: rgba(23, 33, 31, 0.05);
  color: var(--text-secondary);
}
.st--pending {
  background: rgba(201, 138, 45, 0.12);
  color: var(--warning);
}
.st--published {
  background: rgba(60, 140, 112, 0.1);
  color: var(--success);
}
.st--rejected {
  background: rgba(198, 93, 81, 0.08);
  color: var(--danger);
}
.st--hidden {
  background: rgba(23, 33, 31, 0.05);
  color: var(--text-secondary);
}
.p-card__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.p-card__summary {
  font-size: 13px;
  color: var(--text-secondary);
  margin: 0;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.p-card__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 12px;
  color: var(--text-muted);
}
.p-card__author {
  font-weight: 500;
  color: var(--brand-teal);
  cursor: pointer;
}
.p-card__author:hover {
  text-decoration: underline;
}
.p-card__date {
  margin-left: auto;
}
.p-card__actions {
  display: flex;
  gap: 8px;
  padding-top: 2px;
}
.act {
  border: none;
  border-radius: 8px;
  padding: 5px 12px;
  font-size: 13px;
  background: rgba(0, 0, 0, 0.04);
  color: var(--text-secondary);
  cursor: pointer;
}
.act--on {
  background: rgba(47, 119, 112, 0.08);
  color: var(--brand-teal);
}
.act--dislike {
  margin-left: auto;
  color: var(--text-muted);
}
.act--dislike.act--on {
  background: rgba(201, 138, 45, 0.12);
  color: var(--warning);
}
.p-card__reason {
  margin: 0;
  font-size: 12px;
  color: var(--warning);
  background: rgba(201, 138, 45, 0.1);
  border-radius: 8px;
  padding: 5px 10px;
}
.act:disabled {
  opacity: 0.6;
}
</style>
