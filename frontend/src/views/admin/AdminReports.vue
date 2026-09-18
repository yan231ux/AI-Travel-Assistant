<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";

import type { ReportItem } from "../../types";
import { getPendingReports, getReportHistory } from "../../services/api";

/** 举报中心（设计方案 §3.4：待处理 + 历史；点条目进入证据详情页） */
const router = useRouter();
const seg = ref<"pending" | "history">("pending");
const reports = ref<ReportItem[]>([]);
const loading = ref(true);

function openReport(r: ReportItem) {
  void router.push({ name: "admin-report-detail", params: { id: String(r.id) } });
}

async function load() {
  loading.value = true;
  try {
    reports.value =
      seg.value === "pending"
        ? await getPendingReports(1, 50)
        : await getReportHistory(1, 50);
  } catch {
    reports.value = [];
  } finally {
    loading.value = false;
  }
}

function switchSeg(next: "pending" | "history") {
  seg.value = next;
  void load();
}

onMounted(load);
</script>

<template>
  <div class="ad-card">
    <div class="ad-form-row" style="justify-content: space-between">
      <p class="ad-title">举报中心</p>
      <div class="ad-seg">
        <button type="button" :class="['ad-seg__btn', { 'ad-seg__btn--on': seg === 'pending' }]" @click="switchSeg('pending')">
          待处理（{{ reports.length }}）
        </button>
        <button type="button" :class="['ad-seg__btn', { 'ad-seg__btn--on': seg === 'history' }]" @click="switchSeg('history')">
          处理历史
        </button>
      </div>
    </div>
    <p class="ad-sub">点击条目查看被举报内容完整正文、上下文、处理备注与动作记录。</p>

    <div v-if="loading" class="ad-empty">加载中…</div>
    <div v-else-if="reports.length === 0" class="ad-empty">
      {{ seg === "pending" ? "没有待处理的举报 🎉" : "还没有处理过举报" }}
    </div>
    <div v-else class="ad-list">
      <div v-for="r in reports" :key="r.id" class="ad-item">
        <div class="ad-item__main" role="button" tabindex="0" @click="openReport(r)">
          <p class="ad-item__title">
            {{ r.target_type === "POST" ? "帖子" : "评论" }}：{{ r.target_snapshot }}
            <span
              class="ad-badge"
              :class="r.status === 'RESOLVED' ? 'ad-badge--ok' : r.status === 'DISMISSED' ? 'ad-badge--no' : 'ad-badge--warn'"
            >
              {{ r.status === "RESOLVED" ? "已成立" : r.status === "DISMISSED" ? "已驳回" : "待处理" }}
            </span>
          </p>
          <p class="ad-item__meta">
            原因：{{ r.reason }}
            <template v-if="r.detail"> · 补充：{{ r.detail }}</template>
            · 举报人 @{{ r.reporter_name }} · {{ (r.created_at || "").slice(0, 16) }}
            <template v-if="r.status && r.status !== 'PENDING'">
              <template v-if="r.handled_by"> · 处理人 @{{ r.handled_by }} · {{ (r.handled_at || "").slice(0, 16) }}</template>
              <template v-if="r.handle_note"> · 备注：{{ r.handle_note }}</template>
            </template>
          </p>
        </div>
        <div class="ad-ops">
          <button type="button" class="ad-btn ad-btn--link" @click="openReport(r)">证据详情 ›</button>
        </div>
      </div>
    </div>
  </div>
</template>
