<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { message } from "ant-design-vue";
import dayjs, { type Dayjs } from "dayjs";
import { useRoute, useRouter } from "vue-router";

import { beginLive } from "../stores/trip";
import {
  clearPlannerDraft,
  plannerDraft,
  plannerDraftHasContent,
  type PlannerDraftSpot,
} from "../stores/plannerDraft";
import type { TripRequestPayload } from "../types";

/**
 * AI 行程规划表单（产品化阶段一 /plan；首页 CTA / 景点卡片"加入行程"经 /plan?city=&spot= 跳入预填）。
 * 画像卡已移入首页 Dashboard，本页专注表单主流程。
 *
 * 加入行程闭环（排查报告 P0-2）：query 携带 city + spot + spot_id + poi_id 进入时，
 * 页面显示可删除的"已加入景点"标签；提交时合并进 special_notes（"务必安排：景点名"，
 * 不覆盖用户自己填的备注、同一景点不重复），复用后端 TravelAgent 对点名景点的解析能力。
 *
 * 草稿不丢（本次改动）：表单字段与"已加入景点"挂在 stores/plannerDraft 单例上，
 * 组件卸载不再清空 —— 跳到首页/发现/社区，或生成完行程去结果页再回来，已填内容原样还在；
 * 只有退出账号、关闭标签页/重启浏览器、或手动点"清空"才复位。本页只负责绑定与校验。
 */
const router = useRouter();
const route = useRoute();

/**
 * 从景点卡/详情页"加入行程"带入的指定景点；支持多个累加，重复同名入栈去重（点击多家不同景点可一起规划）。
 * 直接绑定草稿单例里的数组（不再是页面局部 ref）：跳走再回来不会被清掉。
 * ⚠️ 因此所有改动都必须原地改数组（push / splice），不能整体重新赋值，否则会与草稿脱钩。
 */
const joinedSpots = plannerDraft.spots;

/** 入栈：同名视为同一请求（不重复提示也不重复文本），返回 true=新增，false=去重命中 */
function pushJoinedSpot(next: PlannerDraftSpot): boolean {
  if (joinedSpots.find((x) => x.name === next.name)) {
    return false;
  }
  joinedSpots.push(next);
  return true;
}

/** 移除某个已加入景点（按 name 定位）；URL query 不再回写以免覆盖后续其它"加入行程"导航 */
function removeJoinedSpot(name: string) {
  const idx = joinedSpots.findIndex((x) => x.name === name);
  if (idx >= 0) {
    joinedSpots.splice(idx, 1);
  }
}

/**
 * 是否提示"已恢复上次填写的内容"：进入页面时取一次快照。
 * 用快照而不是实时判断 —— 用户随后边填边改时提示条不该突然冒出来；
 * 只有"一进来就发现内容还在"才需要解释来历，顺带给出清空入口。
 */
const showDraftTip = ref(plannerDraftHasContent());

/** 手动清空草稿：内存草稿与存储一起复位（退出登录清的是同一份数据） */
function resetDraft() {
  clearPlannerDraft();
  showDraftTip.value = false;
  message.success("已清空，可重新填写");
}

const preferenceOptions = [
  "自然风景",
  "拍照",
  "美食",
  "古镇",
  "休闲",
];

const dietaryOptions = [
  "少辣",
  "不吃香菜",
  "不吃葱",
];

function formatDate(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

/**
 * 表单字段直接绑定草稿单例（默认值生成与持久化都在 stores/plannerDraft 内）：
 * 页面只读写这一份数据，因此挂载时拿到的就是"上次离开时填的值"，不再随组件卸载消失。
 */
const formState = plannerDraft.form;

const MAX_DAYS = 7;

function calcDays(start: string, end: string): number {
  const s = new Date(start);
  const e = new Date(end);
  const diff = e.getTime() - s.getTime();
  const d = Math.floor(diff / 86400000) + 1;
  return isNaN(d) || d < 1 ? 1 : Math.min(d, MAX_DAYS);
}

const todayStart = dayjs().startOf("day");

// 日历选择器绑定的 dayjs 值（与 formState 字符串双向联动）——
// 初值从草稿的日期反解：恢复草稿时日历要停在同一段日期上，而不是跳回"今天"。
const startDate = ref<Dayjs>(dayjs(formState.startDate));
const endDate = ref<Dayjs>(dayjs(formState.endDate));

// 开始日期：今天及以后可选（不能选过去时间）
function disabledStartDate(current: Dayjs | null): boolean {
  return !!current && current.isBefore(todayStart, "day");
}

// 结束日期：不能早于开始日期，且最长不超过 MAX_DAYS 天
function disabledEndDate(current: Dayjs): boolean {
  if (!current) return false;
  if (current.isBefore(startDate.value, "day")) return true;
  return current.isAfter(startDate.value.add(MAX_DAYS - 1, "day"), "day");
}

const dayCount = ref(calcDays(formState.startDate, formState.endDate));

// 开始/结束/天数三联动：改开始 → 自动修正结束；改天数 → 联动结束
watch(startDate, (s) => {
  if (endDate.value.isBefore(s, "day")) {
    endDate.value = s;
  }
  formState.startDate = formatDate(s.toDate());
  formState.endDate = formatDate(endDate.value.toDate());
  dayCount.value = calcDays(formState.startDate, formState.endDate);
});

watch(endDate, (e) => {
  formState.endDate = formatDate(e.toDate());
  dayCount.value = calcDays(formState.startDate, formState.endDate);
});

watch(dayCount, (dc) => {
  const valid = Math.max(1, Math.min(MAX_DAYS, dc));
  if (valid !== dc) {
    dayCount.value = valid;
    return;
  }
  endDate.value = startDate.value.add(valid - 1, "day");
  formState.endDate = formatDate(endDate.value.toDate());
});

// 首页/景点卡"加入行程" → /plan?city=城市[&spot=景点&spot_id=&poi_id=] 预填目的地 + 已加入景点
// （排查报告 P0-2 + GPT 建议：多景点累加：用户从不同景点卡点击时一并入栈，统一规划）
function applyPlanPrefill() {
  const q = route.query;
  const qCity = q.city;
  if (qCity && typeof qCity === "string" && qCity.trim()) {
    const city = qCity.trim();
    // 换城市 = 换了一次规划意图：上一个城市累积的"已加入景点"不再适用，
    // 若沿用会让"上海的行程"里冒出三亚的景点，所以只在换城市时清空它们（其余字段保留）。
    if (formState.destination.trim() && formState.destination.trim() !== city) {
      joinedSpots.splice(0, joinedSpots.length);
    }
    formState.destination = city;
  }
  const qSpot = q.spot;
  if (qSpot && typeof qSpot === "string" && qSpot.trim()) {
    const name = qSpot.trim();
    const sid = q.spot_id;
    const pid = q.poi_id;
    const next: PlannerDraftSpot = {
      name,
      spotId: typeof sid === "string" && sid ? sid : undefined,
      poiId: typeof pid === "string" && pid ? pid : undefined,
    };
    // 同一景点重复进入（浏览器回退/重复点击）→ 入栈去重、不重复提示
    if (pushJoinedSpot(next)) {
      message.info(`已加入「${name}」，生成行程时会优先安排该景点`);
    }
  }
}

/** 组装特殊要求：用户备注（原样保留）+ 多行点名景点（去重、不覆盖用户已填内容） */
function buildSpecialNotes(): string {
  const parts: string[] = [];
  if (formState.notes.trim()) {
    parts.push(formState.notes.trim());
  }
  for (const spot of joinedSpots) {
    if (!spot.name) continue;
    const line = `务必安排：${spot.name}`;
    if (!parts.some((t) => t.includes(`务必安排：${spot.name}`))) {
      parts.push(line);
    }
  }
  return parts.join("\n");
}

onMounted(applyPlanPrefill);
// 同页内 query 变化（如浏览器前进/后退到另一个"加入行程"目标）也重新预填
watch(() => route.query, applyPlanPrefill);

function togglePreference(list: string[], value: string) {
  const idx = list.indexOf(value);
  if (idx >= 0) {
    list.splice(idx, 1);
  } else {
    list.push(value);
  }
}

function handleSubmit() {
  if (!formState.destination.trim()) {
    message.warning("请填写目的地城市");
    return;
  }

  const payload: TripRequestPayload = {
    destination: formState.destination,
    start_date: formState.startDate,
    end_date: formState.endDate,
    travelers: formState.travelers,
    budget: formState.budget,
    preferences: formState.preferences,
    pace: formState.pace,
    dietary_preferences: formState.dietaryPreferences,
    hotel_level: formState.hotelLevel,
    special_notes: buildSpecialNotes(),
  };

  // 写入工作区入参，跳到 agent 实时视图，边生成边展示思考过程
  beginLive(payload);
  void router.push({ name: "agent" });
}
</script>

<template>
  <section class="plan-page">
    <div class="plan-head">
      <h2 class="plan-head__title">✈️ 生成我的行程</h2>
      <p class="plan-head__desc">告诉我想去哪、怎么玩 —— 提交后实时展示 AI 思考过程</p>
    </div>

    <!-- 草稿恢复提示：让"内容还在"这件事被看见（否则用户会以为是缓存出错），并给出明确的清空入口 -->
    <div v-if="showDraftTip" class="draft-tip">
      <span class="draft-tip__text">
        已恢复上次填写的内容（退出登录或关闭浏览器后自动清空）
      </span>
      <button type="button" class="draft-tip__clear" @click="resetDraft">清空重填</button>
    </div>

    <!-- 目的地与日期 -->
    <div class="ios-card">
      <div class="ios-card__header">
        <span class="ios-card__icon">📍</span>
        <span class="ios-card__title">目的地与日期</span>
      </div>

      <div class="ios-form-row">
        <div class="ios-field ios-field--full">
          <label class="ios-label">目的地城市</label>
          <input v-model="formState.destination" class="ios-input" placeholder="例如：大理、三亚、成都" />
        </div>
      </div>

      <div class="ios-form-row ios-form-row--3col">
        <div class="ios-field">
          <label class="ios-label">开始日期</label>
          <a-date-picker
            v-model:value="startDate"
            :disabled-date="disabledStartDate"
            class="ios-input"
            style="width: 100%"
            placeholder="选择开始日期"
          />
        </div>
        <div class="ios-field">
          <label class="ios-label">结束日期</label>
          <a-date-picker
            v-model:value="endDate"
            :disabled-date="disabledEndDate"
            class="ios-input"
            style="width: 100%"
            placeholder="选择结束日期"
          />
        </div>
        <div class="ios-field">
          <label class="ios-label">人数</label>
          <input v-model.number="formState.travelers" type="number" class="ios-input" min="1" />
        </div>
      </div>

      <div class="ios-info-row">
        <span class="ios-info-label">旅行天数</span>
        <div class="ios-day-count">
          <input
            v-model.number="dayCount"
            type="number"
            class="ios-input ios-input--sm"
            min="1"
            max="7"
          />
          <span class="ios-day-unit">天</span>
        </div>
      </div>
    </div>

    <!-- 已加入景点（多景点累加：可点多个景点卡一并规划；P0-2 + GPT 建议） -->
    <div v-if="joinedSpots.length" class="joined-spot">
      <span class="joined-spot__label">已加入景点</span>
      <div class="joined-spot__chips">
        <span v-for="s in joinedSpots" :key="s.name" class="joined-spot__chip">
          <span class="joined-spot__pin">📍</span>
          {{ s.name }}
          <button
            type="button"
            class="joined-spot__remove"
            :aria-label="`移除已加入景点 ${s.name}`"
            @click="removeJoinedSpot(s.name)"
          >✕</button>
        </span>
      </div>
      <span class="joined-spot__hint">生成行程时会优先安排这些景点</span>
    </div>

    <!-- 偏好设置 -->
    <div class="ios-card">
      <div class="ios-card__header">
        <span class="ios-card__icon">⚙️</span>
        <span class="ios-card__title">偏好设置</span>
      </div>

      <div class="ios-form-row ios-form-row--3col">
        <div class="ios-field">
          <label class="ios-label">节奏偏好</label>
          <select v-model="formState.pace" class="ios-select">
            <option value="轻松">轻松</option>
            <option value="适中">适中</option>
            <option value="紧凑">紧凑</option>
          </select>
        </div>
        <div class="ios-field">
          <label class="ios-label">住宿偏好</label>
          <select v-model="formState.hotelLevel" class="ios-select">
            <option value="舒适型">舒适型</option>
            <option value="高档型">高档型</option>
            <option value="经济型">经济型</option>
          </select>
        </div>
        <div class="ios-field">
          <label class="ios-label">预算（元）</label>
          <input v-model.number="formState.budget" type="number" class="ios-input" min="0" />
        </div>
      </div>

      <div class="ios-field" style="margin-top: 16px">
        <label class="ios-label">旅行偏好</label>
        <div class="ios-chips">
          <button
            v-for="opt in preferenceOptions"
            :key="opt"
            :class="['ios-chip', { 'ios-chip--active': formState.preferences.includes(opt) }]"
            @click="togglePreference(formState.preferences, opt)"
          >
            {{ opt }}
          </button>
        </div>
      </div>

      <div class="ios-field" style="margin-top: 16px">
        <label class="ios-label">饮食偏好</label>
        <div class="ios-chips">
          <button
            v-for="opt in dietaryOptions"
            :key="opt"
            :class="['ios-chip', { 'ios-chip--active': formState.dietaryPreferences.includes(opt) }]"
            @click="togglePreference(formState.dietaryPreferences, opt)"
          >
            {{ opt }}
          </button>
        </div>
      </div>
    </div>

    <!-- 额外要求 -->
    <div class="ios-card">
      <div class="ios-card__header">
        <span class="ios-card__icon">💬</span>
        <span class="ios-card__title">额外要求</span>
      </div>
      <textarea
        v-model="formState.notes"
        class="ios-textarea"
        rows="3"
        placeholder="例如：不想太早起床，希望安排适合看日落的地点"
      />
    </div>

    <!-- 提交 -->
    <div class="submit-area">
      <button class="ios-button ios-button--primary" @click="handleSubmit">
        开始规划
      </button>
      <p class="submit-hint">提交后会实时展示 AI 思考过程</p>
    </div>
  </section>
</template>

<style scoped>
.plan-page {
  display: grid;
  gap: 12px;
}

.plan-head {
  padding: 6px 2px 2px;
}

.plan-head__title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
}

.plan-head__desc {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--text-muted);
}

/* 草稿恢复提示条：与下方表单卡片区分开，浅卡片 + 细边框，不抢主流程视觉 */
.draft-tip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border-radius: 10px;
  background: var(--surface-white);
  border: 1px solid var(--border-soft);
}

.draft-tip__text {
  font-size: 13px;
  color: var(--text-muted);
}

.draft-tip__clear {
  flex: none;
  border: none;
  padding: 0;
  background: transparent;
  font-size: 13px;
  font-weight: 600;
  color: var(--brand-teal);
  cursor: pointer;
}

.draft-tip__clear:hover {
  text-decoration: underline;
}

/* iOS 卡片 */
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
}

.ios-card__icon {
  font-size: 17px;
}

.ios-card__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

/* 已加入景点提示条（P0-2：加入行程闭环 + GPT 建议：多景点累加一起规划） */
.joined-spot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: 12px;
  background: rgba(47, 119, 112, 0.08);
  border: 1px dashed rgba(47, 119, 112, 0.4);
}

.joined-spot__label {
  font-size: 12px;
  font-weight: 600;
  color: var(--brand-deep);
}

.joined-spot__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.joined-spot__chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 6px 4px 10px;
  border-radius: 999px;
  background: var(--brand-teal);
  color: var(--surface-white);
  font-size: 13px;
  font-weight: 600;
}

.joined-spot__pin {
  font-size: 12px;
}

.joined-spot__remove {
  border: none;
  border-radius: 50%;
  width: 18px;
  height: 18px;
  line-height: 1;
  background: rgba(255, 255, 255, 0.22);
  color: var(--surface-white);
  font-size: 11px;
  cursor: pointer;
  padding: 0;
}

.joined-spot__remove:hover {
  background: rgba(255, 255, 255, 0.38);
}

.joined-spot__hint {
  font-size: 12px;
  color: var(--brand-deep);
}

/* iOS 表单 */
.ios-form-row {
  display: grid;
  gap: 12px;
}

.ios-form-row--3col {
  grid-template-columns: repeat(3, 1fr);
}

.ios-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ios-field--full {
  grid-column: 1 / -1;
}

.ios-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-muted);
}

.ios-input,
.ios-select {
  height: 36px;
  padding: 0 12px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface-white);
  font-size: 15px;
  color: var(--text-primary);
  outline: none;
  transition: border-color 0.2s ease;
}

.ios-input:focus,
.ios-select:focus {
  border-color: var(--brand-teal);
}

.ios-textarea {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface-white);
  font-size: 15px;
  color: var(--text-primary);
  outline: none;
  resize: vertical;
  transition: border-color 0.2s ease;
  font-family: inherit;
}

.ios-textarea:focus {
  border-color: var(--brand-teal);
}

/* iOS 信息行 */
.ios-info-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 0.5px solid rgba(0, 0, 0, 0.06);
}

.ios-info-label {
  font-size: 13px;
  color: var(--text-muted);
}

.ios-day-count {
  display: flex;
  align-items: center;
  gap: 6px;
}

.ios-input--sm {
  width: 70px;
  text-align: center;
}

.ios-day-unit {
  font-size: 14px;
  color: var(--text-primary);
  font-weight: 500;
}

/* iOS Chip 标签 */
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
}

.ios-chip:active {
  transform: scale(0.97);
}

.ios-chip--active {
  border-color: var(--brand-teal);
  background: var(--brand-teal);
  color: var(--surface-white);
}

/* iOS 按钮 */
.ios-button {
  border: none;
  border-radius: 12px;
  padding: 14px 32px;
  font-size: 17px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
}

.ios-button:active {
  transform: scale(0.97);
}

.ios-button--primary {
  background: var(--brand-coral);
  color: var(--surface-white);
}

.ios-button--primary:disabled {
  opacity: 0.5;
  cursor: wait;
}

/* 提交区 */
.submit-area {
  text-align: center;
  padding: 8px 0;
}

.submit-hint {
  margin-top: 10px;
  font-size: 13px;
  color: var(--text-muted);
}

@media (max-width: 768px) {
  .ios-form-row--3col {
    grid-template-columns: 1fr;
  }
}
</style>
