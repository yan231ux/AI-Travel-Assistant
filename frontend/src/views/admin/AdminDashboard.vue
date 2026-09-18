<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";

import type { AdminDashboardSummary } from "../../services/api";
import { getAdminSummary } from "../../services/api";

const router = useRouter();
const summary = ref<AdminDashboardSummary | null>(null);

function auditActionText(a: string): string {
  const map: Record<string, string> = {
    post_approved: "通过帖子",
    post_rejected: "拒绝帖子",
    post_hidden: "隐藏下架",
    report_resolved: "举报成立处理",
    report_dismissed: "驳回举报",
    guide_created: "新建攻略",
    guide_updated: "编辑攻略",
    guide_submitted: "攻略提交审核",
    guide_published: "攻略发布",
    guide_rejected: "攻略拒绝",
    guide_hidden: "攻略下线",
    guide_restored: "攻略恢复",
    guide_imported: "攻略导入",
    experiment_created: "创建A/B实验",
    experiment_closed: "关闭A/B实验",
    user_registered: "注册账号",
    login_success: "登录成功",
    trip_saved: "保存行程",
    post_created: "发布帖子",
    report_created: "收到举报",
    user_followed: "关注用户",
    user_unfollowed: "取关用户",
  };
  return map[a] || a;
}

function go(name: string) {
  void router.push({ name });
}

const loading = ref(false);
const loadError = ref(false);

async function load() {
  loading.value = true;
  loadError.value = false;
  try {
    summary.value = await getAdminSummary();
  } catch {
    summary.value = null;
    loadError.value = true;
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  void load();
});

/**
 * 统计项为 null 表示后端查询失败（不可用），必须显示"—"而不是 0——
 * 否则会把"数据库故障"误读成"系统没有数据"（P0-1）。
 */
function num(v: number | null | undefined): string {
  return v === null || v === undefined ? "—" : String(v);
}

/** 该统计项是否处于"不可用"状态（后端 errors 里点名了它） */
function isUnavailable(key: string): boolean {
  return !!summary.value?.errors?.some((e) => e.key === key);
}
</script>

<template>
  <div class="ad-grid" style="gap: 16px">
    <div class="ad-card">
      <p class="ad-title">运营总览</p>
      <p class="ad-sub">现在有什么需要处理、内容质量如何、最近发生了什么 —— 全部读数来自实时统计。</p>
      <div v-if="loading" class="ad-empty">加载中…</div>
      <div v-else-if="loadError" class="ad-empty">
        看板加载失败
        <button type="button" class="ad-retry" @click="load">重试</button>
      </div>
      <template v-else-if="summary">
        <div v-if="summary.degraded" class="ad-degraded">
          <span>
            有 {{ summary.errors?.length ?? 0 }} 项统计暂时不可用（后端查询失败），对应读数显示为"—"，不代表真实为 0。
          </span>
          <button type="button" class="ad-retry" @click="load">重试</button>
        </div>
        <div class="ad-kpis">
          <button type="button" class="ad-kpi ad-kpi--link" :class="{ 'ad-kpi--na': isUnavailable('todo.pending_posts') }" @click="go('admin-review')">
            <b>{{ num(summary.todo.pending_posts) }}</b>
            <span>待审核帖子</span>
          </button>
          <button type="button" class="ad-kpi ad-kpi--link" :class="{ 'ad-kpi--na': isUnavailable('todo.pending_reports') }" @click="go('admin-reports')">
            <b>{{ num(summary.todo.pending_reports) }}</b>
            <span>待处理举报</span>
          </button>
          <button type="button" class="ad-kpi ad-kpi--link" :class="{ 'ad-kpi--na': isUnavailable('todo.pending_guides') }" @click="go('admin-guides')">
            <b>{{ num(summary.todo.pending_guides) }}</b>
            <span>待审攻略</span>
          </button>
          <div class="ad-kpi" :class="{ 'ad-kpi--na': isUnavailable('todo.low_quality_pending') }">
            <b>{{ num(summary.todo.low_quality_pending) }}</b>
            <span>低质待审</span>
          </div>
        </div>

        <p class="ad-group-title">内容概况</p>
        <div class="ad-kpis">
          <div class="ad-kpi" :class="{ 'ad-kpi--na': isUnavailable('content.posts_total') }"><b>{{ num(summary.content.posts_total) }}</b><span>帖子总量</span></div>
          <div class="ad-kpi" :class="{ 'ad-kpi--na': isUnavailable('content.published_posts') }"><b>{{ num(summary.content.published_posts) }}</b><span>已发布</span></div>
          <div class="ad-kpi" :class="{ 'ad-kpi--na': isUnavailable('content.hidden_posts') }"><b>{{ num(summary.content.hidden_posts) }}</b><span>已隐藏</span></div>
          <div class="ad-kpi" :class="{ 'ad-kpi--na': isUnavailable('content.published_today') }"><b>{{ num(summary.content.published_today) }}</b><span>今日发布</span></div>
          <div class="ad-kpi" :class="{ 'ad-kpi--na': isUnavailable('content.reports_7d') }"><b>{{ num(summary.content.reports_7d) }}</b><span>近7天举报</span></div>
          <div class="ad-kpi" :class="{ 'ad-kpi--na': isUnavailable('content.report_resolve_rate') }"><b>{{ num(summary.content.report_resolve_rate) }}{{ summary.content.report_resolve_rate === null || summary.content.report_resolve_rate === undefined ? "" : "%" }}</b><span>举报成立率</span></div>
          <div class="ad-kpi" :class="{ 'ad-kpi--na': isUnavailable('content.guides_total') }"><b>{{ num(summary.content.guides_total) }}</b><span>攻略总数</span></div>
          <div class="ad-kpi" :class="{ 'ad-kpi--na': isUnavailable('content.cities_with_guide') }"><b>{{ num(summary.content.cities_with_guide) }}</b><span>有攻略城市</span></div>
          <div class="ad-kpi" :class="{ 'ad-kpi--na': isUnavailable('content.users_total') }"><b>{{ num(summary.content.users_total) }}</b><span>注册用户</span></div>
        </div>
      </template>
    </div>

    <div class="ad-card">
      <p class="ad-title">最近操作</p>
      <p class="ad-sub">谁在何时对什么内容做了什么（全链路审计，最新 10 条）</p>
      <div v-if="loading" class="ad-empty">加载中…</div>
      <div v-else-if="loadError" class="ad-empty">—</div>
      <div v-else-if="summary && summary.recent_ops_visible === false" class="ad-empty">
        当前角色无审计查看权限（全链路审计仅超级管理员可见）
      </div>
      <div v-else-if="isUnavailable('recent_ops')" class="ad-empty">审计数据暂时不可用</div>
      <div v-else-if="!summary || summary.recent_ops.length === 0" class="ad-empty">还没有审计记录</div>
      <div v-else class="ad-list">
        <div v-for="(op, i) in summary.recent_ops" :key="i" class="ad-item">
          <div class="ad-item__main">
            <p class="ad-item__title">
              {{ auditActionText(op.action) }}
              <span class="ad-badge ad-badge--info">{{ op.category }}</span>
            </p>
            <p class="ad-item__meta">
              <template v-if="op.actor">@{{ op.actor }}</template>
              <template v-else>系统/匿名</template>
              <template v-if="op.target_type"> · {{ op.target_type }}#{{ op.target_id }}</template>
              <template v-if="op.detail">
                ·
                <span class="ad-detail-json">{{ op.detail }}</span>
              </template>
              <br />{{ (op.created_at || "").replace("T", " ") }}
            </p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ad-group-title {
  margin: 16px 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: #4a4a46;
}
.ad-kpi--link {
  border: none;
  cursor: pointer;
  text-align: left;
  font-family: inherit;
}
.ad-kpi--link:hover {
  outline: 1px solid rgba(47, 119, 112, 0.4);
}
.ad-detail-json {
  font-size: 11px;
  color: #8c8a83;
}
.ad-kpi--na b {
  color: #a8a69e;
}
.ad-kpi--na span {
  color: #a8a69e;
}
</style>
