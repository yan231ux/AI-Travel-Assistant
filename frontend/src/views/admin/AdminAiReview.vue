<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { message } from "ant-design-vue";

import type { ModerationTask } from "../../services/api";
import {
  decideModerationTask,
  getModerationTask,
  listModerationTasks,
  retryModerationTask,
} from "../../services/api";

/**
 * AI 内容审核页（设计方案 §4.7 /admin/content/ai-review，阶段三）：
 * 队列列表（内容类型/风险等级/风险分/规则命中数/AI 决策）→ 详情
 * （原文 + 规则命中 + AI 风险解释）→ 人工决策（覆盖 AI 结论必须填原因）→ 失败重试。
 */
const tasks = ref<ModerationTask[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = 12;
const loading = ref(true);
const statusFilter = ref<string>("");
const typeFilter = ref<string>("");

const detail = ref<ModerationTask | null>(null);
const detailLoading = ref(false);

const rejectOpen = ref(false);
const rejectReason = ref("");
const deciding = ref(false);
const retryingId = ref<number | null>(null);

const STATUS_LABEL: Record<string, string> = {
  PENDING: "排队中",
  RUNNING: "AI 初筛中",
  PASSED: "已自动放行",
  REVIEW: "待人工复核",
  FAILED: "AI 失败",
};
const TYPE_LABEL: Record<string, string> = {
  POST: "帖子",
  COMMENT: "评论",
  GUIDE: "攻略",
  SPOT: "景点",
};
const RISK_LABEL: Record<string, string> = {
  LOW: "低风险",
  MEDIUM: "中风险",
  HIGH: "高风险",
  CRITICAL: "严重",
};

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)));

async function load() {
  loading.value = true;
  try {
    const resp = await listModerationTasks({
      status: statusFilter.value || undefined,
      targetType: typeFilter.value || undefined,
      page: page.value,
      pageSize,
    });
    tasks.value = resp.items;
    total.value = resp.total;
  } catch {
    tasks.value = [];
    total.value = 0;
    message.error("AI 审核队列加载失败");
  } finally {
    loading.value = false;
  }
}

function switchFilter() {
  page.value = 1;
  void load();
}

async function openDetail(t: ModerationTask) {
  detailLoading.value = true;
  detail.value = null;
  try {
    detail.value = await getModerationTask(t.id);
    if (!detail.value) {
      message.error("任务详情不存在");
    }
  } catch {
    message.error("任务详情加载失败");
  } finally {
    detailLoading.value = false;
  }
}

function closeDetail() {
  detail.value = null;
}

function askReject() {
  rejectReason.value = "";
  rejectOpen.value = true;
}

async function confirmReject() {
  if (!detail.value) return;
  if (!rejectReason.value.trim()) {
    message.warning("覆盖 AI 结论必须填写原因");
    return;
  }
  deciding.value = true;
  try {
    await decideModerationTask(detail.value.id, "REJECT", rejectReason.value.trim());
    message.success("已拒绝并留痕");
    rejectOpen.value = false;
    const id = detail.value.id;
    detail.value = await getModerationTask(id);
    await load();
  } catch {
    message.error("决策失败（内容可能已被处理）");
  } finally {
    deciding.value = false;
  }
}

async function approveTask() {
  if (!detail.value) return;
  deciding.value = true;
  try {
    await decideModerationTask(detail.value.id, "APPROVE");
    message.success("已通过");
    const id = detail.value.id;
    detail.value = await getModerationTask(id);
    await load();
  } catch {
    message.error("决策失败（内容可能已被处理）");
  } finally {
    deciding.value = false;
  }
}

async function retryTask(t: ModerationTask) {
  retryingId.value = t.id;
  try {
    await retryModerationTask(t.id);
    message.success("已重新排队");
    await load();
  } catch {
    message.error("重试失败");
  } finally {
    retryingId.value = null;
  }
}

function riskBadge(t: ModerationTask): string {
  if (t.risk_level === "HIGH" || t.risk_level === "CRITICAL") return "ad-badge ad-badge--no";
  if (t.risk_level === "MEDIUM") return "ad-badge ad-badge--warn";
  return "ad-badge ad-badge--ok";
}

function riskText(t: ModerationTask): string {
  const level = t.risk_level ? RISK_LABEL[t.risk_level] || t.risk_level : "—";
  const score = t.risk_score != null ? t.risk_score.toFixed(2) : "";
  return score ? `${level} · ${score}` : level;
}

function statusBadge(t: ModerationTask): string {
  if (t.status === "REVIEW") return "ad-badge ad-badge--warn";
  if (t.status === "PASSED") return "ad-badge ad-badge--ok";
  if (t.status === "FAILED") return "ad-badge ad-badge--no";
  return "ad-badge ad-badge--muted";
}

/** 决策来源文案：区分"AI 自动放行"与人工决策，避免把系统动作说成人工动作 */
function decisionText(t: ModerationTask): string {
  if (t.decision_by === "system:ai") {
    return t.decision === "APPROVE" ? "AI 自动放行" : "AI 自动拦截";
  }
  return t.decision === "APPROVE" ? "人工通过" : "人工拒绝";
}

/** 解析 AI 结构化输出（result_json），解析失败显示原文 */
const detailAi = computed(() => {
  const raw = detail.value?.result_json;
  if (!raw) return null;
  try {
    const start = raw.indexOf("{");
    const end = raw.lastIndexOf("}");
    if (start === -1 || end === -1) return null;
    return JSON.parse(raw.slice(start, end + 1)) as {
      risk_level?: string;
      risk_score?: number;
      decision?: string;
      categories?: { code?: string; confidence?: number; evidence?: string }[];
      suggestion?: string;
    };
  } catch {
    return null;
  }
});

const detailRules = computed(() => {
  const raw = detail.value?.matched_rules_json;
  if (!raw) return [] as { code: string; name: string; matchedText: string; severity: string }[];
  try {
    return JSON.parse(raw) as { code: string; name: string; matchedText: string; severity: string }[];
  } catch {
    return [];
  }
});

onMounted(load);
</script>

<template>
  <div class="ad-card">
    <p class="ad-title">🤖 AI 内容审核 · 初筛队列（{{ total }}）</p>
    <p class="ad-sub">
      规则命中高危直接转人工；AI 结构化初筛按阈值聚合（≥0.90 必须人工复核 / 0.65~0.90 进队列 /
      &lt;0.65 且规则通过自动放行 / AI 失败进待审）。AI 结论仅是建议，覆盖必须填原因。
    </p>

    <div class="air-filters">
      <select v-model="statusFilter" class="air-select" @change="switchFilter">
        <option value="">全部状态</option>
        <option value="REVIEW">待人工复核</option>
        <option value="PASSED">已自动放行</option>
        <option value="FAILED">AI 失败</option>
        <option value="RUNNING">初筛中</option>
      </select>
      <select v-model="typeFilter" class="air-select" @change="switchFilter">
        <option value="">全部类型</option>
        <option value="POST">帖子</option>
        <option value="COMMENT">评论</option>
        <option value="GUIDE">攻略</option>
        <option value="SPOT">景点</option>
      </select>
      <button class="ad-btn" @click="load">刷新</button>
    </div>

    <div v-if="loading" class="ad-empty">加载中…</div>
    <div v-else-if="tasks.length === 0" class="ad-empty">✅ 没有符合条件的审核任务</div>
    <div v-else class="ad-list">
      <div v-for="t in tasks" :key="t.id" class="ad-item">
        <div class="ad-item__main" role="button" tabindex="0" @click="openDetail(t)">
          <p class="ad-item__title">
            #{{ t.id }} {{ TYPE_LABEL[t.target_type] || t.target_type }} ·
            {{ t.content_title || "（无标题）" }}
            <span :class="statusBadge(t)">{{ STATUS_LABEL[t.status] || t.status }}</span>
            <span :class="riskBadge(t)">{{ riskText(t) }}</span>
            <span v-if="t.rule_hit_count > 0" class="ad-badge ad-badge--warn">
              规则命中 {{ t.rule_hit_count }}
            </span>
            <!-- 结论徽章只在「真有人工决策」时显示：自动放行由 system:ai 写入 decision，
                 但其结论已由状态徽章「已自动放行」表达，此处若再写死「人工」会与状态打架。 -->
            <span v-if="t.decision && t.decision_by !== 'system:ai'" class="ad-badge ad-badge--info">
              人工{{ t.decision === "APPROVE" ? "通过" : "拒绝" }}
            </span>
          </p>
          <p class="ad-item__meta">
            目标 #{{ t.target_id }} · {{ t.city || "未填城市" }} ·
            {{ t.model_name ? `${t.model_name}@${t.prompt_version || "v1"}` : "规则直判" }} ·
            {{ (t.created_at || "").slice(0, 16) }}
          </p>
        </div>
        <div class="ad-item__ops">
          <button
            v-if="t.status === 'FAILED' || t.status === 'REVIEW'"
            class="ad-btn"
            :disabled="retryingId === t.id"
            @click.stop="retryTask(t)"
          >
            重试
          </button>
          <button class="ad-btn ad-btn--primary" @click.stop="openDetail(t)">详情</button>
        </div>
      </div>
    </div>

    <div class="air-pager" v-if="totalPages > 1">
      <button class="ad-btn" :disabled="page <= 1" @click="page--; load()">上一页</button>
      <span>{{ page }} / {{ totalPages }}</span>
      <button class="ad-btn" :disabled="page >= totalPages" @click="page++; load()">下一页</button>
    </div>
  </div>

  <!-- 详情抽屉（模态） -->
  <div v-if="detailLoading || detail" class="air-drawer" @click.self="closeDetail">
    <div class="air-drawer__box">
      <div v-if="detailLoading" class="ad-empty">加载详情中…</div>
      <template v-else-if="detail">
        <div class="air-detail__head">
          <p class="ad-title">任务 #{{ detail.id }} · {{ TYPE_LABEL[detail.target_type] }} #{{ detail.target_id }}</p>
          <button class="ad-btn" @click="closeDetail">收起</button>
        </div>
        <p class="ad-sub">
          状态：{{ STATUS_LABEL[detail.status] }} · 风险：{{ riskText(detail) }} ·
          {{ detail.model_name ? `模型 ${detail.model_name}（${detail.prompt_version}）` : "规则直判" }}
          <template v-if="detail.decision">
            · {{ decisionText(detail) }}（{{ detail.decision_by }}）
          </template>
        </p>
        <p v-if="detail.error_message" class="air-error">⚠️ {{ detail.error_message }}</p>
        <p v-if="detail.decision_reason" class="air-reason">决策原因：{{ detail.decision_reason }}</p>

        <div class="air-block">
          <p class="air-block__title">原文</p>
          <p class="air-content">{{ detail.content_title || "（无标题）" }}</p>
          <p class="air-content air-content--text">{{ detail.content_text || "（无正文）" }}</p>
        </div>

        <div class="air-block" v-if="detailRules.length">
          <p class="air-block__title">规则命中（{{ detailRules.length }}）</p>
          <p v-for="r in detailRules" :key="r.code" class="air-content">
            <span :class="r.severity === 'HIGH' ? 'ad-badge ad-badge--no' : 'ad-badge ad-badge--warn'">
              {{ r.severity }}
            </span>
            {{ r.name }} —— 命中「{{ r.matchedText }}」
          </p>
        </div>
        <div class="air-block" v-else>
          <p class="air-block__title">规则命中</p>
          <p class="air-content">无（规则通过）</p>
        </div>

        <div class="air-block">
          <p class="air-block__title">AI 初筛结论</p>
          <template v-if="detailAi">
            <p class="air-content">
              决策 {{ detailAi.decision }} · 风险 {{ detailAi.risk_level }}（{{ detailAi.risk_score }}）
            </p>
            <p v-for="(c, i) in detailAi.categories || []" :key="i" class="air-content">
              · {{ c.code }}（置信度 {{ c.confidence }}）：{{ c.evidence }}
            </p>
            <p v-if="detailAi.suggestion" class="air-content">建议：{{ detailAi.suggestion }}</p>
          </template>
          <p v-else class="air-content">无结构化结论{{ detail.status === "REVIEW" ? "（转人工复核）" : "" }}</p>
        </div>

        <!-- 操作区（按"能否改变内容状态"分派，而不是一律隐藏） -->
        <div class="air-actions" v-if="detail.status === 'PASSED' && detail.decision">
          <button class="ad-btn ad-btn--danger" :disabled="deciding" @click="askReject">改判拒绝并下架…</button>
        </div>
        <div class="air-actions" v-else-if="!detail.decision">
          <button class="ad-btn ad-btn--primary" :disabled="deciding" @click="approveTask">通过</button>
          <button class="ad-btn ad-btn--danger" :disabled="deciding" @click="askReject">拒绝…</button>
        </div>
        <p v-if="detail.decision" class="ad-sub">
          {{ detail.decision_by === "system:ai"
            ? "本任务由 AI 自动放行，内容已上线；如判定有误可改判下架（留痕）。"
            : "该任务已完成人工决策（留痕）。" }}
        </p>
      </template>
    </div>
  </div>

  <!-- 拒绝原因弹层 -->
  <div v-if="rejectOpen" class="air-modal" @click.self="rejectOpen = false">
    <div class="air-modal__box">
      <p class="air-block__title">拒绝原因（必填，随审计留痕）</p>
      <textarea v-model="rejectReason" class="air-textarea" rows="3"
                placeholder="例如：正文含微信号导流，违反社区规范" />
      <div class="air-actions">
        <button class="ad-btn" :disabled="deciding" @click="rejectOpen = false">取消</button>
        <button class="ad-btn ad-btn--danger" :disabled="deciding" @click="confirmReject">确认拒绝</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.air-filters {
  display: flex;
  gap: 8px;
  margin: 12px 0;
  flex-wrap: wrap;
}
.air-select {
  padding: 6px 10px;
  border: 1px solid var(--border, #ddd);
  border-radius: 8px;
  background: transparent;
  color: inherit;
}
.air-pager {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: center;
  margin-top: 12px;
}
.air-detail__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.air-error {
  color: #d4380d;
  font-size: 13px;
}
.air-reason {
  color: inherit;
  opacity: 0.75;
  font-size: 13px;
}
.air-block {
  margin-top: 12px;
  padding-top: 8px;
  border-top: 1px dashed var(--border, #ddd);
}
.air-block__title {
  font-weight: 600;
  margin-bottom: 4px;
}
.air-content {
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
.air-content--text {
  max-height: 320px;
  overflow: auto;
}
.air-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
.ad-btn--primary {
  border-color: #1677ff;
  color: #1677ff;
}
.ad-btn--danger {
  border-color: #d4380d;
  color: #d4380d;
}
.ad-btn--warn {
  border-color: #d48806;
}
.air-modal {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 80;
}
.air-drawer {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  justify-content: flex-end;
  z-index: 70;
}
.air-drawer__box {
  width: min(560px, 94vw);
  height: 100vh;
  overflow-y: auto;
  background: var(--bg, #fff);
  color: inherit;
  padding: 20px;
  box-shadow: -4px 0 20px rgba(0, 0, 0, 0.12);
}
.air-modal__box {
  width: min(460px, 92vw);
  background: var(--bg, #fff);
  color: inherit;
  border-radius: 12px;
  padding: 16px;
}
.air-textarea {
  width: 100%;
  margin-top: 8px;
  padding: 8px;
  border: 1px solid var(--border, #ddd);
  border-radius: 8px;
  background: transparent;
  color: inherit;
  resize: vertical;
}
</style>
