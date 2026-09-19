<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { message } from "ant-design-vue";
import { useRouter } from "vue-router";

import { getProfileStats, getProfileSummary, getUserProfile, saveProfileQuestionnaire } from "../services/api";
import { currentUserId } from "../stores/session";
import type { ProfilePreference, ProfileStats, ProfileSummary, QuestionnaireRequest, UserProfile } from "../types";

/* ---------- 问卷选项（长期画像，区别于 Home 表单的"本次偏好"） ---------- */
const styleOptions = ["自然风景", "历史文化", "美食探索", "城市漫游", "拍照打卡", "亲子活动", "夜生活", "购物", "户外运动"];
const paceOptions = ["轻松", "适中", "紧凑"];
const hotelOptions = ["经济型", "舒适型", "高档型"];
const foodOptions = ["火锅", "海鲜", "本帮菜", "川菜", "粤菜", "小吃", "烧烤", "面食", "甜品", "咖啡", "日料"];
const dietaryOptions = ["少辣", "不吃香菜", "不吃葱", "不吃蒜", "清真"];
const behaviorOptions = ["不早起", "少换酒店", "少走路", "优先公共交通", "避开人多的景点", "适合老人或儿童"];

const SOURCE_LABELS: Record<string, string> = {
  QUESTIONNAIRE: "主动选择",
  HISTORY_INFER: "历史推断",
  FEEDBACK: "行为反馈",
};

const CATEGORY_LABELS: Record<string, string> = {
  travel_style: "旅行风格",
  pace: "节奏",
  hotel: "住宿档次",
  food: "口味",
  dietary: "饮食要求",
  behavior: "行为约束",
  city: "城市偏好",
};

const profile = ref<UserProfile | null>(null);
const preferences = ref<ProfilePreference[]>([]);
/** Q5 修复：行程统计改为 /user/profile/summary 实时聚合（不信 user_profile 过期快照） */
const summary = ref<ProfileSummary | null>(null);
const loading = ref(true);
const saving = ref(false);
const editing = ref(false);

const router = useRouter();

/* 个性化效果统计（阶段四）：偏好命中率 / 负反馈率 / 平均满意度 */
const stats = ref<ProfileStats | null>(null);

async function loadProfile() {
  loading.value = true;
  try {
    const [resp, summaryResp] = await Promise.all([
      getUserProfile(),
      getProfileSummary().catch(() => null),
    ]);
    profile.value = resp.profile;
    preferences.value = resp.preferences || [];
    summary.value = summaryResp;
    const statsResp = await getProfileStats();
    stats.value = statsResp.stats ?? null;
  } catch (e) {
    console.error(e);
    message.error("画像加载失败。");
  } finally {
    loading.value = false;
  }
}

function go(name: string) {
  void router.push({ name });
}

/** 效果指标展示（无日志时给出引导文案）；帖子推荐指标在有过曝光后才出现（阶段三） */
const statsItems = computed(() => {
  const s = stats.value;
  if (!s) return [];
  if (s.recommendationCount === 0 && (s.postExposureCount ?? 0) === 0) return [];
  const items: { label: string; value: string; tip: string }[] = [];
  if (s.recommendationCount > 0) {
    items.push(
      { label: "推荐偏好命中率", value: `${s.preferenceHitRate.toFixed(1)}%`, tip: "生成的景点里，符合你画像偏好的比例" },
      { label: "负反馈率", value: `${s.dislikeRate.toFixed(1)}%`, tip: "点「不感兴趣/替换」的行为占比（越低越好）" },
      { label: "平均满意度", value: s.avgRating > 0 ? `${s.avgRating.toFixed(1)} / 5` : "暂无评分", tip: "你对历史行程的整体评分均值" },
      { label: "个性化推荐记录", value: `${s.recommendationCount} 条`, tip: "已记录的景点推荐依据（可解释、可追溯）" },
    );
  }
  if ((s.postExposureCount ?? 0) > 0) {
    items.push(
      { label: "帖子推荐曝光", value: `${s.postExposureCount} 条`, tip: "社区「为你推荐」展示过的帖子数（效果统计分母）" },
      { label: "帖子收藏率", value: `${(s.postFavoriteRate ?? 0).toFixed(1)}%`, tip: "推荐帖子中被你收藏的比例（收藏会提升同类内容）" },
      { label: "帖子负反馈率", value: `${(s.postDislikeRate ?? 0).toFixed(1)}%`, tip: "推荐帖子中你点「不感兴趣」的比例（越低越好）" },
    );
  }
  return items;
});

/* 问卷表单（编辑态）；本地用全必填接口，避免可选字段的模板判空噪音 */
interface ProfileFormState {
  travelStyles: string[];
  pace: string | null;
  hotelLevel: string | null;
  foodPreferences: string[];
  dietaryRestrictions: string[];
  behaviorNotes: string[];
}

const form = reactive<ProfileFormState>({
  travelStyles: [],
  pace: null,
  hotelLevel: null,
  foodPreferences: [],
  dietaryRestrictions: [],
  behaviorNotes: [],
});
const customInputs = reactive({ style: "", food: "", dietary: "", behavior: "" });

function splitList(s?: string | null): string[] {
  if (!s) return [];
  return s.split(",").map((x) => x.trim()).filter(Boolean);
}

function enterEdit() {
  const p = profile.value;
  form.travelStyles = splitList(p?.travelStyles);
  form.pace = p?.pacePreference || null;
  form.hotelLevel = p?.hotelPreference || null;
  form.foodPreferences = splitList(p?.foodPreferences);
  form.dietaryRestrictions = splitList(p?.dietaryRestrictions);
  form.behaviorNotes = splitList(p?.behaviorNotes);
  customInputs.style = "";
  customInputs.food = "";
  customInputs.dietary = "";
  customInputs.behavior = "";
  editing.value = true;
}

function cancelEdit() {
  editing.value = false;
}

function toggle(list: string[], value: string) {
  const idx = list.indexOf(value);
  if (idx >= 0) list.splice(idx, 1);
  else list.push(value);
}

function pickSingle(current: string | null, value: string): string | null {
  return current === value ? null : value;
}

/** 追加自定义标签（去重 + 上限），回车或点击 + 触发 */
function addCustom(field: "style" | "food" | "dietary" | "behavior") {
  const listMap: Record<string, string[]> = {
    style: form.travelStyles,
    food: form.foodPreferences,
    dietary: form.dietaryRestrictions,
    behavior: form.behaviorNotes,
  };
  const input = customInputs[field].trim();
  if (!input) return;
  const list = listMap[field];
  if (list.length >= 10) {
    message.warning("该分类标签最多 10 个");
    return;
  }
  if (!list.includes(input)) list.push(input);
  customInputs[field] = "";
}

async function save() {
  if (saving.value) return;
  if (form.travelStyles.length === 0 && !form.pace && !form.hotelLevel
      && form.foodPreferences.length === 0 && form.dietaryRestrictions.length === 0
      && form.behaviorNotes.length === 0) {
    message.warning("至少选择一个偏好项，或点取消返回");
    return;
  }
  saving.value = true;
  try {
    // 空数组 / 空串 = 用户主动清空该域（后端据此撤销明细与主档）；缺失字段才表示"不涉及该域"。
    // 单选项取消后 form.pace 为 null，转成空串提交，用户"取消勾选"才能真正落库。
    const resp = await saveProfileQuestionnaire({
      travelStyles: form.travelStyles,
      pace: form.pace ?? "",
      hotelLevel: form.hotelLevel ?? "",
      foodPreferences: form.foodPreferences,
      dietaryRestrictions: form.dietaryRestrictions,
      behaviorNotes: form.behaviorNotes,
    });
    profile.value = resp.profile;
    preferences.value = resp.preferences || [];
    editing.value = false;
    message.success("偏好已保存，下次生成行程将按你的偏好定制。");
  } catch (e) {
    console.error(e);
    message.error("保存失败，请稍后重试。");
  } finally {
    saving.value = false;
  }
}

/* 可解释展示：偏好明细按「来源 → 分类」分组 */
const preferenceLines = computed(() => {
  const lines: { sourceLabel: string; categoryLabel: string; tags: string[] }[] = [];
  for (const p of preferences.value) {
    const sourceLabel = SOURCE_LABELS[p.source] || p.source;
    const categoryLabel = CATEGORY_LABELS[p.category] || p.category;
    const last = lines[lines.length - 1];
    if (last && last.sourceLabel === sourceLabel && last.categoryLabel === categoryLabel) {
      last.tags.push(p.tag);
    } else {
      lines.push({ sourceLabel, categoryLabel, tags: [p.tag] });
    }
  }
  return lines;
});

/* 去过城市：summary 实时结果优先（Q5：不信 user_profile.visited_cities 快照） */
const visitedCities = computed(() => {
  const s = summary.value;
  if (s && s.visited_cities && s.visited_cities.length) return s.visited_cities;
  return splitList(profile.value?.visitedCities);
});

const tripInfoText = computed(() => {
  const s = summary.value;
  const p = profile.value;
  const parts: string[] = [];
  const tripCount = s?.trip_count ?? p?.tripCount ?? 0;
  if (tripCount > 0) parts.push(`${tripCount} 次历史行程`);
  const budget = s?.budget_preference ?? p?.budgetPreference;
  if (budget && budget > 0) parts.push(`人均约 ¥${Math.round(budget)}`);
  return parts.join(" · ");
});

onMounted(() => {
  void loadProfile();
});
</script>

<template>
  <section class="profile-page">
    <div class="ios-card profile-head">
      <div>
        <h2 class="profile-head__title">我的旅行偏好</h2>
        <p class="profile-head__desc">系统如何理解你 —— 生成行程时会结合以下画像</p>
      </div>
      <button v-if="!editing" class="ios-btn ios-btn--primary ios-btn--sm" @click="enterEdit">
        编辑偏好
      </button>
    </div>

    <!-- 个人中心入口（历史行程 / 我的收藏 / 我的帖子 / 新行程） -->
    <div class="hub-row">
      <button type="button" class="hub" @click="go('history')">
        <span class="hub__icon">🧳</span>
        <span class="hub__text">历史行程</span>
      </button>
      <button type="button" class="hub" @click="go('favorites')">
        <span class="hub__icon">💛</span>
        <span class="hub__text">我的收藏</span>
      </button>
      <button type="button" class="hub" @click="go('my-posts')">
        <span class="hub__icon">📝</span>
        <span class="hub__text">我的帖子</span>
      </button>
      <button
        v-if="currentUserId"
        type="button"
        class="hub"
        @click="router.push({ name: 'user-home', params: { id: currentUserId } })"
      >
        <span class="hub__icon">👤</span>
        <span class="hub__text">我的主页</span>
      </button>
      <button
        v-if="currentUserId"
        type="button"
        class="hub"
        @click="router.push({ name: 'user-follows', params: { id: currentUserId }, query: { tab: 'following' } })"
      >
        <span class="hub__icon">➕</span>
        <span class="hub__text">我的关注</span>
      </button>
      <button
        v-if="currentUserId"
        type="button"
        class="hub"
        @click="router.push({ name: 'user-follows', params: { id: currentUserId }, query: { tab: 'followers' } })"
      >
        <span class="hub__icon">👥</span>
        <span class="hub__text">我的粉丝</span>
      </button>
      <button type="button" class="hub" @click="go('dashboard')">
        <span class="hub__icon">🏠</span>
        <span class="hub__text">返回首页</span>
      </button>
    </div>

    <div v-if="loading" class="ios-card ios-empty">正在加载画像...</div>

    <!-- 只读视图 -->
    <template v-else-if="!editing">
      <div class="ios-card">
        <div v-if="preferenceLines.length === 0" class="ios-empty" style="text-align: left">
          <p style="margin: 0 0 8px;">我还不了解你的偏好。</p>
          <p class="ios-muted" style="margin: 0;">点右上角「编辑偏好」，用不到 30 秒告诉我你喜欢什么，之后的行程就会按你的口味来。</p>
        </div>
        <template v-else>
          <div class="ios-card__header">🧠 我了解你</div>
          <div class="pref-lines">
            <div v-for="(line, idx) in preferenceLines" :key="idx" class="pref-line">
              <span :class="['pref-line__src', { 'pref-line__src--active': line.sourceLabel === '主动选择' }]">
                {{ line.sourceLabel }}
              </span>
              <div class="pref-line__body">
                <span class="pref-line__cat">{{ line.categoryLabel }}</span>
                <span class="pref-line__tags">
                  <span v-for="t in line.tags" :key="t" class="pref-line__tag">{{ t }}</span>
                </span>
              </div>
            </div>
          </div>
        </template>
      </div>

      <div v-if="visitedCities.length > 0 || tripInfoText" class="ios-card">
        <div class="ios-card__header">🧳 来自你的历史行程</div>
        <div class="ios-chip-row">
          <span v-for="city in visitedCities" :key="city" class="ios-chip ios-chip--static">去过 · {{ city }}</span>
        </div>
        <p class="ios-muted" style="margin: 10px 0 0;">{{ tripInfoText }} —— 去过的城市会在再次规划时为你安排新玩法</p>
      </div>

      <!-- 个性化效果指标（阶段四）：偏好命中率 / 负反馈率 / 平均满意度 -->
      <div class="ios-card">
        <div class="ios-card__header">📈 个性化效果</div>
        <div v-if="statsItems.length === 0" class="ios-empty" style="text-align: left">
          <p style="margin: 0 0 8px;">还没有个性化推荐记录。</p>
          <p class="ios-muted" style="margin: 0;">去生成一次行程，系统会记录每个景点是「因为你的哪条偏好」被推荐的——偏好命中率就是从这里算出来的。</p>
        </div>
        <div v-else class="stats-grid">
          <div v-for="item in statsItems" :key="item.label" class="stats-item" :title="item.tip">
            <div class="stats-item__value">{{ item.value }}</div>
            <div class="stats-item__label">{{ item.label }}</div>
          </div>
        </div>
      </div>
    </template>

    <!-- 编辑（问卷）视图 -->
    <div v-else class="ios-card">
      <div class="ios-card__header">✏️ 我的偏好问卷</div>
      <p class="ios-muted" style="margin: -6px 0 16px;">没勾的项保持现状；保存后立即生效。</p>

      <div class="ios-field">
        <label class="ios-label">旅行风格（可多选）</label>
        <div class="ios-chips">
          <button v-for="opt in styleOptions" :key="opt" type="button"
            :class="['ios-chip', { 'ios-chip--active': form.travelStyles.includes(opt) }]"
            @click="toggle(form.travelStyles, opt)">{{ opt }}</button>
          <span class="ios-chip-add">
            <input v-model="customInputs.style" class="ios-chip-input" placeholder="其他风格" @keyup.enter="addCustom('style')" />
            <button type="button" class="ios-chip-addbtn" @click="addCustom('style')">＋</button>
          </span>
        </div>
      </div>

      <div class="ios-form-row ios-form-row--2col">
        <div class="ios-field">
          <label class="ios-label">节奏偏好</label>
          <div class="ios-chips">
            <button v-for="opt in paceOptions" :key="opt" type="button"
              :class="['ios-chip', { 'ios-chip--active': form.pace === opt }]"
              @click="form.pace = pickSingle(form.pace, opt)">{{ opt }}</button>
          </div>
        </div>
        <div class="ios-field">
          <label class="ios-label">住宿偏好</label>
          <div class="ios-chips">
            <button v-for="opt in hotelOptions" :key="opt" type="button"
              :class="['ios-chip', { 'ios-chip--active': form.hotelLevel === opt }]"
              @click="form.hotelLevel = pickSingle(form.hotelLevel, opt)">{{ opt }}</button>
          </div>
        </div>
      </div>

      <div class="ios-field">
        <label class="ios-label">口味偏好（可多选）</label>
        <div class="ios-chips">
          <button v-for="opt in foodOptions" :key="opt" type="button"
            :class="['ios-chip', { 'ios-chip--active': form.foodPreferences.includes(opt) }]"
            @click="toggle(form.foodPreferences, opt)">{{ opt }}</button>
          <span class="ios-chip-add">
            <input v-model="customInputs.food" class="ios-chip-input" placeholder="如：潮汕牛肉" @keyup.enter="addCustom('food')" />
            <button type="button" class="ios-chip-addbtn" @click="addCustom('food')">＋</button>
          </span>
        </div>
      </div>

      <div class="ios-field">
        <label class="ios-label">饮食要求（可多选）</label>
        <div class="ios-chips">
          <button v-for="opt in dietaryOptions" :key="opt" type="button"
            :class="['ios-chip', { 'ios-chip--active': form.dietaryRestrictions.includes(opt) }]"
            @click="toggle(form.dietaryRestrictions, opt)">{{ opt }}</button>
          <span class="ios-chip-add">
            <input v-model="customInputs.dietary" class="ios-chip-input" placeholder="如：不吃肥肉" @keyup.enter="addCustom('dietary')" />
            <button type="button" class="ios-chip-addbtn" @click="addCustom('dietary')">＋</button>
          </span>
        </div>
      </div>

      <div class="ios-field">
        <label class="ios-label">出行习惯（可多选）</label>
        <div class="ios-chips">
          <button v-for="opt in behaviorOptions" :key="opt" type="button"
            :class="['ios-chip', { 'ios-chip--active': form.behaviorNotes.includes(opt) }]"
            @click="toggle(form.behaviorNotes, opt)">{{ opt }}</button>
          <span class="ios-chip-add">
            <input v-model="customInputs.behavior" class="ios-chip-input" placeholder="如：午饭后出发" @keyup.enter="addCustom('behavior')" />
            <button type="button" class="ios-chip-addbtn" @click="addCustom('behavior')">＋</button>
          </span>
        </div>
      </div>

      <div class="profile-actions">
        <button class="ios-btn ios-btn--primary" :disabled="saving" @click="save">
          {{ saving ? "保存中..." : "保存偏好" }}
        </button>
        <button class="ios-btn" :disabled="saving" @click="cancelEdit">取消</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.ios-card {
  padding: 20px;
  border-radius: 12px;
  background: var(--surface-white);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.ios-card__header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 0.5px solid rgba(0, 0, 0, 0.06);
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.ios-empty {
  padding: 24px;
  text-align: center;
  font-size: 14px;
  color: var(--text-muted);
}

.ios-muted {
  font-size: 13px;
  color: var(--text-muted);
}

.ios-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 14px;
}

.ios-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-muted);
}

.ios-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.ios-chip {
  border: 1px solid var(--border-soft);
  border-radius: 20px;
  padding: 6px 14px;
  background: var(--surface-white);
  font-size: 13px;
  color: var(--text-primary);
  cursor: pointer;
  transition: all 0.2s ease;
  font-family: inherit;
}

.ios-chip:active {
  transform: scale(0.97);
}

.ios-chip--active {
  border-color: var(--brand-teal);
  background: var(--brand-teal);
  color: var(--surface-white);
}

.ios-btn {
  border: none;
  border-radius: 12px;
  padding: 14px 32px;
  font-size: 17px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  background: rgba(0, 0, 0, 0.04);
  color: var(--text-primary);
  font-family: inherit;
}

.ios-btn:active {
  transform: scale(0.97);
}

.ios-btn--primary {
  background: var(--brand-teal);
  color: var(--surface-white);
}

.ios-btn--primary:disabled {
  opacity: 0.5;
  cursor: wait;
}

.ios-btn--sm {
  padding: 8px 16px;
  font-size: 14px;
  border-radius: 10px;
}

.profile-page {
  display: grid;
  gap: 12px;
}

.profile-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.profile-head__title {
  margin: 0 0 4px;
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
}

.profile-head__desc {
  margin: 0;
  font-size: 13px;
  color: var(--text-muted);
}

.pref-lines {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.pref-line {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  font-size: 14px;
  line-height: 1.7;
  color: var(--text-primary);
}

.pref-line__src {
  flex-shrink: 0;
  align-self: flex-start; /* 与类别名顶端对齐：1 行/2 行行数不同时徽章不漂移 */
  padding: 2px 9px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 500;
  background: rgba(0, 0, 0, 0.05);
  color: var(--text-secondary);
  white-space: nowrap;
  /* 让胶囊内边距在多行情况下也维持一致的视觉高度 */
  min-height: 22px;
  display: inline-flex;
  align-items: center;
}

.pref-line__src--active {
  background: rgba(47, 119, 112, 0.12);
  color: var(--brand-teal);
  font-weight: 600;
}

.pref-line__body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  flex: 1;
}

.pref-line__cat {
  color: var(--text-primary);
  font-weight: 600;
  font-size: 13.5px;
}

.pref-line__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.pref-line__tag {
  display: inline-block;
  padding: 1px 9px;
  border-radius: 10px;
  background: rgba(47, 119, 112, 0.07);
  color: var(--brand-deep);
  font-size: 12.5px;
}

.ios-chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.ios-chip--static {
  cursor: default;
  background: rgba(0, 0, 0, 0.04);
}

.ios-form-row--2col {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.profile-actions {
  display: flex;
  gap: 10px;
  margin-top: 20px;
}

.ios-chip-add {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 1px dashed rgba(23, 33, 31, 0.18);
  border-radius: 20px;
  padding: 2px 6px 2px 12px;
  background: var(--surface-white);
}

.ios-chip-input {
  border: none;
  outline: none;
  width: 96px;
  font-size: 13px;
  color: var(--text-primary);
  background: transparent;
  font-family: inherit;
}

.ios-chip-addbtn {
  border: none;
  border-radius: 50%;
  width: 22px;
  height: 22px;
  background: var(--brand-teal);
  color: var(--surface-white);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}

/* 个性化效果指标（阶段四） */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 10px;
}

.stats-item {
  padding: 14px 12px;
  border-radius: 10px;
  background: rgba(23, 33, 31, 0.05);
  text-align: center;
}

.stats-item__value {
  font-size: 22px;
  font-weight: 700;
  color: var(--brand-teal);
  line-height: 1.2;
}

.stats-item__label {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
}

/* 个人中心入口 */
.hub-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
}

.hub {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 14px 10px;
  border: none;
  border-radius: 14px;
  background: var(--surface-white);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.2s ease;
}

.hub:hover {
  box-shadow: 0 3px 12px rgba(0, 0, 0, 0.12);
}

.hub:active {
  transform: scale(0.98);
}

.hub__icon {
  font-size: 16px;
}

.hub__text {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
}

@media (max-width: 768px) {
  .ios-form-row--2col {
    grid-template-columns: 1fr;
    gap: 12px;
  }

  .hub-row {
    grid-template-columns: 1fr;
  }
}
</style>
