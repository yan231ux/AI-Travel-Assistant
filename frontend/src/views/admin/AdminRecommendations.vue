<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { message } from "ant-design-vue";

import type {
  AdminInterventionAction,
  AdminInterventionItem,
  AdminSpotItem,
  CityQualityResponse,
  ExperimentItem,
  FeedMetricsItem,
} from "../../services/api";
import {
  closeExperiment,
  createExperiment,
  getExperiments,
  getFeedMonitor,
  getRecommendationCityQuality,
  listAdminInterventions,
  listAdminSpots,
  removeAdminIntervention,
  saveAdminIntervention,
} from "../../services/api";

/**
 * 推荐运营（设计方案 §6：推荐流概览 + 城市推荐质量 + A/B 实验 + §6.3 人工干预，独立运营页）。
 *
 * 四个分区的分工：
 * - 推荐流监控 = 两条推荐流的全站读数（曝光/命中/反馈率/质量构成/各城市推荐量）；
 * - 城市推荐质量 = 把同一件事按城市切开看"有没有货、货好不好、用户买不买账"；
 * - A/B 实验 = 对照策略；人工干预 = 运营兜底（与算法分数严格分层）。
 */
const seg = ref<"experiments" | "monitor" | "quality" | "interventions">("experiments");

/* 实验 */
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

/* 监控 */
const monitorDays = ref(7);
const monitor = ref<{
  days: number;
  feeds: Record<string, FeedMetricsItem>;
  degraded: boolean;
  errors: string[];
} | null>(null);

/* 城市推荐质量（设计方案 §6：按城市拆开看"有没有货 / 货好不好 / 用户买不买账"） */
const qualityDays = ref(30);
const quality = ref<CityQualityResponse | null>(null);
const qualityLoading = ref(false);
const qualityError = ref("");

/* ================= §6.3 人工干预 ================= */
const ACTIONS: { value: AdminInterventionAction; label: string; spot: boolean }[] = [
  { value: "PIN", label: "景点置顶", spot: true },
  { value: "DEMOTE", label: "景点降权", spot: true },
  { value: "BLACKLIST", label: "推荐黑名单", spot: true },
  { value: "FEATURED", label: "城市精选", spot: false },
];
const ACTION_LABEL: Record<string, string> = {
  PIN: "置顶",
  DEMOTE: "降权",
  BLACKLIST: "黑名单",
  FEATURED: "城市精选",
};
const CITIES = ["北京", "上海", "成都", "重庆", "杭州", "丽江", "三亚", "大理", "厦门", "西安"];

const interventions = ref<AdminInterventionItem[]>([]);
const ivTotal = ref(0);
const ivLoading = ref(false);
const ivSaving = ref(false);
const ivFilters = reactive({ action: "", scope: "", page: 1, pageSize: 20 });

/** 新增/编辑表单 */
const ivForm = reactive({
  action: "PIN" as AdminInterventionAction,
  city: "",
  spotId: "",
  spotName: "",
  reason: "",
  effectiveFrom: "",
  effectiveUntil: "",
});
/** 景点搜索候选（选 SPOT 动作时） */
const spotHits = ref<AdminSpotItem[]>([]);
const spotSearching = ref(false);
const spotKeyword = ref("");

const isSpotAction = computed(() => ACTIONS.find((a) => a.value === ivForm.action)?.spot ?? true);

function errOf(e: unknown): string {
  return (e as { response?: { data?: { message?: string } } })?.response?.data?.message || "操作失败";
}

async function loadInterventions() {
  ivLoading.value = true;
  try {
    const resp = await listAdminInterventions({
      action: ivFilters.action || undefined,
      scope: ivFilters.scope || undefined,
      page: ivFilters.page,
      pageSize: ivFilters.pageSize,
    });
    interventions.value = resp.items;
    ivTotal.value = resp.total;
  } catch {
    interventions.value = [];
    ivTotal.value = 0;
  } finally {
    ivLoading.value = false;
  }
}

function ivSearch() {
  ivFilters.page = 1;
  void loadInterventions();
}

function ivPage(p: number) {
  ivFilters.page = p;
  void loadInterventions();
}

function resetIvForm() {
  ivForm.action = "PIN";
  ivForm.city = ivForm.city || CITIES[0];
  ivForm.spotId = "";
  ivForm.spotName = "";
  ivForm.reason = "";
  ivForm.effectiveFrom = "";
  ivForm.effectiveUntil = "";
  spotKeyword.value = "";
  spotHits.value = [];
}

async function searchSpots() {
  if (!ivForm.city || !spotKeyword.value.trim()) {
    return message.warning("请先选城市并输入景点关键词");
  }
  spotSearching.value = true;
  try {
    const resp = await listAdminSpots({
      city: ivForm.city,
      keyword: spotKeyword.value.trim(),
      page: 1,
      pageSize: 8,
    });
    spotHits.value = resp.items;
    if (spotHits.value.length === 0) message.info("没有搜到匹配景点（可核对 spot_id 是否存在）");
  } catch (e: unknown) {
    message.error(errOf(e));
  } finally {
    spotSearching.value = false;
  }
}

function pickSpot(s: AdminSpotItem) {
  ivForm.spotId = s.spot_id;
  ivForm.spotName = s.name;
  ivForm.city = s.city;
  spotHits.value = [];
  spotKeyword.value = "";
}

async function submitIntervention() {
  if (!ivForm.reason.trim()) return message.warning("请填写运营原因（展示与审计，必填）");
  if (isSpotAction.value) {
    if (!ivForm.spotId.trim()) return message.warning("请选择要干预的景点（搜索后点选，或直接填 spot_id）");
  } else {
    ivForm.city = ivForm.city || CITIES[0];
  }
  ivSaving.value = true;
  try {
    const resp = await saveAdminIntervention({
      target_type: isSpotAction.value ? "SPOT" : "CITY",
      target_id: isSpotAction.value ? ivForm.spotId.trim() : ivForm.city,
      action: ivForm.action,
      reason: ivForm.reason.trim(),
      ...(ivForm.effectiveFrom ? { effective_from: ivForm.effectiveFrom } : {}),
      ...(ivForm.effectiveUntil ? { effective_until: ivForm.effectiveUntil } : {}),
    });
    message.success(resp?.message || "干预已保存");
    resetIvForm();
    await loadInterventions();
  } catch (e: unknown) {
    message.error(errOf(e));
  } finally {
    ivSaving.value = false;
  }
}

async function removeIv(it: AdminInterventionItem) {
  const target = it.target_type === "CITY" ? `城市「${it.target_id}」` : `景点「${it.spot_name || it.target_id}」`;
  if (!window.confirm(`确认移除对${target}的「${ACTION_LABEL[it.action] || it.action}」干预？移除后推荐流恢复算法排序。`)) return;
  try {
    await removeAdminIntervention(it.id);
    message.success("已移除");
    await loadInterventions();
  } catch (e: unknown) {
    message.error(errOf(e));
  }
}

const STATUS_LABEL: Record<string, string> = { ACTIVE: "生效中", SCHEDULED: "待生效", EXPIRED: "已过期" };

function targetLabel(it: AdminInterventionItem): string {
  return it.target_type === "CITY" ? `城市 · ${it.target_id}` : `景点 · ${it.spot_name || it.target_id}${it.spot_city ? `（${it.spot_city}）` : ""}`;
}

function ivTitle(it: AdminInterventionItem): string {
  return `${ACTION_LABEL[it.action] || it.action} · ${targetLabel(it)}`;
}

function onFeedTypeChange() {
  expForm.value.strategy = expForm.value.feedType === "SPOT_FEED" ? "QUALITY_GATE" : "LOW_QUALITY_FILTER";
}

function strategyLabel(e: ExperimentItem): string {
  if (e.strategy === "QUALITY_GATE") return "攻略质量门（只保留有真实攻略的候选）";
  if (e.strategy === "LOW_QUALITY_FILTER") return "低质过滤（剔除 low_quality 帖子）";
  return e.strategy;
}

function rateOf(part: number, total: number): string {
  return total <= 0 ? "—" : `${Math.round((part / total) * 1000) / 10}%`;
}

/**
 * A/B 效果对照行：从 by_variant_metrics 取对照/处理组各自的曝光与率。
 * 只有曝光分布（by_variant）回答不了"新策略有没有更好"，必须按变体拆开命中率/收藏率。
 * 没有曝光的组不显示（避免展示 0% 被误读成"该组表现差"）。
 */
function variantCompare(m: FeedMetricsItem): {
  key: string;
  label: string;
  exposures: number;
  hitRate: number;
  saveRate: number;
  dislikeRate: number;
}[] {
  const src = m.by_variant_metrics || {};
  const order = [
    { key: "CONTROL", label: "对照（现状）" },
    { key: "TREATMENT", label: "处理组（新策略）" },
  ];
  return order
    .filter((o) => src[o.key] && src[o.key].exposures > 0)
    .map((o) => ({
      key: o.key,
      label: o.label,
      exposures: src[o.key].exposures,
      hitRate: src[o.key].hit_rate,
      saveRate: src[o.key].save_rate,
      dislikeRate: src[o.key].dislike_rate,
    }));
}

async function loadAll() {
  await Promise.all([loadExperiments(), loadMonitor(), loadQuality(), loadInterventions()]);
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

async function loadQuality() {
  qualityLoading.value = true;
  qualityError.value = "";
  try {
    quality.value = await getRecommendationCityQuality(qualityDays.value, 50);
  } catch {
    // 请求本身失败与"后端降级返回"是两种状态，页面要分开提示
    quality.value = null;
    qualityError.value = "城市推荐质量加载失败，请稍后重试。";
  } finally {
    qualityLoading.value = false;
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
    message.success("实验已创建（确定性哈希粘性分桶）");
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

/** 各城市推荐量按数量降序（对象遍历顺序不稳定，展示前显式排序） */
function cityEntries(byCity?: Record<string, number>): { city: string; count: number }[] {
  return Object.entries(byCity || {})
    .map(([city, count]) => ({ city, count: Number(count) || 0 }))
    .sort((a, b) => b.count - a.count);
}

/** 平均质量分分档（可信度加权：VERIFIED 100 / GUIDE_MATCHED 70 / POI_ONLY 40） */
function qualityBadgeClass(score: number): string {
  if (score >= 80) return "ad-badge--ok";
  if (score >= 60) return "ad-badge--info";
  return "ad-badge--no";
}

onMounted(loadAll);
</script>

<template>
  <div class="ad-card">
    <div class="ad-form-row" style="justify-content: space-between">
      <p class="ad-title">推荐运营</p>
      <div class="ad-seg">
        <button
          type="button"
          :class="['ad-seg__btn', { 'ad-seg__btn--on': seg === 'experiments' }]"
          @click="seg = 'experiments'"
        >
          🧪 A/B 实验
        </button>
        <button type="button" :class="['ad-seg__btn', { 'ad-seg__btn--on': seg === 'monitor' }]" @click="seg = 'monitor'">
          📈 推荐流监控
        </button>
        <button type="button" :class="['ad-seg__btn', { 'ad-seg__btn--on': seg === 'quality' }]" @click="seg = 'quality'">
          🏙 城市推荐质量
        </button>
        <button type="button" :class="['ad-seg__btn', { 'ad-seg__btn--on': seg === 'interventions' }]" @click="seg = 'interventions'">
          🎛 人工干预
        </button>
      </div>
    </div>

    <template v-if="seg === 'experiments'">
      <p class="ad-sub">同作用域同时只允许一个进行中实验；用户分桶由用户ID确定性哈希决定，实验期内不换桶。</p>
      <div class="ad-card" style="box-shadow: none; border: 1px solid #ece8e0; margin-bottom: 12px">
        <div class="ad-form-row">
          <input v-model="expForm.name" class="ad-input" style="flex: 1; min-width: 160px" placeholder="实验名（小写字母/数字/下划线）" maxlength="60" />
          <select v-model="expForm.feedType" class="ad-select" @change="onFeedTypeChange">
            <option value="SPOT_FEED">推荐景点流</option>
            <option value="POST_FEED">帖子推荐流</option>
          </select>
          <select v-model="expForm.strategy" class="ad-select">
            <option value="QUALITY_GATE">攻略质量门</option>
            <option value="LOW_QUALITY_FILTER">低质过滤</option>
          </select>
          <input v-model.number="expForm.trafficPercent" class="ad-input" style="width: 76px" type="number" min="1" max="100" title="参与流量%" />
          <input v-model.number="expForm.controlPercent" class="ad-input" style="width: 76px" type="number" min="0" max="100" title="对照组占比%" />
          <button type="button" class="ad-btn ad-btn--ok" :disabled="creating" @click="submitExperiment">创建</button>
        </div>
        <p class="ad-item__meta" style="margin-top: 6px">流量 100 / 对照 50 = 一半用户看新策略、一半看现状。</p>
      </div>

      <div v-if="experiments.length === 0" class="ad-empty">还没有实验</div>
      <div v-else class="ad-list">
        <div v-for="e in experiments" :key="e.id" class="ad-item">
          <div class="ad-item__main">
            <p class="ad-item__title">
              {{ e.name }}
              <span class="ad-badge" :class="e.status === 'ACTIVE' ? 'ad-badge--ok' : 'ad-badge--no'">
                {{ e.status === "ACTIVE" ? "进行中" : "已关闭" }}
              </span>
              <span class="ad-badge ad-badge--info">{{ e.feed_type === "POST_FEED" ? "帖子推荐流" : "推荐景点流" }}</span>
            </p>
            <p class="ad-item__meta">
              {{ strategyLabel(e) }} · 流量 {{ e.traffic_percent }}% / 对照 {{ e.control_percent }}% ·
              对照 {{ e.control_users }} 人 / 处理组 {{ e.treatment_users }} 人
              <template v-if="e.description"> · {{ e.description }}</template>
            </p>
          </div>
          <div class="ad-ops">
            <button v-if="e.status === 'ACTIVE'" type="button" class="ad-btn ad-btn--no" @click="closeExp(e.name)">
              关闭实验
            </button>
          </div>
        </div>
      </div>
    </template>

    <template v-else-if="seg === 'monitor'">
      <div class="ad-form-row" style="margin-bottom: 12px">
        <span class="ad-item__meta">时间窗：</span>
        <select v-model.number="monitorDays" class="ad-select" @change="void loadMonitor()">
          <option :value="1">近 1 天</option>
          <option :value="7">近 7 天</option>
          <option :value="30">近 30 天</option>
        </select>
      </div>
      <template v-if="monitor">
        <div v-if="monitor.degraded" class="ad-degraded" style="margin-bottom: 12px">
          <span>
            部分数据源读取失败（{{ monitor.errors.join("、") }}），以下读数不可信，不代表真实为 0。
          </span>
          <button type="button" class="ad-retry" @click="void loadMonitor()">重试</button>
        </div>
        <div v-for="(m, feed) in monitor.feeds" :key="feed" class="ad-card" style="margin-bottom: 12px">
          <p class="ad-item__title">{{ feed === "POST_FEED" ? "帖子推荐流（为你推荐）" : "推荐景点流（为你推荐）" }}</p>
          <div class="ad-kpis">
            <div class="ad-kpi"><b>{{ m.exposures }}</b><span>曝光</span></div>
            <div class="ad-kpi"><b>{{ m.users }}</b><span>用户</span></div>
            <div class="ad-kpi"><b>{{ m.hit_rate }}%</b><span>偏好命中</span></div>
            <div class="ad-kpi"><b>{{ rateOf(m.feedbacks?.save || 0, m.exposures) }}</b><span>收藏率</span></div>
            <div class="ad-kpi"><b>{{ rateOf(m.feedbacks?.dislike || 0, m.exposures) }}</b><span>负反馈率</span></div>
          </div>
          <p class="ad-item__meta" style="margin-top: 8px">
            A/B 分布：
            <span v-for="(n, v) in m.by_variant" :key="v" class="ad-badge ad-badge--muted">
              {{ v === "NONE" ? "无实验" : v === "CONTROL" ? "对照" : "处理组" }} {{ n }}
            </span>
          </p>
          <!-- A/B 效果对照：曝光分布之外，按变体看命中率/收藏率才知道新策略有没有更好 -->
          <div v-if="variantCompare(m).length" style="margin-top: 10px">
            <p class="ad-item__meta" style="margin-bottom: 4px">A/B 效果对照（按变体）：</p>
            <div class="ad-table-wrap">
              <table class="ad-table">
                <thead>
                  <tr>
                    <th>分组</th>
                    <th>曝光</th>
                    <th>命中率</th>
                    <th>收藏率</th>
                    <th>负反馈率</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="r in variantCompare(m)" :key="r.key">
                    <td><b>{{ r.label }}</b></td>
                    <td>{{ r.exposures }}</td>
                    <td>{{ r.hitRate }}%</td>
                    <td>{{ r.saveRate }}%</td>
                    <td>{{ r.dislikeRate }}%</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
          <p v-if="feed === 'SPOT_FEED'" class="ad-item__meta">
            内容质量构成：
            <span v-for="(n, q) in m.by_quality" :key="q" class="ad-badge ad-badge--muted">
              {{ q === "POI_ONLY" ? "纯高德POI" : q === "GUIDE_MATCHED" ? "攻略命中" : q }} {{ n }}
            </span>
          </p>
          <!-- 各城市推荐量（§6「推荐流概览」）：看曝光是不是被少数城市吃掉 -->
          <p v-if="feed === 'SPOT_FEED' && cityEntries(m.by_city).length" class="ad-item__meta">
            各城市推荐量：
            <span v-for="c in cityEntries(m.by_city)" :key="c.city" class="ad-badge ad-badge--muted">
              {{ c.city === "UNKNOWN" ? "未知城市" : c.city }} {{ c.count }}
            </span>
          </p>
        </div>
      </template>
      <div v-else class="ad-empty">暂无曝光数据 —— 有用户刷「为你推荐」并互动后这里会出现读数</div>
    </template>

    <!-- ===== 城市推荐质量（设计方案 §6）：把全站读数按城市切开 ===== -->
    <template v-else-if="seg === 'quality'">
      <p class="ad-sub">
        按城市拆开看推荐的"供货能力"和"用户买账程度"：<b>可推荐景点数</b>是该城市真正能推的货，
        <b>有攻略/无攻略</b>看内容支撑够不够（无攻略多 = 推荐理由只能靠高德数据，容易空洞），
        <b>收藏率/负反馈率</b>看用户态度。反馈率只统计「先曝光、后行为」，与「推荐流监控」同口径。
      </p>

      <div class="ad-form-row" style="margin-bottom: 12px; flex-wrap: wrap; row-gap: 8px">
        <span class="ad-item__meta">反馈窗口：</span>
        <select v-model.number="qualityDays" class="ad-select" @change="void loadQuality()">
          <option :value="7">近 7 天</option>
          <option :value="30">近 30 天</option>
          <option :value="90">近 90 天</option>
        </select>
        <button type="button" class="ad-btn" :disabled="qualityLoading" @click="void loadQuality()">
          {{ qualityLoading ? "加载中…" : "刷新" }}
        </button>
        <span class="ad-item__meta">景点存量与最近同步/RAG 时间是当前状态，不受窗口影响</span>
      </div>

      <div v-if="qualityError" class="ad-degraded" style="margin-bottom: 12px">
        <span>{{ qualityError }}</span>
        <button type="button" class="ad-retry" @click="void loadQuality()">重试</button>
      </div>

      <template v-else-if="quality">
        <!-- 后端降级（某一路数据源读失败）与"真的没数据"必须区分开 -->
        <div v-if="quality.degraded" class="ad-degraded" style="margin-bottom: 12px">
          <span>
            部分数据源读取失败（{{ quality.errors.join("、") }}）—— 受影响的列不可信，
            空白不代表该城市真的没有数据。
          </span>
          <button type="button" class="ad-retry" @click="void loadQuality()">重试</button>
        </div>

        <div v-if="!quality.items.length" class="ad-empty">
          景点库里还没有可推荐的城市 —— 同步景点后这里会出现。
        </div>
        <div v-else class="ad-table-wrap">
          <table class="ad-table">
            <thead>
              <tr>
                <th>城市</th>
                <th>可推荐</th>
                <th>有攻略</th>
                <th>无攻略</th>
                <th>平均质量分</th>
                <th>曝光</th>
                <th>覆盖用户</th>
                <th>收藏率</th>
                <th>负反馈率</th>
                <th>最近同步</th>
                <th>最近 RAG 更新</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="q in quality.items" :key="q.city">
                <td><b>{{ q.city }}</b></td>
                <td>{{ q.recommendable_spots }}</td>
                <td>{{ q.guide_backed_spots }}</td>
                <td :class="{ 'ad-cell--warn': q.poi_only_spots > 0 }">{{ q.poi_only_spots }}</td>
                <td>
                  <span class="ad-badge" :class="qualityBadgeClass(q.avg_quality_score)">
                    {{ q.avg_quality_score }}
                  </span>
                </td>
                <td>{{ q.exposures }}</td>
                <td>{{ q.users }}</td>
                <!-- 无曝光时率没有分母，显示「—」而不是 0%，避免被读成"用户完全不买账" -->
                <td>{{ q.exposures > 0 ? q.save_rate + "%" : "—" }}</td>
                <td>{{ q.exposures > 0 ? q.dislike_rate + "%" : "—" }}</td>
                <td class="ad-item__meta">{{ q.last_synced_at || "暂无" }}</td>
                <td class="ad-item__meta">{{ q.last_rag_updated_at || "暂无" }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="ad-notes">
          <p class="ad-item__meta">指标口径（后端随响应下发，页面不另写一套，避免口径漂移）：</p>
          <ul>
            <li v-for="(n, i) in quality.notes" :key="i" class="ad-item__meta">{{ n }}</li>
          </ul>
          <p v-if="quality.generated_at" class="ad-item__meta">统计时间：{{ quality.generated_at }}</p>
        </div>
      </template>
    </template>

    <template v-else>
      <p class="ad-sub">
        运营在推荐链路上的低风险干预：<b>置顶/降权/黑名单</b>作用于景点、<b>城市精选</b>作用于城市。
        干预不改写任何算法分数，只在推荐流排序层生效（黑名单剔除 / 置顶优先 / 降权沉底），
        载荷以独立 interventions meta 输出并标注「人工干预」——运营规则与算法分数严格分层，每次保存/删除均审计留痕。
      </p>

      <!-- 新增干预 -->
      <div class="ad-card" style="box-shadow: none; border: 1px solid #ece8e0; margin-bottom: 12px">
        <div class="ad-form-row" style="flex-wrap: wrap; row-gap: 8px">
          <select v-model="ivForm.action" class="ad-select" style="width: 120px">
            <option v-for="a in ACTIONS" :key="a.value" :value="a.value">{{ a.label }}</option>
          </select>

          <template v-if="isSpotAction">
            <select v-model="ivForm.city" class="ad-select" style="width: 100px">
              <option v-for="c in CITIES" :key="c" :value="c">{{ c }}</option>
            </select>
            <input v-model="spotKeyword" class="ad-input" style="width: 160px" placeholder="景点关键词搜索" @keyup.enter="searchSpots" />
            <button class="ad-btn" type="button" :disabled="spotSearching" @click="searchSpots">{{ spotSearching ? "搜索中…" : "搜景点" }}</button>
            <template v-if="ivForm.spotId">
              <span class="ad-badge ad-badge--info">已选：{{ ivForm.spotName || ivForm.spotId }}（{{ ivForm.city }}）</span>
              <button class="ad-btn" type="button" @click="ivForm.spotId = ''; ivForm.spotName = ''">重选</button>
            </template>
            <div v-else-if="spotHits.length" class="ad-list" style="margin-top: 4px">
              <div v-for="s in spotHits" :key="s.id" class="ad-item" style="padding: 6px 10px">
                <div class="ad-item__main">
                  <p class="ad-item__title" style="font-size: 13px">
                    {{ s.name }} <span class="ad-badge ad-badge--muted">{{ s.city }}</span>
                  </p>
                  <p class="ad-item__meta">{{ s.category || "未分类" }} · {{ s.spot_id }}</p>
                </div>
                <div class="ad-ops">
                  <button class="ad-btn ad-btn--ok" type="button" @click="pickSpot(s)">选用</button>
                </div>
              </div>
            </div>
          </template>

          <select v-else v-model="ivForm.city" class="ad-select" style="width: 120px">
            <option v-for="c in CITIES" :key="c" :value="c">{{ c }}</option>
          </select>

          <input v-model="ivForm.reason" class="ad-input" style="flex: 1; min-width: 200px" placeholder="运营原因（必填，展示与审计）" maxlength="255" />
          <input v-model="ivForm.effectiveFrom" class="ad-input" style="width: 170px" type="datetime-local" title="生效时间（空=立即）" />
          <input v-model="ivForm.effectiveUntil" class="ad-input" style="width: 170px" type="datetime-local" title="失效时间（空=长期）" />
          <button class="ad-btn ad-btn--ok" type="button" :disabled="ivSaving" @click="submitIntervention">
            {{ ivSaving ? "保存中…" : "新增干预" }}
          </button>
        </div>
      </div>

      <!-- 干预列表 -->
      <div class="ad-form-row" style="margin-bottom: 12px">
        <select v-model="ivFilters.action" class="ad-select" style="width: 130px" @change="ivSearch">
          <option value="">全部动作</option>
          <option v-for="a in ACTIONS" :key="a.value" :value="a.value">{{ a.label }}</option>
        </select>
        <select v-model="ivFilters.scope" class="ad-select" style="width: 110px" @change="ivSearch">
          <option value="">全部状态</option>
          <option value="ACTIVE">生效中</option>
          <option value="SCHEDULED">待生效</option>
          <option value="EXPIRED">已过期</option>
        </select>
        <span class="ad-item__meta">干预不改变算法分数，仅排序/剔除 + 独立 meta 标识</span>
      </div>

      <div v-if="ivLoading" class="ad-empty">加载中…</div>
      <div v-else-if="interventions.length === 0" class="ad-empty">还没有人工干预 —— 在推荐流中手动置顶/降权/拉黑内容会显示在这里</div>
      <div v-else class="ad-list">
        <div v-for="it in interventions" :key="it.id" class="ad-item">
          <div class="ad-item__main">
            <p class="ad-item__title">
              {{ ivTitle(it) }}
              <span class="ad-badge ad-badge--warn" title="运营标记，区别于算法推荐">🧑‍✈️ 人工干预</span>
              <span class="ad-badge" :class="it.status === 'ACTIVE' ? 'ad-badge--ok' : it.status === 'SCHEDULED' ? 'ad-badge--info' : 'ad-badge--no'">
                {{ STATUS_LABEL[it.status] || it.status }}
              </span>
            </p>
            <p class="ad-item__meta">
              {{ ACTION_LABEL[it.action] || it.action }} · {{ targetLabel(it) }}
              <template v-if="it.effective_from"> · 生效 {{ it.effective_from.replace('T', ' ').slice(0, 16) }}</template>
              <template v-if="it.effective_until"> ~ {{ it.effective_until.replace('T', ' ').slice(0, 16) }}</template>
              <template v-else> · 长期生效</template>
            </p>
            <p class="ad-item__meta" v-if="it.reason">原因：{{ it.reason }}</p>
            <p class="ad-item__meta" v-if="it.created_at">登记于 {{ it.created_at.replace('T', ' ').slice(0, 16) }}（{{ it.created_by || "—" }}）</p>
          </div>
          <div class="ad-ops">
            <button class="ad-btn ad-btn--no" type="button" @click="removeIv(it)">移除</button>
          </div>
        </div>
      </div>

      <!-- 分页 -->
      <div v-if="ivTotal > ivFilters.pageSize" class="ad-card" style="display: flex; justify-content: space-between; align-items: center">
        <span style="font-size: 12px; color: #8c8a83">共 {{ ivTotal }} 条</span>
        <div class="ad-form-row">
          <button class="ad-btn" type="button" :disabled="ivFilters.page <= 1" @click="ivPage(ivFilters.page - 1)">上一页</button>
          <span style="font-size: 12px; color: #6b6861">第 {{ ivFilters.page }} 页</span>
          <button class="ad-btn" type="button" :disabled="ivFilters.page * ivFilters.pageSize >= ivTotal" @click="ivPage(ivFilters.page + 1)">下一页</button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
/* ===== 城市推荐质量表（11 列，窄屏横向滚动而不是压缩换行） ===== */
.ad-table-wrap {
  overflow-x: auto;
  border: 1px solid #ece8e0;
  border-radius: 10px;
  background: #fff;
}

.ad-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  white-space: nowrap;
}

.ad-table th,
.ad-table td {
  padding: 8px 10px;
  text-align: left;
  border-bottom: 1px solid #f0ece5;
}

.ad-table thead th {
  background: #faf8f4;
  font-size: 12px;
  font-weight: 600;
  color: #5b5750;
}

.ad-table tbody tr:last-child td {
  border-bottom: none;
}

.ad-table tbody tr:hover {
  background: #faf8f4;
}

/* 无攻略景点 > 0：内容支撑不足（推荐理由只能靠高德数据），值得运营关注 */
.ad-cell--warn {
  color: #b5713a;
  font-weight: 600;
}

.ad-notes {
  margin-top: 12px;
  padding: 10px 12px;
  border: 1px solid #f0ece5;
  border-radius: 10px;
  background: #faf8f4;
}

.ad-notes ul {
  margin: 6px 0 0;
  padding-left: 18px;
}

.ad-notes li {
  line-height: 1.7;
}
</style>
