<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import { message } from "ant-design-vue";
import dayjs, { type Dayjs } from "dayjs";

import type { TripRequestPayload } from "../types";

const emit = defineEmits<{
  startGenerate: [payload: TripRequestPayload];
}>();

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

const today = new Date();
const todayPlus2 = new Date(today);
todayPlus2.setDate(todayPlus2.getDate() + 2);

const formState = reactive({
  destination: "",
  startDate: formatDate(today),
  endDate: formatDate(todayPlus2),
  travelers: 2,
  budget: 3200,
  hotelLevel: "舒适型",
  pace: "轻松",
  preferences: [] as string[],
  dietaryPreferences: [] as string[],
  notes: "",
});

const MAX_DAYS = 7;

function calcDays(start: string, end: string): number {
  const s = new Date(start);
  const e = new Date(end);
  const diff = e.getTime() - s.getTime();
  const d = Math.floor(diff / 86400000) + 1;
  return isNaN(d) || d < 1 ? 1 : Math.min(d, MAX_DAYS);
}

const todayStart = dayjs().startOf("day");

// 日历选择器绑定的 dayjs 值（与 formState 字符串双向联动）
const startDate = ref<Dayjs>(dayjs());
const endDate = ref<Dayjs>(dayjs().add(2, "day"));

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
    special_notes: formState.notes,
  };

  // 立即切换到 agent 实时视图，边生成边展示思考过程
  emit("startGenerate", payload);
}
</script>

<template>
  <section class="home-page">
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
.home-page {
  display: grid;
  gap: 12px;
}

/* iOS 卡片 */
.ios-card {
  padding: 20px;
  border-radius: 12px;
  background: #FFFFFF;
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
  color: #1C1C1E;
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
  color: #8E8E93;
}

.ios-input,
.ios-select {
  height: 36px;
  padding: 0 12px;
  border: 1px solid #D1D1D6;
  border-radius: 8px;
  background: #FFFFFF;
  font-size: 15px;
  color: #1C1C1E;
  outline: none;
  transition: border-color 0.2s ease;
}

.ios-input:focus,
.ios-select:focus {
  border-color: #007AFF;
}

.ios-textarea {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #D1D1D6;
  border-radius: 8px;
  background: #FFFFFF;
  font-size: 15px;
  color: #1C1C1E;
  outline: none;
  resize: vertical;
  transition: border-color 0.2s ease;
  font-family: inherit;
}

.ios-textarea:focus {
  border-color: #007AFF;
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
  color: #8E8E93;
}

.ios-badge {
  display: inline-flex;
  align-items: center;
  padding: 4px 12px;
  border-radius: 20px;
  background: #007AFF;
  color: #FFFFFF;
  font-size: 13px;
  font-weight: 600;
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
  color: #1C1C1E;
  font-weight: 500;
}

/* iOS Chip 标签 */
.ios-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.ios-chip {
  border: 1px solid #D1D1D6;
  border-radius: 20px;
  padding: 6px 14px;
  background: #FFFFFF;
  font-size: 13px;
  color: #1C1C1E;
  cursor: pointer;
  transition: all 0.2s ease;
}

.ios-chip:active {
  transform: scale(0.97);
}

.ios-chip--active {
  border-color: #007AFF;
  background: #007AFF;
  color: #FFFFFF;
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
  background: #007AFF;
  color: #FFFFFF;
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
  color: #8E8E93;
}

@media (max-width: 768px) {
  .ios-form-row--3col {
    grid-template-columns: 1fr;
  }
}
</style>
