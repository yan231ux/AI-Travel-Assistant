<script setup lang="ts">
import { message } from "ant-design-vue";
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";

import { confirmVisited, deleteTrip, getTripDetail, listTrips, unconfirmVisited } from "../services/api";
import { openSaved } from "../stores/trip";
import type { TripSummaryItem } from "../types";

const router = useRouter();

const loading = ref(false);
const items = ref<TripSummaryItem[]>([]);
const deletingTripId = ref("");
const confirmingTripId = ref("");
const celebratingCity = ref<string | null>(null);

async function loadTrips() {
  loading.value = true;
  try {
    const response = await listTrips();
    items.value = response.items;
  } catch (error) {
    console.error(error);
    message.error("历史列表加载失败。");
  } finally {
    loading.value = false;
  }
}

/** 打开已保存行程 → 写入工作区并跳结果页 */
async function viewTrip(tripId: string) {
  try {
    const response = await getTripDetail(tripId);
    openSaved(response.itinerary, response.trace);
    void router.push({ name: "result" });
    message.success("已加载已保存行程。");
  } catch (error) {
    console.error(error);
    message.error("读取行程详情失败。");
  }
}

async function removeTrip(tripId: string) {
  const confirmed = window.confirm("确定要删除这条已保存行程吗？删除后无法恢复。");
  if (!confirmed) return;

  deletingTripId.value = tripId;
  try {
    await deleteTrip(tripId);
    items.value = items.value.filter((item) => item.trip_id !== tripId);
    message.success("行程已删除。");
  } catch (error) {
    console.error(error);
    message.error("删除行程失败。");
  } finally {
    deletingTripId.value = "";
  }
}

/** 确认去过：置 visited_confirmed=1，成功弹祝福 + 询问发帖推荐 */
async function confirmVisit(item: TripSummaryItem) {
  if (item.future) {
    message.info("行程还未出发，出发后再来确认去过吧。");
    return;
  }
  if (confirmingTripId.value) return;
  confirmingTripId.value = item.trip_id;
  try {
    const res = await confirmVisited(item.trip_id);
    item.confirmed_visited = true;
    celebratingCity.value = res.city || item.destination || "";
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
    message.error(msg || "确认失败，请稍后重试。");
  } finally {
    confirmingTripId.value = "";
  }
}

/** 撤销"确认去过"（幂等） */
async function undoVisit(item: TripSummaryItem) {
  if (confirmingTripId.value) return;
  confirmingTripId.value = item.trip_id;
  try {
    await unconfirmVisited(item.trip_id);
    item.confirmed_visited = false;
    message.success("已撤销去过标记。");
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
    message.error(msg || "撤销失败，请稍后重试。");
  } finally {
    confirmingTripId.value = "";
  }
}

/** 祝福弹窗 → 去发帖（带城市预填） */
function goPost() {
  const city = celebratingCity.value;
  celebratingCity.value = null;
  router.push({ name: "post-create", query: city ? { city } : {} });
}

function closeCelebrate() {
  celebratingCity.value = null;
}

// 每次进入历史页都会重新挂载，直接加载即可
onMounted(() => {
  void loadTrips();
});
</script>

<template>
  <section class="history-page">
    <!-- 头部 -->
    <div class="ios-card history-header">
      <div>
        <h2 class="history-header__title">历史行程</h2>
        <p class="history-header__desc">已保存到数据库的行程记录</p>
      </div>
      <button class="ios-btn ios-btn--primary ios-btn--sm" @click="loadTrips">刷新</button>
    </div>

    <!-- 状态 -->
    <div v-if="loading" class="ios-card ios-empty">正在加载...</div>
    <div v-else-if="items.length === 0" class="ios-card ios-empty">还没有已保存的行程</div>

    <!-- 列表 -->
    <div v-else class="history-grid">
      <div v-for="item in items" :key="item.trip_id" class="ios-card history-card">
        <div class="history-card__dest">
          {{ item.destination }}
          <span v-if="item.confirmed_visited" class="visit-badge visit-badge--done">✓ 已去过</span>
          <span v-else-if="item.future" class="visit-badge visit-badge--future">未出发</span>
        </div>
        <div class="history-card__id">{{ item.trip_id }}</div>
        <p class="history-card__summary">{{ item.summary }}</p>
        <div class="history-card__time">{{ item.updated_at || "未记录" }}</div>
        <div class="history-card__actions">
          <button
            v-if="!item.confirmed_visited"
            class="ios-btn ios-btn--primary ios-btn--sm"
            :disabled="confirmingTripId === item.trip_id || !!item.future"
            @click="confirmVisit(item)"
          >
            {{ item.future ? "未出发" : (confirmingTripId === item.trip_id ? "确认中..." : "✓ 确认去过") }}
          </button>
          <button
            v-else
            class="ios-btn history-btn--ghost ios-btn--sm"
            :disabled="confirmingTripId === item.trip_id"
            @click="undoVisit(item)"
          >
            撤销去过
          </button>
          <button class="ios-btn ios-btn--primary ios-btn--sm" @click="viewTrip(item.trip_id)">查看详情</button>
          <button class="ios-btn ios-btn--danger ios-btn--sm" :disabled="deletingTripId === item.trip_id" @click="removeTrip(item.trip_id)">
            {{ deletingTripId === item.trip_id ? "删除中..." : "删除" }}
          </button>
        </div>
      </div>
    </div>

    <!-- 确认去过 → 庆祝弹窗 + 发帖引导 -->
    <div v-if="celebratingCity" class="celebrate-mask" @click.self="closeCelebrate">
      <div class="celebrate-card">
        <div class="celebrate-emoji">🎉</div>
        <h3 class="celebrate-title">恭喜打卡 {{ celebratingCity }}！</h3>
        <p class="celebrate-desc">这座城市的记忆已经收进你的旅行足迹啦～要不要分享一篇攻略，推荐给更多旅行者？</p>
        <div class="celebrate-actions">
          <button class="ios-btn ios-btn--primary" @click="goPost">去发帖推荐</button>
          <button class="ios-btn celebrate-btn--ghost" @click="closeCelebrate">稍后再说</button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.history-page {
  display: grid;
  gap: 12px;
}

/* 头部 */
.history-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.history-header__title {
  margin: 0 0 4px;
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
}

.history-header__desc {
  margin: 0;
  font-size: 14px;
  color: var(--text-muted);
}

/* 卡片 */
.ios-card {
  padding: 20px;
  border-radius: 12px;
  background: var(--surface-white);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.ios-empty {
  text-align: center;
  color: var(--text-muted);
  font-size: 14px;
  padding: 40px 20px;
}

/* 按钮 */
.ios-btn {
  border: none;
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.ios-btn:active { transform: scale(0.97); }
.ios-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.ios-btn--primary { background: var(--brand-teal); color: var(--surface-white); }
.ios-btn--danger { background: var(--danger); color: var(--surface-white); }
.ios-btn--sm { padding: 6px 14px; font-size: 13px; }

/* 列表 */
.history-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 12px;
}

.history-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.history-card__dest {
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
}

.history-card__id {
  font-size: 12px;
  color: var(--text-muted);
  word-break: break-all;
}

.history-card__summary {
  margin: 0;
  font-size: 14px;
  color: var(--text-secondary);
  line-height: 1.6;
}

.history-card__time {
  font-size: 12px;
  color: var(--text-muted);
}

.history-card__actions {
  display: flex;
  gap: 8px;
  margin-top: auto;
  padding-top: 12px;
  border-top: 0.5px solid rgba(0, 0, 0, 0.06);
  flex-wrap: wrap;
}

.visit-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  margin-left: 8px;
  vertical-align: middle;
  font-weight: 500;
}
.visit-badge--done {
  color: var(--brand-teal);
  background: rgba(29, 158, 117, 0.1);
}
.visit-badge--future {
  color: var(--text-muted);
  background: rgba(0, 0, 0, 0.05);
}

.history-btn--ghost {
  background: rgba(0, 0, 0, 0.05);
  color: var(--text-secondary);
}

.celebrate-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: grid;
  place-items: center;
  z-index: 1000;
}
.celebrate-card {
  background: var(--surface-white, #fff);
  border-radius: 16px;
  padding: 28px 24px;
  max-width: 360px;
  width: 90%;
  text-align: center;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.celebrate-emoji {
  font-size: 40px;
  line-height: 1;
}
.celebrate-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
}
.celebrate-desc {
  margin: 0;
  font-size: 14px;
  color: var(--text-secondary);
  line-height: 1.7;
}
.celebrate-actions {
  display: flex;
  gap: 10px;
  justify-content: center;
  margin-top: 4px;
}
.celebrate-btn--ghost {
  background: rgba(0, 0, 0, 0.05);
  color: var(--text-secondary);
}

@media (max-width: 768px) {
  .history-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
}
</style>
