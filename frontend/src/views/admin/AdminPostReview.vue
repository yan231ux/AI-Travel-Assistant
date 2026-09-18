<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { message } from "ant-design-vue";
import { useRouter } from "vue-router";

import type { ReviewPostDetail } from "../../services/api";
import { approvePost, getPostReviewDetail, hidePost, rejectPost } from "../../services/api";

const props = defineProps<{ id: string }>();
const router = useRouter();
const detail = ref<ReviewPostDetail | null>(null);
const busy = ref(false);

const statusText = computed(() => {
  const s = detail.value?.status || "";
  const map: Record<string, string> = {
    PENDING_REVIEW: "待审核",
    PUBLISHED: "已发布",
    HIDDEN: "已隐藏",
    REJECTED: "已拒绝",
    DRAFT: "草稿",
  };
  const base = map[s] || s;
  return detail.value?.has_pending_revision ? `${base} · 有修改待审` : base;
});

/** 是否存在"已发布帖的待审修改版本"（P1-1 版本化） */
const hasPendingRevision = computed(() => Boolean(detail.value?.has_pending_revision));

function show(v: unknown): string {
  return v === null || v === undefined || v === "" ? "—" : String(v);
}

/**
 * 待审版本相对线上版本的字段级变化（只列变化项）。
 * 审核员据此一眼看出"改了哪里"，不必在两个大文本框之间来回找。
 */
const revisionDiffs = computed(() => {
  const cur = detail.value;
  const rev = cur?.pending_revision;
  if (!cur || !rev) return [] as { label: string; before: string; after: string }[];
  const pairs: { label: string; a: unknown; b: unknown }[] = [
    { label: "标题", a: cur.title, b: rev.title },
    { label: "摘要", a: cur.summary, b: rev.summary },
    { label: "城市", a: cur.city, b: rev.city },
    { label: "天数", a: cur.travel_days, b: rev.travel_days },
    { label: "预算", a: cur.budget, b: rev.budget },
    { label: "节奏", a: cur.pace, b: rev.pace },
    { label: "类型", a: cur.post_type, b: rev.post_type },
  ];
  return pairs
    .filter((p) => show(p.a) !== show(p.b))
    .map((p) => ({ label: p.label, before: show(p.a), after: show(p.b) }));
});

/** 待审版本正文是否与线上不同 */
const revContentChanged = computed(() => {
  const cur = detail.value;
  const rev = cur?.pending_revision;
  return Boolean(rev) && show(rev?.content) !== show(cur?.content);
});

/* 拒绝原因模态 */
const rejecting = ref(false);
const rejectReason = ref("");

function back() {
  void router.push({ name: "admin-review" });
}

async function approve() {
  if (!detail.value || busy.value) return;
  busy.value = true;
  try {
    await approvePost(detail.value.id);
    message.success(hasPendingRevision.value ? "已通过修改，线上内容已切换到新版本" : "已通过并发布");
    await load();
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败");
  } finally {
    busy.value = false;
  }
}

async function confirmReject() {
  if (!detail.value || !rejectReason.value.trim()) return message.warning("请填写拒绝原因（作者会看到）");
  busy.value = true;
  try {
    await rejectPost(detail.value.id, rejectReason.value.trim());
    message.success(hasPendingRevision.value ? "已拒绝修改，线上仍展示原版本" : "已拒绝");
    rejecting.value = false;
    await load();
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败");
  } finally {
    busy.value = false;
  }
}

async function hide() {
  if (!detail.value) return;
  if (!window.confirm(`确认下架「${detail.value.title}」？下架后不在公开流展示。`)) return;
  busy.value = true;
  try {
    await hidePost(detail.value.id);
    message.success("已隐藏下架");
    await load();
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败");
  } finally {
    busy.value = false;
  }
}

async function load() {
  try {
    detail.value = await getPostReviewDetail(Number(props.id));
  } catch {
    detail.value = null;
    message.error("帖子不存在或已删除");
  }
}

onMounted(load);
</script>

<template>
  <div v-if="!detail" class="ad-empty">加载中…</div>
  <div v-else class="ad-review-grid">
    <!-- 左：内容预览 -->
    <section class="ad-card">
      <div class="ad-form-row" style="justify-content: space-between">
        <p class="ad-title">{{ detail.title }}</p>
        <button type="button" class="ad-btn" @click="back">← 返回队列</button>
      </div>
      <p v-if="hasPendingRevision" class="ad-rev-banner">
        ⚠️ 这是「已发布内容的修改版本」审核：线上仍在展示原版本，读者无感；
        点「通过修改」才切换到下方待审版本，「拒绝修改」则线上保持原版本不变。
      </p>
      <p class="ad-item__meta">
        作者 @{{ detail.author.nickname || detail.author.id }} ·
        {{ detail.city || "未填城市" }} · {{ detail.post_type }}
        <span class="ad-badge ad-badge--muted">{{ statusText }}</span>
      </p>
      <p v-if="detail.summary" class="ad-preview-summary">{{ detail.summary }}</p>
      <pre class="ad-preview-content">{{ detail.content }}</pre>
      <p v-if="detail.reject_reason" class="ad-reject-hint">最近拒绝原因：{{ detail.reject_reason }}</p>
    </section>

    <!-- 右：证据与决策 -->
    <aside class="ad-grid" style="align-content: start; gap: 12px">
      <!-- P1-1 版本化：待审修改版本（只在有待审版本时出现） -->
      <div v-if="hasPendingRevision && detail.pending_revision" class="ad-card ad-rev-card">
        <p class="ad-title" style="font-size: 15px">
          待审修改版本 v{{ detail.pending_revision.revision_no }}
          <span class="ad-badge ad-badge--warn">线上仍为原版本</span>
        </p>
        <p class="ad-item__meta">
          提交于 {{ detail.pending_revision.edited_at || "—" }} ·
          作者 @{{ detail.pending_revision.editor || "—" }}<br />
          审核通过后：内容切换为下方版本，发布时间 <b>保持不变</b>（不顶到时间线最前）。
        </p>

        <template v-if="revisionDiffs.length">
          <p class="ad-item__meta" style="margin-top: 8px">字段变化：</p>
          <div class="ad-rev-diff">
            <div v-for="d in revisionDiffs" :key="d.label" class="ad-rev-diff__row">
              <span class="ad-rev-diff__label">{{ d.label }}</span>
              <span class="ad-rev-diff__old">{{ d.before }}</span>
              <span class="ad-rev-diff__arrow">→</span>
              <span class="ad-rev-diff__new">{{ d.after }}</span>
            </div>
          </div>
        </template>

        <p class="ad-item__meta" style="margin-top: 10px">
          {{ revContentChanged ? "正文已修改（见下方）" : "正文未改动" }}
        </p>
        <pre v-if="revContentChanged" class="ad-preview-content ad-preview-content--rev">{{
          detail.pending_revision.content
        }}</pre>

        <div v-if="detail.pending_revision.spots?.length" class="ad-item__meta" style="margin-top: 8px">
          关联景点（{{ detail.pending_revision.spots.length }}）：
          {{ detail.pending_revision.spots.map((s) => s.spot_name).join("、") }}
        </div>
      </div>

      <div class="ad-card">
        <p class="ad-title" style="font-size: 15px">自动规则命中</p>
        <div class="ad-form-row">
          <span class="ad-badge" :class="detail.low_quality ? 'ad-badge--no' : 'ad-badge--ok'">
            {{ detail.low_quality ? "低质命中" : "非低质" }}
          </span>
          <span class="ad-badge ad-badge--info">质量分 {{ detail.quality_score ?? 0 }}/100</span>
          <span class="ad-badge ad-badge--muted">❤️{{ detail.like_count }} ⭐{{ detail.favorite_count }} 💬{{ detail.comment_count }} 👁{{ detail.view_count }}</span>
        </div>
        <p class="ad-item__meta">创建 {{ detail.created_at }}<br />更新 {{ detail.updated_at }}</p>
      </div>

      <div class="ad-card">
        <p class="ad-title" style="font-size: 15px">作者治理信息</p>
        <p class="ad-item__meta">
          @{{ detail.author.nickname || detail.author.id }}
          <span v-if="detail.author.role === 'ADMIN'" class="ad-badge ad-badge--info">管理员</span><br />
          注册于 {{ detail.author.registered_at || "—" }} · 已发布 {{ detail.author.published_posts ?? 0 }} 篇<br />
          历史被举报成立 {{ detail.author.resolved_reports ?? 0 }} 次
        </p>
      </div>

      <div class="ad-card">
        <p class="ad-title" style="font-size: 15px">相关举报（{{ detail.reports.length }}）</p>
        <p v-if="detail.reports.length === 0" class="ad-item__meta">无举报记录</p>
        <div v-for="r in detail.reports" :key="r.id" class="ad-report-row">
          <span class="ad-item__meta">
            #{{ r.id }} · {{ r.reason }}<template v-if="r.detail">：{{ r.detail }}</template> ·
            {{ r.reporter_name }} · {{ r.status === "PENDING" ? "待处理" : r.status === "RESOLVED" ? "已成立" : "已驳回" }}
          </span>
        </div>
      </div>

      <div class="ad-card">
        <p class="ad-title" style="font-size: 15px">处理动作（审核决策）</p>
        <p class="ad-item__meta" style="margin-bottom: 10px">
          审核动作与拒绝原因将写入审计日志；拒绝原因作者可见。
        </p>
        <div class="ad-ops">
          <template v-if="detail.status === 'PENDING_REVIEW'">
            <button type="button" class="ad-btn ad-btn--ok" :disabled="busy" @click="approve">✓ 通过并发布</button>
            <button type="button" class="ad-btn ad-btn--no" :disabled="busy" @click="rejecting = true">✕ 拒绝</button>
          </template>
          <!-- P1-1：已发布帖的修改版本 → 决策针对"修改稿"，线上内容在决策前不受影响 -->
          <template v-else-if="detail.status === 'PUBLISHED' && hasPendingRevision">
            <button type="button" class="ad-btn ad-btn--ok" :disabled="busy" @click="approve">
              ✓ 通过修改（切换到新版本）
            </button>
            <button type="button" class="ad-btn ad-btn--no" :disabled="busy" @click="rejecting = true">
              ✕ 拒绝修改（保留原版本）
            </button>
            <button type="button" class="ad-btn" :disabled="busy" @click="hide">下架整篇</button>
          </template>
          <template v-else-if="detail.status === 'PUBLISHED'">
            <button type="button" class="ad-btn ad-btn--no" :disabled="busy" @click="hide">下架隐藏</button>
          </template>
          <template v-else-if="detail.status === 'HIDDEN'">
            <button type="button" class="ad-btn ad-btn--ok" :disabled="busy" @click="approve">恢复公开</button>
          </template>
          <template v-else>
            <span class="ad-badge ad-badge--muted">非可处理状态</span>
          </template>
        </div>
        <p class="ad-item__meta" style="margin-top: 10px">操作记录：</p>
        <div v-if="detail.audit.length === 0" class="ad-item__meta">暂无</div>
        <div v-for="(a, i) in detail.audit" :key="i" class="ad-item__meta" style="margin-top: 2px">
          · {{ a.created_at }} {{ a.actor }}：{{ a.action }}
        </div>
      </div>
    </aside>

    <!-- 拒绝原因 -->
    <div v-if="rejecting" class="ad-modal">
      <div class="ad-modal__card">
        <p class="ad-modal__title">填写拒绝原因（作者可见）</p>
        <textarea
          v-model="rejectReason"
          class="ad-textarea"
          rows="3"
          maxlength="300"
          placeholder="例如：正文含未核实的门票价格，请补充信息来源后重新提交"
        ></textarea>
        <div class="ad-ops" style="justify-content: flex-end">
          <button type="button" class="ad-btn" @click="rejecting = false">取消</button>
          <button type="button" class="ad-btn ad-btn--no" :disabled="!rejectReason.trim()" @click="confirmReject">
            确认拒绝
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ad-review-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(280px, 1fr);
  gap: 14px;
  align-items: start;
}
.ad-preview-summary {
  font-size: 13px;
  color: #4a4a46;
  background: rgba(47, 119, 112, 0.06);
  border-radius: 8px;
  padding: 8px 10px;
}
.ad-preview-content {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.75;
  font-family: inherit;
  color: #2e2e2b;
  background: #fbfaf7;
  border-radius: 10px;
  padding: 12px;
  margin: 10px 0 0;
}
.ad-reject-hint {
  font-size: 12px;
  color: #c65d51;
  background: rgba(198, 93, 81, 0.06);
  border-radius: 8px;
  padding: 8px 10px;
}
/* P1-1 版本化：待审修改版本 */
.ad-rev-banner {
  font-size: 12.5px;
  line-height: 1.6;
  color: #8a6420;
  background: rgba(201, 138, 45, 0.1);
  border: 1px dashed rgba(201, 138, 45, 0.4);
  border-radius: 8px;
  padding: 8px 10px;
  margin: 8px 0 10px;
}
.ad-rev-card {
  border: 1px solid rgba(201, 138, 45, 0.35);
  background: rgba(201, 138, 45, 0.04);
}
.ad-rev-diff {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ad-rev-diff__row {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr) 16px minmax(0, 1fr);
  align-items: center;
  gap: 6px;
  font-size: 12px;
}
.ad-rev-diff__label {
  color: #6b6b64;
}
.ad-rev-diff__old {
  color: #a4443a;
  text-decoration: line-through;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ad-rev-diff__arrow {
  text-align: center;
  color: #9a9a92;
}
.ad-rev-diff__new {
  color: #2f7770;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ad-preview-content--rev {
  background: rgba(201, 138, 45, 0.07);
  max-height: 260px;
  overflow: auto;
}
.ad-report-row {
  border-top: 1px dashed #ece8e0;
  padding: 6px 0;
}
@media (max-width: 980px) {
  .ad-review-grid {
    grid-template-columns: 1fr;
  }
}
</style>
