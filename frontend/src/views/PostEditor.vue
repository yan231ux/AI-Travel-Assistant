<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { message } from "ant-design-vue";
import { useRouter } from "vue-router";

import { POPULAR_CITIES } from "../constants/cities";
import {
  createPost,
  getPostDetail,
  getRecommendations,
  resolveImageUrl,
  updatePost,
  uploadImage,
} from "../services/api";
import type { PostPayload, PostSpotRef, RecommendationItem } from "../types";

/**
 * 发帖 / 编辑帖子（阶段二 /community/create、/community/edit/:id）。
 * 创建=草稿（DRAFT），保存后可去我的帖子/详情页提交审核 —— 不经审核不会公开。
 * 关联景点：按城市从推荐流挑选（复用景点稳定 ID，详情页可回跳景点页）。
 */
const props = defineProps<{ id?: string }>();
const router = useRouter();

const editingId = ref<number | null>(props.id ? Number(props.id) : null);
const loading = ref(true);

const title = ref("");
const summary = ref("");
const content = ref("");
const coverImage = ref("");
const city = ref("");
const travelDays = ref<number | null>(null);
const budget = ref<number | null>(null);
const pace = ref("");
const postType = ref<PostPayload["postType"]>("NOTE");

const typeOptions = [
  { key: "GUIDE", label: "🗺️ 城市攻略" },
  { key: "SPOT_RECOMMENDATION", label: "📍 景点推荐" },
  { key: "ITINERARY", label: "🧳 行程分享" },
  { key: "NOTE", label: "✍️ 旅行随笔" },
];

/* 关联景点 */
const selectedSpots = ref<PostSpotRef[]>([]);
const spotCandidates = ref<RecommendationItem[]>([]);
const loadingSpots = ref(false);
const chosenSpotIds = ref<Set<string>>(new Set());

async function loadSpotCandidates() {
  if (!city.value.trim()) {
    spotCandidates.value = [];
    return;
  }
  loadingSpots.value = true;
  try {
    const feed = await getRecommendations(city.value.trim(), 1, 12, "popular");
    spotCandidates.value = feed.items || [];
  } catch {
    spotCandidates.value = [];
  } finally {
    loadingSpots.value = false;
  }
}

function pickSpot(s: RecommendationItem) {
  if (chosenSpotIds.value.has(s.spot_id)) return;
  chosenSpotIds.value = new Set(chosenSpotIds.value).add(s.spot_id);
  selectedSpots.value.push({
    spot_id: s.spot_id,
    poi_id: s.poi_id,
    spot_name: s.name,
    image_url: s.image_url || null,
  });
}

function removeSpot(idx: number) {
  const removed = selectedSpots.value[idx];
  if (removed?.spot_id) {
    const next = new Set(chosenSpotIds.value);
    next.delete(removed.spot_id);
    chosenSpotIds.value = next;
  }
  selectedSpots.value = selectedSpots.value.filter((_, i) => i !== idx);
}

function onCityChange() {
  chosenSpotIds.value = new Set();
  selectedSpots.value = [];
  void loadSpotCandidates();
}

const saving = ref(false);

/* ---------- 封面图：本地上传到后端（阶段四⑨），或手动填 URL ---------- */
const fileInput = ref<HTMLInputElement | null>(null);
const uploading = ref(false);
const coverPreviewBroken = ref(false);
const coverPreview = computed(() => resolveImageUrl(coverImage.value));

async function onFilePick(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = ""; // 允许连续选择同一文件
  if (!file) return;
  if (!/^image\/(jpeg|png|webp|gif)$/i.test(file.type)) {
    return message.warning("仅支持 JPG/PNG/WebP/GIF 图片");
  }
  if (file.size > 5 * 1024 * 1024) {
    return message.warning("图片不能超过 5MB");
  }
  uploading.value = true;
  try {
    coverImage.value = await uploadImage(file);
    coverPreviewBroken.value = false;
    message.success("封面已上传");
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
    message.error(msg || "上传失败，请稍后重试。");
  } finally {
    uploading.value = false;
  }
}

function clearCover() {
  coverImage.value = "";
  coverPreviewBroken.value = false;
}

async function save() {
  if (!title.value.trim()) return message.warning("请填写标题");
  if (content.value.trim().length < 10) return message.warning("正文至少写 10 个字");
  saving.value = true;
  const payload: PostPayload = {
    title: title.value.trim(),
    summary: summary.value.trim() || undefined,
    content: content.value.trim(),
    coverImage: coverImage.value.trim() || undefined,
    city: city.value.trim() || undefined,
    travelDays: travelDays.value || undefined,
    budget: budget.value || undefined,
    pace: pace.value || undefined,
    postType: postType.value,
    spots: selectedSpots.value.length ? selectedSpots.value : undefined,
  };
  try {
    if (editingId.value != null) {
      await updatePost(editingId.value, payload);
      // P0-1：已发布内容发生实质修改 → 后端自动转重新审核，公开流不再展示旧版
      message.success("已保存；已发布内容若有修改需重新审核通过后才会公开");
    } else {
      const resp = await createPost(payload);
      message.success("草稿已保存");
      editingId.value = resp.postId;
    }
    void router.replace({ name: "my-posts" });
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
    message.error(msg || "保存失败，请稍后重试。");
  } finally {
    saving.value = false;
  }
}

async function loadForEdit() {
  if (editingId.value == null) {
    loading.value = false;
    return;
  }
  try {
    const d = await getPostDetail(editingId.value);
    if (!d.mine) {
      message.warning("只能编辑自己的帖子");
      void router.replace({ name: "community" });
      return;
    }
    title.value = d.title || "";
    summary.value = d.summary || "";
    content.value = d.content || "";
    coverImage.value = d.cover_image || "";
    city.value = d.city || "";
    travelDays.value = d.travel_days ?? null;
    budget.value = d.budget ?? null;
    pace.value = d.pace || "";
    postType.value = d.post_type;
    selectedSpots.value = (d.spots || []).map((s) => ({
      spot_id: s.spot_id,
      poi_id: s.poi_id,
      spot_name: s.spot_name,
      image_url: s.image_url || null,
    }));
    chosenSpotIds.value = new Set(
      (d.spots || []).map((s) => s.spot_id).filter((x): x is string => !!x)
    );
    if (d.city) void loadSpotCandidates();
  } catch {
    message.error("帖子不存在或无权编辑");
    void router.replace({ name: "community" });
  } finally {
    loading.value = false;
  }
}

onMounted(() => void loadForEdit());
</script>

<template>
  <section class="pe-page">
    <div v-if="loading" class="pe-state">加载中...</div>
    <form v-else class="pe-card" @submit.prevent="save">
      <h2 class="pe-title">{{ editingId != null ? "编辑帖子" : "✍️ 发布旅行内容" }}</h2>
      <p class="pe-hint">发布流程：保存草稿 → 提交审核 → 管理员通过后公开展示（未审核内容不会出现在社区流）。</p>

      <label class="pe-label">类型</label>
      <div class="pe-types">
        <button
          v-for="t in typeOptions"
          :key="t.key"
          type="button"
          :class="['pe-type', { 'pe-type--on': postType === t.key }]"
          @click="postType = t.key as PostPayload['postType']"
        >
          {{ t.label }}
        </button>
      </div>

      <label class="pe-label">标题 <span class="pe-req">*</span></label>
      <input v-model="title" class="pe-input" maxlength="120" placeholder="例如：三亚三日轻松路线，不早起版本" />

      <label class="pe-label">摘要（选填，列表卡片展示）</label>
      <input v-model="summary" class="pe-input" maxlength="300" placeholder="一句话概括这篇内容" />

      <label class="pe-label">正文 <span class="pe-req">*</span></label>
      <textarea
        v-model="content"
        class="pe-textarea"
        rows="8"
        placeholder="分享你的真实旅行经验……（至少 10 个字；含联系方式/外链会被自动拦截）"
      ></textarea>

      <div class="pe-grid">
        <div>
          <label class="pe-label">城市（选填）</label>
          <input v-model="city" class="pe-input" list="city-list" placeholder="选择或输入城市" @change="onCityChange" />
          <datalist id="city-list">
            <option v-for="c in POPULAR_CITIES" :key="c" :value="c" />
          </datalist>
        </div>
        <div>
          <label class="pe-label">节奏（选填）</label>
          <select v-model="pace" class="pe-input">
            <option value="">不指定</option>
            <option value="轻松">轻松</option>
            <option value="适中">适中</option>
            <option value="紧凑">紧凑</option>
          </select>
        </div>
      </div>

      <div class="pe-grid">
        <div>
          <label class="pe-label">天数（选填）</label>
          <input v-model.number="travelDays" type="number" min="1" max="30" class="pe-input" placeholder="如 3" />
        </div>
        <div>
          <label class="pe-label">预算 ¥（选填）</label>
          <input v-model.number="budget" type="number" min="0" class="pe-input" placeholder="如 3200" />
        </div>
      </div>

      <label class="pe-label">封面图（选填）</label>
      <div class="pe-cover">
        <div class="pe-cover__preview">
          <img
            v-if="coverImage && !coverPreviewBroken"
            :src="coverPreview"
            alt="封面预览"
            @error="coverPreviewBroken = true"
          />
          <span v-else-if="coverPreviewBroken">预览失败</span>
          <span v-else>尚未设置封面</span>
        </div>
        <div class="pe-cover__ops">
          <input
            ref="fileInput"
            type="file"
            accept="image/jpeg,image/png,image/webp,image/gif"
            style="display: none"
            @change="onFilePick"
          />
          <button type="button" class="btn" :disabled="uploading" @click="fileInput?.click()">
            {{ uploading ? "上传中..." : "🖼 上传本地图片" }}
          </button>
          <button v-if="coverImage" type="button" class="btn" @click="clearCover">移除封面</button>
        </div>
      </div>
      <label class="pe-label">或直接填写图片 URL（选填；远端图片粘贴完整 https:// 地址）</label>
      <input v-model="coverImage" class="pe-input" placeholder="https://… 或 /uploads/…" />

      <!-- 关联景点 -->
      <label class="pe-label">关联景点（选填，从所选城市的推荐景点挑选）</label>
      <div v-if="selectedSpots.length" class="pe-spots">
        <button v-for="(s, i) in selectedSpots" :key="s.spot_name + i" type="button" class="pe-spot" @click="removeSpot(i)">
          {{ s.spot_name }} ✕
        </button>
      </div>
      <button v-if="city" type="button" class="pe-load" :disabled="loadingSpots" @click="loadSpotCandidates">
        {{ loadingSpots ? "加载中..." : "加载该城市景点" }}
      </button>
      <div v-if="spotCandidates.length" class="pe-cands">
        <button
          v-for="s in spotCandidates"
          :key="s.spot_id"
          type="button"
          class="pe-cand"
          :disabled="chosenSpotIds.has(s.spot_id)"
          @click="pickSpot(s)"
        >
          {{ s.name }}（{{ s.city }}）
        </button>
      </div>

      <div class="pe-ops">
        <button type="button" class="btn" @click="router.back()">取消</button>
        <button type="submit" class="btn btn--primary" :disabled="saving">
          {{ saving ? "保存中..." : editingId != null ? "保存修改" : "保存草稿" }}
        </button>
      </div>
    </form>
  </section>
</template>

<style scoped>
.pe-page {
  max-width: 720px;
  margin: 0 auto;
}
.pe-state {
  text-align: center;
  padding: 48px 0;
  color: #8e8e93;
}
.pe-card {
  background: #fff;
  border-radius: 16px;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}
.pe-title {
  font-size: 20px;
  margin: 0;
  color: #1c1c1e;
}
.pe-hint {
  font-size: 12px;
  color: #8e8e93;
  margin: 0 0 4px;
}
.pe-label {
  font-size: 13px;
  font-weight: 600;
  color: #3c3c43;
  margin-top: 6px;
}
.pe-req {
  color: #ff3b30;
}
.pe-input {
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 10px;
  padding: 9px 12px;
  font-size: 14px;
  width: 100%;
  box-sizing: border-box;
}
.pe-textarea {
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 10px;
  padding: 10px 12px;
  font-size: 14px;
  width: 100%;
  box-sizing: border-box;
  line-height: 1.7;
  font-family: inherit;
  resize: vertical;
}
.pe-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.pe-types {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.pe-type {
  border: 1px solid rgba(0, 0, 0, 0.1);
  background: #fff;
  border-radius: 999px;
  padding: 6px 14px;
  font-size: 13px;
  color: #3c3c43;
  cursor: pointer;
}
.pe-type--on {
  border-color: #3478f6;
  background: rgba(52, 120, 246, 0.08);
  color: #3478f6;
  font-weight: 600;
}
.pe-spots {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.pe-spot {
  border: 1px solid rgba(52, 120, 246, 0.3);
  background: rgba(52, 120, 246, 0.08);
  color: #3478f6;
  border-radius: 999px;
  padding: 5px 12px;
  font-size: 13px;
  cursor: pointer;
}
.pe-load {
  align-self: flex-start;
  border: none;
  background: none;
  color: #3478f6;
  font-size: 13px;
  cursor: pointer;
  padding: 2px 0;
}
.pe-cands {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  max-height: 150px;
  overflow-y: auto;
}
.pe-cand {
  border: 1px solid rgba(0, 0, 0, 0.1);
  background: #fff;
  color: #3c3c43;
  border-radius: 999px;
  padding: 5px 12px;
  font-size: 13px;
  cursor: pointer;
}
.pe-cand:disabled {
  opacity: 0.5;
}
.pe-cover {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.pe-cover__preview {
  width: 150px;
  height: 92px;
  border-radius: 10px;
  overflow: hidden;
  border: 1px dashed rgba(0, 0, 0, 0.18);
  background: #fafafa;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #8e8e93;
  font-size: 12px;
  flex-shrink: 0;
}
.pe-cover__preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.pe-cover__ops {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  padding-top: 26px;
}
.pe-ops {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}
.btn {
  border: none;
  border-radius: 10px;
  padding: 8px 18px;
  font-size: 14px;
  font-weight: 500;
  background: rgba(0, 0, 0, 0.05);
  color: #3c3c43;
  cursor: pointer;
}
.btn--primary {
  background: #3478f6;
  color: #fff;
}
.btn:disabled {
  opacity: 0.6;
}
@media (max-width: 640px) {
  .pe-grid {
    grid-template-columns: 1fr;
  }
}
</style>
