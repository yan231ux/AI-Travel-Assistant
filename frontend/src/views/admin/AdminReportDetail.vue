<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { message } from "ant-design-vue";
import { useRouter } from "vue-router";

import type { ReportEvidence } from "../../services/api";
import { getReportEvidence, handleReport } from "../../services/api";
import { resolveImageUrl } from "../../services/api";

const props = defineProps<{ id: string }>();
const router = useRouter();
const ev = ref<ReportEvidence | null>(null);
const busy = ref(false);
const note = ref("");
const showAction = ref(false);

const isPending = computed(() => ev.value?.report.status === "PENDING");

function back() {
  void router.push({ name: "admin-reports" });
}

async function act(action: "RESOLVE" | "DISMISS") {
  if (!ev.value || busy.value) return;
  const verb = action === "RESOLVE" ? "举报成立并处理该内容" : "驳回该举报";
  if (!window.confirm(`确认${verb}吗？`)) return;
  busy.value = true;
  try {
    await handleReport(ev.value.report.id, action, note.value.trim() || undefined);
    message.success(action === "RESOLVE" ? "已处理（成立）" : "已驳回");
    await load();
  } catch (e: unknown) {
    message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败");
  } finally {
    busy.value = false;
    showAction.value = false;
  }
}

async function load() {
  try {
    ev.value = await getReportEvidence(Number(props.id));
  } catch {
    ev.value = null;
    message.error("举报记录不存在");
  }
}

onMounted(load);
</script>

<template>
  <div v-if="!ev" class="ad-empty">加载中…</div>
  <div v-else class="ad-report-grid">
    <!-- 左：被举报内容与上下文 -->
    <section class="ad-card">
      <div class="ad-form-row" style="justify-content: space-between">
        <p class="ad-title">
          {{ ev.report.target_type === "POST" ? "被举报帖子" : "被举报评论所在帖子" }}
        </p>
        <button type="button" class="ad-btn" @click="back">← 返回举报中心</button>
      </div>

      <template v-if="!ev.target_post">
        <div class="ad-empty">内容不存在或已被删除</div>
      </template>
      <template v-else>
        <img
          v-if="ev.target_post.cover_image"
          :src="resolveImageUrl(ev.target_post.cover_image)"
          alt="封面"
          class="ad-cover"
        />
        <p class="ad-item__title" style="font-size: 16px">{{ ev.target_post.title }}</p>
        <p class="ad-item__meta">
          作者 @{{ ev.target_post.author?.nickname || ev.target_post.author?.id }} ·
          {{ ev.target_post.city || "未填城市" }} · {{ ev.target_post.post_type }}
          <span class="ad-badge" :class="ev.target_post.status === 'PUBLISHED' ? 'ad-badge--ok' : 'ad-badge--warn'">
            {{ ev.target_post.status }}
          </span>
          <span v-if="ev.target_post.low_quality" class="ad-badge ad-badge--no">低质</span>
          <span v-if="ev.target_post.quality_score != null" class="ad-badge ad-badge--info">
            {{ ev.target_post.quality_score }} 分
          </span>
        </p>
        <p v-if="ev.target_post.summary" class="ad-item__meta">{{ ev.target_post.summary }}</p>
        <pre class="ad-preview-content">{{ ev.target_post.content }}</pre>
        <p class="ad-item__meta">
          ❤️{{ ev.target_post.like_count || 0 }} ⭐{{ ev.target_post.favorite_count || 0 }} 💬{{
            ev.target_post.comment_count || 0
          }} 👁{{ ev.target_post.view_count || 0 }} · 创建 {{ ev.target_post.created_at }}
        </p>

        <!-- 评论举报时展示被举报评论 -->
        <template v-if="ev.target_comment">
          <p class="ad-item__title" style="margin-top: 14px">被举报评论</p>
          <div class="ad-comment">
            <p class="ad-item__meta">@{{ ev.target_comment.author?.nickname || ev.target_comment.author?.id }} · {{ ev.target_comment.created_at }}</p>
            <p class="ad-comment__body">{{ ev.target_comment.content }}</p>
          </div>
        </template>
      </template>
    </section>

    <!-- 右：举报证据 + 处理 -->
    <aside class="ad-grid" style="align-content: start; gap: 12px">
      <div class="ad-card">
        <p class="ad-title" style="font-size: 15px">举报单 #{{ ev.report.id }}</p>
        <p class="ad-item__meta">
          对象：{{ ev.report.target_type === "POST" ? "帖子" : "评论" }}#{{ ev.report.target_id }}<br />
          原因：<b>{{ ev.report.reason }}</b><br />
          举报人：@{{ ev.report.reporter_name }}<br />
          提交于：{{ ev.report.created_at }}
          <template v-if="ev.report.detail"> <br />补充说明：{{ ev.report.detail }}</template>
          <template v-if="ev.report.handled_by">
            <br />处理人：@{{ ev.report.handled_by }}（{{ ev.report.handled_at }}）
            <br />备注：{{ ev.report.handle_note || "—" }}
          </template>
        </p>
        <p class="ad-item__meta">
          状态：
          <span class="ad-badge" :class="isPending ? 'ad-badge--warn' : ev.report.status === 'RESOLVED' ? 'ad-badge--ok' : 'ad-badge--no'">
            {{ isPending ? "待处理" : ev.report.status === "RESOLVED" ? "已成立" : "已驳回" }}
          </span>
        </p>
      </div>

      <div class="ad-card">
        <p class="ad-title" style="font-size: 15px">处理决策</p>
        <p class="ad-item__meta">处理备注会随审计与历史留存（成立 ≠ 封禁账号，仅处理违规内容本身）。</p>
        <template v-if="isPending">
          <textarea
            v-model="note"
            class="ad-textarea"
            rows="2"
            maxlength="300"
            placeholder="处理备注（可选，如：帖子含虚假票价，已下架）"
          ></textarea>
          <div class="ad-ops" style="margin-top: 10px">
            <button type="button" class="ad-btn ad-btn--ok" :disabled="busy" @click="act('RESOLVE')">✓ 成立并处理</button>
            <button type="button" class="ad-btn ad-btn--no" :disabled="busy" @click="act('DISMISS')">✕ 驳回</button>
          </div>
        </template>
        <p v-else class="ad-item__meta">该举报已处理，动作记录见下。</p>
      </div>

      <div class="ad-card">
        <p class="ad-title" style="font-size: 15px">同对象其他举报（{{ ev.target_post?.reports?.length ?? 0 }}）</p>
        <div v-if="!ev.target_post?.reports?.length" class="ad-item__meta">无</div>
        <div v-for="r in ev.target_post?.reports || []" :key="r.id" class="ad-item__meta" style="margin-top: 2px">
          · #{{ r.id }} {{ r.reason }}（{{ r.reporter_name }}）{{ r.status === "PENDING" ? "待处理" : r.status === "RESOLVED" ? "已成立" : "已驳回" }}
        </div>
      </div>

      <div class="ad-card">
        <p class="ad-title" style="font-size: 15px">处理历史（审计）</p>
        <div v-if="!ev.audit.length" class="ad-item__meta">暂无</div>
        <div v-for="(a, i) in ev.audit" :key="i" class="ad-item__meta" style="margin-top: 2px">
          · {{ a.created_at }} {{ a.actor }}：{{ a.action }}
        </div>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.ad-report-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(280px, 1fr);
  gap: 14px;
  align-items: start;
}
.ad-cover {
  max-width: 100%;
  border-radius: 10px;
  margin-bottom: 8px;
  max-height: 240px;
  object-fit: cover;
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
  margin: 8px 0;
}
.ad-comment {
  background: rgba(0, 0, 0, 0.03);
  border-radius: 10px;
  padding: 10px 12px;
}
.ad-comment__body {
  margin: 4px 0 0;
  font-size: 13px;
  color: #2e2e2b;
}
@media (max-width: 980px) {
  .ad-report-grid {
    grid-template-columns: 1fr;
  }
}
</style>
