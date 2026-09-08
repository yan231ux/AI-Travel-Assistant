<script setup lang="ts">
import { onMounted, ref } from "vue";
import { message } from "ant-design-vue";
import { useRouter } from "vue-router";

import {
  approvePost,
  closeExperiment,
  createExperiment,
  getAdminPosts,
  getAdminUsers,
  getAuditLogs,
  getExperiments,
  getFeedMonitor,
  getPendingPosts,
  getPendingReports,
  getReportHistory,
  handleReport,
  hidePost,
  rejectPost,
} from "../services/api";
import type { AdminUserItem, AuditLogItem, ExperimentItem, FeedMetricsItem } from "../services/api";
import { isAdmin } from "../stores/session";
import type { PostItem, ReportItem } from "../types";

/**
 * 内容审核与管理页（阶段二 /moderation + 阶段四任务 4/6/7，仅 ADMIN）。
 * tabs：
 * - 待审帖子：通过/拒绝+原因；
 * - 举报处理：成立（自动隐藏目标/删评论）或驳回；
 * - 帖子管理：已发布（下架）/ 已隐藏（恢复上架）双队列；
 * - 举报历史：已处理记录（含处理人）；
 * - 用户列表：注册用户 + 角色 + 发帖数（治理入口）；
 * - 推荐实验：A/B 实验创建/关闭（任务 6，同作用域同时只允许一个 ACTIVE）；
 * - 推荐流监控：两条推荐流曝光/命中/质量构成/反馈漏斗（任务 7）。
 * 所有动作服务端校验 ADMIN，非管理员接口直接 403。
 */
const router = useRouter();

type TabKey = "pending" | "reports" | "posts" | "history" | "users" | "experiments" | "monitor" | "audit";
const tab = ref<TabKey>("pending");

const posts = ref<PostItem[]>([]);
const postsTotal = ref(0);
const reports = ref<ReportItem[]>([]);
const history = ref<ReportItem[]>([]);
const managed = ref<PostItem[]>([]);
const managedStatus = ref<"PUBLISHED" | "HIDDEN">("PUBLISHED");
const managedTotal = ref(0);
const users = ref<AdminUserItem[]>([]);
const loading = ref(true);

/* 推荐实验（任务 6） */
const experiments = ref<ExperimentItem[]>([]);
const creating = ref(false);
const expForm = ref({
  name: "",
  description: "",
  feedType: "SPOT_FEED" as "SPOT_FEED" | "POST_FEED",
  strategy: "QUALITY_GATE",
  trafficPercent: 100,
  controlPercent: 50,
});
/** 作用域变更时联动推荐默认策略（与后端校验一致，避免误配） */
function onFeedTypeChange() {
  expForm.value.strategy =
    expForm.value.feedType === "SPOT_FEED" ? "QUALITY_GATE" : "LOW_QUALITY_FILTER";
}

/* 推荐流监控（任务 7） */
const monitorDays = ref(7);
const monitor = ref<{ days: number; feeds: Record<string, FeedMetricsItem> } | null>(null);

/* 全链路审计日志（任务 10） */
const audits = ref<AuditLogItem[]>([]);
const auditTotal = ref(0);
const auditCategory = ref("");
const auditDays = ref(7);
const AUDIT_CATEGORIES: Record<string, string> = {
  USER: "用户/鉴权",
  TRIP: "行程",
  CONTENT: "内容",
  ADMIN: "审核/治理",
  SOCIAL: "关注",
  PROFILE: "画像",
  OPS: "运营",
};
const AUDIT_ACTIONS: Record<string, string> = {
  user_registered: "注册账号",
  login_success: "登录成功",
  login_failed: "登录失败",
  trip_saved: "保存行程",
  trip_updated: "更新行程",
  trip_deleted: "删除行程",
  post_created: "发布帖子",
  post_submitted: "提交审核",
  post_approved: "通过审核",
  post_rejected: "拒绝帖子",
  post_hidden: "隐藏下架",
  report_created: "收到举报",
  report_resolved: "举报成立处理",
  report_dismissed: "驳回举报",
  user_followed: "关注用户",
  user_unfollowed: "取关用户",
  experiment_created: "创建A/B实验",
  experiment_closed: "关闭A/B实验",
};

function auditActionText(a: string): string {
  return AUDIT_ACTIONS[a] || a;
}

function auditCategoryText(c: string): string {
  return AUDIT_CATEGORIES[c] || c;
}

function auditDetailText(d?: string | null): string {
  if (!d) return "";
  try {
    const o = JSON.parse(d) as Record<string, unknown>;
    return Object.entries(o)
      .map(([k, v]) => `${k}=${String(v)}`)
      .join(" · ");
  } catch {
    return d;
  }
}

async function loadAudits() {
  try {
    const resp = await getAuditLogs({
      category: auditCategory.value || undefined,
      days: auditDays.value,
      page: 1,
      pageSize: 50,
    });
    audits.value = resp.items;
    auditTotal.value = resp.total;
  } catch {
    audits.value = [];
    auditTotal.value = 0;
  }
}

async function loadExperiments() {
  try {
    experiments.value = await getExperiments();
  } catch {
    experiments.value = [];
  }
}

async function loadMonitor() {
  try {
    monitor.value = await getFeedMonitor(monitorDays.value);
  } catch {
    monitor.value = null;
  }
}

async function submitExperiment() {
  const f = expForm.value;
  if (!/^[a-z0-9_]{3,60}$/.test(f.name.trim())) {
    return message.warning("实验名需为 3~60 位小写字母/数字/下划线");
  }
  try {
    creating.value = true;
    await createExperiment({
      name: f.name.trim(),
      description: f.description.trim() || undefined,
      feedType: f.feedType,
      strategy: f.strategy,
      trafficPercent: f.trafficPercent,
      controlPercent: f.controlPercent,
    });
    message.success("实验已创建，流量内用户将按确定性哈希粘性分桶");
    expForm.value.name = "";
    expForm.value.description = "";
    await loadExperiments();
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
    message.error(msg || "创建失败（同作用域已有进行中实验？）");
  } finally {
    creating.value = false;
  }
}

async function closeExp(name: string) {
  if (!window.confirm(`确认关闭实验 ${name}？关闭后推荐流回到基线（对比数据保留）。`)) return;
  try {
    await closeExperiment(name);
    message.success("实验已关闭");
    await loadExperiments();
  } catch {
    message.error("操作失败");
  }
}

function strategyLabel(e: ExperimentItem): string {
  if (e.strategy === "QUALITY_GATE") return "攻略质量门（只保留有真实攻略的候选）";
  if (e.strategy === "LOW_QUALITY_FILTER") return "低质过滤（剔除 low_quality 帖子）";
  return e.strategy;
}

function feedLabel(feed: string): string {
  return feed === "POST_FEED" ? "帖子推荐流" : "推荐景点流";
}

function rateOf(part: number, total: number): string {
  return total <= 0 ? "—" : `${Math.round((part / total) * 1000) / 10}%`;
}

const rejecting = ref(false);
const pendingPostId = ref<number | null>(null);
const rejectReason = ref("");

async function loadPosts() {
  try {
    const resp = await getPendingPosts(1, 50);
    posts.value = resp.items;
    postsTotal.value = resp.total;
  } catch {
    posts.value = [];
  }
}

async function loadReports() {
  try {
    reports.value = await getPendingReports(1, 50);
  } catch {
    reports.value = [];
  }
}

async function loadManaged() {
  try {
    const resp = await getAdminPosts(managedStatus.value, 1, 50);
    managed.value = resp.items;
    managedTotal.value = resp.total;
  } catch {
    managed.value = [];
  }
}

async function loadHistory() {
  try {
    history.value = await getReportHistory(1, 50);
  } catch {
    history.value = [];
  }
}

async function loadUsers() {
  try {
    users.value = await getAdminUsers(1, 100);
  } catch {
    users.value = [];
  }
}

async function load() {
  loading.value = true;
  await Promise.all([
    loadPosts(),
    loadReports(),
    loadManaged(),
    loadHistory(),
    loadUsers(),
    loadExperiments(),
    loadMonitor(),
    loadAudits(),
  ]);
  loading.value = false;
}

function switchTab(t: TabKey) {
  tab.value = t;
}

async function approve(p: PostItem) {
  try {
    await approvePost(p.id);
    message.success(`「${p.title}」已通过并发布`);
    await loadPosts();
    await loadManaged();
  } catch {
    message.error("操作失败");
  }
}

function openReject(p: PostItem) {
  pendingPostId.value = p.id;
  rejectReason.value = "";
  rejecting.value = true;
}

async function confirmReject() {
  if (pendingPostId.value == null) return;
  if (!rejectReason.value.trim()) return message.warning("请填写拒绝原因（作者会看到）");
  try {
    await rejectPost(pendingPostId.value, rejectReason.value.trim());
    message.success("已拒绝");
    rejecting.value = false;
    await loadPosts();
  } catch {
    message.error("操作失败");
  }
}

async function resolveReport(r: ReportItem) {
  if (!window.confirm(`确认举报成立并处理该${r.target_type === "POST" ? "帖子" : "评论"}吗？`)) return;
  try {
    await handleReport(r.id, "RESOLVE");
    message.success("举报成立，已处理");
    await loadReports();
    await loadHistory();
    await loadManaged();
  } catch {
    message.error("操作失败");
  }
}

async function dismissReport(r: ReportItem) {
  try {
    await handleReport(r.id, "DISMISS");
    message.success("已驳回该举报");
    await loadReports();
    await loadHistory();
  } catch {
    message.error("操作失败");
  }
}

/** 帖子管理：隐藏已发布（违规下架） */
async function hideManaged(p: PostItem) {
  if (!window.confirm(`确认下架「${p.title}」？下架后不在公开流展示，可随时恢复。`)) return;
  try {
    await hidePost(p.id);
    message.success("已下架");
    await loadManaged();
    await loadPosts();
  } catch {
    message.error("操作失败");
  }
}

/** 帖子管理：恢复已隐藏（重新上架） */
async function restoreManaged(p: PostItem) {
  if (!window.confirm(`确认恢复「${p.title}」为公开？`)) return;
  try {
    await approvePost(p.id);
    message.success("已恢复公开");
    await loadManaged();
  } catch {
    message.error("操作失败");
  }
}

function goPost(p: PostItem) {
  void router.push({ name: "post-detail", params: { id: String(p.id) } });
}

function goUser(u: AdminUserItem) {
  void router.push({ name: "user-home", params: { id: u.id } });
}

onMounted(async () => {
  if (!isAdmin.value) {
    message.warning("需要管理员权限");
    void router.replace({ name: "community" });
    return;
  }
  await load();
});
</script>

<template>
  <section class="md-page">
    <h2 class="md-title">🛡️ 内容审核与管理</h2>

    <div class="md-tabs">
      <button
        type="button"
        :class="['md-tab', { 'md-tab--on': tab === 'pending' }]"
        @click="switchTab('pending')"
      >
        待审帖子（{{ postsTotal }}）
      </button>
      <button
        type="button"
        :class="['md-tab', { 'md-tab--on': tab === 'reports' }]"
        @click="switchTab('reports')"
      >
        举报处理（{{ reports.length }}）
      </button>
      <button
        type="button"
        :class="['md-tab', { 'md-tab--on': tab === 'posts' }]"
        @click="switchTab('posts')"
      >
        帖子管理（{{ managedTotal }}）
      </button>
      <button
        type="button"
        :class="['md-tab', { 'md-tab--on': tab === 'history' }]"
        @click="switchTab('history')"
      >
        举报历史（{{ history.length }}）
      </button>
      <button
        type="button"
        :class="['md-tab', { 'md-tab--on': tab === 'users' }]"
        @click="switchTab('users')"
      >
        用户（{{ users.length }}+）
      </button>
      <button
        type="button"
        :class="['md-tab', { 'md-tab--on': tab === 'experiments' }]"
        @click="switchTab('experiments')"
      >
        🧪 推荐实验
      </button>
      <button
        type="button"
        :class="['md-tab', { 'md-tab--on': tab === 'monitor' }]"
        @click="switchTab('monitor')"
      >
        📈 推荐流监控
      </button>
      <button
        type="button"
        :class="['md-tab', { 'md-tab--on': tab === 'audit' }]"
        @click="switchTab('audit')"
      >
        🧾 审计日志（{{ auditTotal }}）
      </button>
    </div>

    <!-- ① 待审帖子 -->
    <div v-if="tab === 'pending'" class="md-list">
      <div v-if="loading" class="md-empty">加载中...</div>
      <div v-else-if="posts.length === 0" class="md-empty">🎉 没有待审核的帖子</div>
      <article v-for="p in posts" v-else :key="p.id" class="md-item">
        <div class="md-item__main" role="button" tabindex="0" @click="goPost(p)">
          <p class="md-item__title">
            {{ p.title }}
            <span v-if="p.low_quality" class="md-badge md-badge--no">低质</span>
            <span v-if="p.quality_score != null" class="md-badge md-badge--score">{{ p.quality_score }} 分</span>
          </p>
          <p class="md-item__meta">
            作者 @{{ p.author?.nickname || p.author?.id }} · {{ p.post_type }} · {{ p.city || "未填城市" }} · 创建于 {{ (p.created_at || "").slice(0, 16) }}
          </p>
        </div>
        <div class="md-item__ops">
          <button type="button" class="btn btn--ok" @click="approve(p)">✓ 通过并发布</button>
          <button type="button" class="btn btn--no" @click="openReject(p)">✕ 拒绝</button>
        </div>
      </article>
    </div>

    <!-- ② 待处理举报 -->
    <div v-else-if="tab === 'reports'" class="md-list">
      <div v-if="loading" class="md-empty">加载中...</div>
      <div v-else-if="reports.length === 0" class="md-empty">没有待处理的举报</div>
      <article v-for="r in reports" v-else :key="r.id" class="md-item">
        <div class="md-item__main">
          <p class="md-item__title">{{ r.target_type === "POST" ? "帖子" : "评论" }}：{{ r.target_snapshot }}</p>
          <p class="md-item__meta">
            举报原因：{{ r.reason }} · 举报人 @{{ r.reporter_name }} · {{ (r.created_at || "").slice(0, 16) }}
            <span v-if="r.detail"> · 补充：{{ r.detail }}</span>
          </p>
        </div>
        <div class="md-item__ops">
          <button type="button" class="btn btn--ok" @click="resolveReport(r)">✓ 成立并处理</button>
          <button type="button" class="btn btn--no" @click="dismissReport(r)">✕ 驳回</button>
        </div>
      </article>
    </div>

    <!-- ③ 帖子管理（已发布 / 已隐藏） -->
    <div v-else-if="tab === 'posts'" class="md-list">
      <div class="md-seg">
        <button
          type="button"
          :class="['md-seg__btn', { 'md-seg__btn--on': managedStatus === 'PUBLISHED' }]"
          @click="managedStatus = 'PUBLISHED'; void loadManaged()"
        >
          已发布
        </button>
        <button
          type="button"
          :class="['md-seg__btn', { 'md-seg__btn--on': managedStatus === 'HIDDEN' }]"
          @click="managedStatus = 'HIDDEN'; void loadManaged()"
        >
          已隐藏
        </button>
      </div>
      <div v-if="loading" class="md-empty">加载中...</div>
      <div v-else-if="managed.length === 0" class="md-empty">
        {{ managedStatus === "PUBLISHED" ? "当前没有已发布帖子" : "没有被隐藏的帖子" }}
      </div>
      <article v-for="p in managed" v-else :key="p.id" class="md-item">
        <div class="md-item__main" role="button" tabindex="0" @click="goPost(p)">
          <p class="md-item__title">
            {{ p.title }}
            <span v-if="p.low_quality" class="md-badge md-badge--no">低质</span>
            <span v-if="p.quality_score != null" class="md-badge md-badge--score">{{ p.quality_score }} 分</span>
          </p>
          <p class="md-item__meta">
            作者 @{{ p.author?.nickname || p.author?.id }} · ❤️{{ p.like_count || 0 }} ⭐{{ p.favorite_count || 0 }} 💬{{ p.comment_count || 0 }} · {{ (p.published_at || p.created_at || "").slice(0, 16) }}
          </p>
        </div>
        <div class="md-item__ops">
          <button
            v-if="managedStatus === 'PUBLISHED'"
            type="button"
            class="btn btn--no"
            @click="hideManaged(p)"
          >
            下架
          </button>
          <button v-else type="button" class="btn btn--ok" @click="restoreManaged(p)">
            恢复公开
          </button>
        </div>
      </article>
    </div>

    <!-- ④ 举报历史 -->
    <div v-else-if="tab === 'history'" class="md-list">
      <div v-if="loading" class="md-empty">加载中...</div>
      <div v-else-if="history.length === 0" class="md-empty">还没有处理过举报</div>
      <article v-for="r in history" v-else :key="r.id" class="md-item">
        <div class="md-item__main">
          <p class="md-item__title">
            {{ r.target_type === "POST" ? "帖子" : "评论" }}：{{ r.target_snapshot }}
            <span :class="['md-badge', r.status === 'RESOLVED' ? 'md-badge--ok' : 'md-badge--no']">
              {{ r.status === "RESOLVED" ? "已成立" : "已驳回" }}
            </span>
          </p>
          <p class="md-item__meta">
            原因：{{ r.reason }} · 举报人 @{{ r.reporter_name }}
            <template v-if="r.handled_by"> · 处理人 @{{ r.handled_by }} · {{ (r.handled_at || "").slice(0, 16) }}</template>
          </p>
        </div>
      </article>
    </div>

    <!-- ⑤ 用户列表 -->
    <div v-else-if="tab === 'users'" class="md-list">
      <div v-if="loading" class="md-empty">加载中...</div>
      <div v-else-if="users.length === 0" class="md-empty">还没有注册用户</div>
      <article v-for="u in users" v-else :key="u.id" class="md-item">
        <div class="md-item__main" role="button" tabindex="0" @click="goUser(u)">
          <p class="md-item__title">{{ u.nickname || u.username }} <span v-if="u.role === 'ADMIN'" class="md-badge md-badge--admin">管理员</span></p>
          <p class="md-item__meta">
            @{{ u.username }} · {{ u.post_count }} 篇攻略 · 注册于 {{ (u.created_at || "").slice(0, 10) }}
          </p>
        </div>
        <div class="md-item__ops">
          <button type="button" class="btn" @click="goUser(u)">看主页 ›</button>
        </div>
      </article>
    </div>

    <!-- ⑥ 推荐 A/B 实验（任务 6） -->
    <div v-else-if="tab === 'experiments'" class="md-list">
      <div class="md-exp-form">
        <p class="md-exp-form__title">创建实验（同作用域同时只允许一个 ACTIVE）</p>
        <div class="md-exp-form__row">
          <input
            v-model="expForm.name"
            class="md-input"
            placeholder="实验名：小写字母/数字/下划线"
            maxlength="60"
          />
          <select
            v-model="expForm.feedType"
            class="md-input md-input--select"
            @change="onFeedTypeChange"
          >
            <option value="SPOT_FEED">推荐景点流</option>
            <option value="POST_FEED">帖子推荐流</option>
          </select>
          <select v-model="expForm.strategy" class="md-input md-input--select">
            <option value="QUALITY_GATE">攻略质量门</option>
            <option value="LOW_QUALITY_FILTER">低质过滤</option>
          </select>
        </div>
        <div class="md-exp-form__row">
          <input v-model="expForm.description" class="md-input" placeholder="实验说明（可选）" maxlength="300" />
          <input
            v-model.number="expForm.trafficPercent"
            class="md-input md-input--num"
            type="number"
            min="1"
            max="100"
            title="参与流量 %"
          />
          <input
            v-model.number="expForm.controlPercent"
            class="md-input md-input--num"
            type="number"
            min="0"
            max="100"
            title="对照组占比 %"
          />
          <button type="button" class="btn btn--ok" :disabled="creating" @click="submitExperiment">
            创建
          </button>
        </div>
        <p class="md-exp-form__hint">
          流量 100 / 对照 50 = 一半用户看新策略、一半看现状；用户分桶由用户ID确定性哈希决定，实验期内不换桶。
        </p>
      </div>

      <div v-if="experiments.length === 0" class="md-empty">还没有实验 —— 创建后，命中处理组的用户会看到对应新策略</div>
      <article v-for="e in experiments" v-else :key="e.id" class="md-item">
        <div class="md-item__main">
          <p class="md-item__title">
            {{ e.name }}
            <span :class="['md-badge', e.status === 'ACTIVE' ? 'md-badge--ok' : 'md-badge--no']">
              {{ e.status === "ACTIVE" ? "进行中" : "已关闭" }}
            </span>
            <span class="md-badge md-badge--admin">{{ feedLabel(e.feed_type) }}</span>
          </p>
          <p class="md-item__meta">
            {{ strategyLabel(e) }} · 流量 {{ e.traffic_percent }}% / 对照 {{ e.control_percent }}% ·
            对照 {{ e.control_users }} 人 / 处理组 {{ e.treatment_users }} 人
            <template v-if="e.description"> · {{ e.description }}</template>
          </p>
        </div>
        <div class="md-item__ops">
          <button v-if="e.status === 'ACTIVE'" type="button" class="btn btn--no" @click="closeExp(e.name)">
            关闭实验
          </button>
        </div>
      </article>
    </div>

    <!-- ⑦ 推荐流监控（任务 7） -->
    <div v-else-if="tab === 'monitor'" class="md-list">
      <div class="md-mon-head">
        <span class="md-mon-head__label">时间窗：</span>
        <select v-model.number="monitorDays" class="md-input md-input--select" @change="void loadMonitor()">
          <option :value="1">近 1 天</option>
          <option :value="7">近 7 天</option>
          <option :value="30">近 30 天</option>
        </select>
      </div>
      <template v-if="monitor">
        <article v-for="(m, feed) in monitor.feeds" :key="feed" class="md-mon">
          <p class="md-item__title">
            {{ feed === "POST_FEED" ? "帖子推荐流（为你推荐）" : "推荐景点流（为你推荐）" }}
          </p>
          <div class="md-mon__kpis">
            <div class="md-kpi"><b>{{ m.exposures }}</b><span>曝光</span></div>
            <div class="md-kpi"><b>{{ m.users }}</b><span>用户</span></div>
            <div class="md-kpi"><b>{{ m.hit_rate }}%</b><span>偏好命中</span></div>
            <div class="md-kpi"><b>{{ m.avg_score }}</b><span>均分</span></div>
            <div class="md-kpi"><b>{{ rateOf(m.feedbacks?.save || 0, m.exposures) }}</b><span>收藏率</span></div>
            <div class="md-kpi"><b>{{ rateOf(m.feedbacks?.dislike || 0, m.exposures) }}</b><span>负反馈率</span></div>
          </div>
          <div class="md-mon__rows">
            <p class="md-item__meta">
              A/B 分布：
              <span v-for="(n, v) in m.by_variant" :key="v" class="md-tag">
                {{ v === "NONE" ? "无实验" : v === "CONTROL" ? "对照" : "处理组" }} {{ n }}
              </span>
            </p>
            <p v-if="feed === 'SPOT_FEED'" class="md-item__meta">
              内容质量构成：
              <span v-for="(n, q) in m.by_quality" :key="q" class="md-tag">
                {{ q === "POI_ONLY" ? "纯高德POI" : q === "GUIDE_MATCHED" ? "攻略命中" : q }} {{ n }}
              </span>
            </p>
            <p class="md-item__meta">
              反馈漏斗：收藏 {{ m.feedbacks?.save || 0 }} 次 · 不感兴趣 {{ m.feedbacks?.dislike || 0 }} 次
              （只统计对该用户真实曝光过的卡片）
            </p>
          </div>
        </article>
      </template>
      <div v-else class="md-empty">暂无曝光数据 —— 有用户刷「为你推荐」并互动后这里会出现读数</div>
    </div>

    <!-- ⑧ 全链路审计日志（任务 10） -->
    <div v-else-if="tab === 'audit'" class="md-list">
      <div class="md-mon-head">
        <select v-model="auditCategory" class="md-input md-input--select" @change="void loadAudits()">
          <option value="">全部分类</option>
          <option v-for="(label, c) in AUDIT_CATEGORIES" :key="c" :value="c">{{ label }}</option>
        </select>
        <select v-model.number="auditDays" class="md-input md-input--select" @change="void loadAudits()">
          <option :value="1">近 1 天</option>
          <option :value="7">近 7 天</option>
          <option :value="30">近 30 天</option>
          <option :value="90">近 90 天</option>
        </select>
        <span class="md-item__meta">共 {{ auditTotal }} 条（谁在何时对什么做了什么，可追溯）</span>
      </div>
      <div v-if="audits.length === 0" class="md-empty">还没有审计记录</div>
      <article v-for="a in audits" v-else :key="a.id" class="md-item">
        <div class="md-item__main">
          <p class="md-item__title">
            {{ auditActionText(a.action) }}
            <span class="md-badge md-badge--admin">{{ auditCategoryText(a.category) }}</span>
          </p>
          <p class="md-item__meta">
            <template v-if="a.actor_name">@{{ a.actor_name }}（{{ a.actor_id }}）</template>
            <template v-else>系统/匿名</template>
            <template v-if="a.target_type"> · 对象 {{ a.target_type }}#{{ a.target_id }}</template>
            <template v-if="auditDetailText(a.detail)"> · {{ auditDetailText(a.detail) }}</template>
            <br />{{ (a.created_at || "").replace("T", " ") }}
          </p>
        </div>
      </article>
    </div>

    <!-- 拒绝原因面板 -->
    <div v-if="rejecting" class="md-modal">
      <div class="md-modal__card">
        <p class="md-modal__title">填写拒绝原因（作者可见）</p>
        <textarea
          v-model="rejectReason"
          class="md-modal__input"
          rows="3"
          maxlength="300"
          placeholder="例如：正文含未核实的门票价格，请补充信息来源后重新提交"
        ></textarea>
        <div class="md-modal__ops">
          <button type="button" class="btn" @click="rejecting = false">取消</button>
          <button type="button" class="btn btn--no" :disabled="!rejectReason.trim()" @click="confirmReject">确认拒绝</button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.md-page {
  max-width: 860px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.md-title {
  font-size: 20px;
  margin: 0;
  color: var(--text-primary);
}
.md-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.md-tab {
  border: none;
  border-radius: 999px;
  padding: 6px 14px;
  font-size: 13px;
  background: rgba(0, 0, 0, 0.04);
  color: var(--text-secondary);
  cursor: pointer;
}
.md-tab--on {
  background: var(--text-primary);
  color: #fff;
}
.md-seg {
  display: inline-flex;
  gap: 4px;
  background: rgba(0, 0, 0, 0.05);
  border-radius: 10px;
  padding: 3px;
  align-self: flex-start;
}
.md-seg__btn {
  border: none;
  border-radius: 8px;
  padding: 5px 16px;
  font-size: 13px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
}
.md-seg__btn--on {
  background: #fff;
  color: var(--text-primary);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08);
}
.md-badge {
  display: inline-block;
  margin-left: 6px;
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
}
.md-badge--ok {
  background: rgba(60, 140, 112, 0.1);
  color: var(--success);
}
.md-badge--no {
  background: rgba(198, 93, 81, 0.08);
  color: var(--danger);
}
.md-badge--admin {
  background: rgba(47, 119, 112, 0.08);
  color: var(--brand-deep);
}
.md-empty {
  text-align: center;
  padding: 40px 0;
  color: var(--text-muted);
  background: #fff;
  border-radius: 14px;
}
.md-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.md-item {
  background: #fff;
  border-radius: 14px;
  padding: 14px 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}
.md-item__main {
  flex: 1;
  min-width: 0;
  cursor: pointer;
}
.md-item__title {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.md-item__meta {
  margin: 0;
  font-size: 12px;
  color: var(--text-muted);
}
.md-item__ops {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.btn {
  border: none;
  border-radius: 10px;
  padding: 6px 14px;
  font-size: 13px;
  font-weight: 500;
  background: rgba(0, 0, 0, 0.05);
  color: var(--text-secondary);
  cursor: pointer;
}
.btn--ok {
  background: rgba(60, 140, 112, 0.1);
  color: var(--success);
}
.btn--no {
  background: rgba(198, 93, 81, 0.08);
  color: var(--danger);
}
.btn:disabled {
  opacity: 0.6;
}
.md-modal {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  display: grid;
  place-items: center;
  z-index: 200;
}
.md-modal__card {
  width: min(420px, 90vw);
  background: #fff;
  border-radius: 16px;
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.md-modal__title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.md-modal__input {
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 10px;
  padding: 10px;
  font-size: 14px;
  font-family: inherit;
  resize: vertical;
}
.md-modal__ops {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
/* ⑥ A/B 实验 */
.md-exp-form {
  background: #fff;
  border-radius: 14px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}
.md-exp-form__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.md-exp-form__row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.md-exp-form__hint {
  margin: 0;
  font-size: 12px;
  color: var(--text-muted);
}
.md-input {
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 10px;
  padding: 6px 12px;
  font-size: 13px;
  font-family: inherit;
  min-width: 0;
  background: #fff;
  color: var(--text-primary);
}
.md-input--select {
  cursor: pointer;
}
.md-input--num {
  width: 88px;
}
/* ⑦ 推荐流监控 */
.md-mon-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.md-mon-head__label {
  font-size: 13px;
  color: var(--text-secondary);
}
.md-mon {
  background: #fff;
  border-radius: 14px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}
.md-mon__kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(88px, 1fr));
  gap: 8px;
}
.md-kpi {
  background: rgba(0, 0, 0, 0.04);
  border-radius: 12px;
  padding: 10px 8px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}
.md-kpi b {
  font-size: 18px;
  color: var(--text-primary);
}
.md-kpi span {
  font-size: 11px;
  color: var(--text-muted);
}
.md-mon__rows {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.md-tag {
  display: inline-block;
  background: rgba(47, 119, 112, 0.08);
  color: var(--brand-deep);
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
  margin: 0 4px 4px 0;
}
</style>
