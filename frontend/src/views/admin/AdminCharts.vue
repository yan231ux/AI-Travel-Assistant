<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { message } from "ant-design-vue";

import type {
  AnalyticsResp,
  CityHeatRow,
  ModerationFunnel,
  RagStatus,
  RiskBreakdownRow,
  SpotAdoptRow,
  TrendRow,
} from "../../services/api";
import {
  getCityHeat,
  getModerationFunnel,
  getRagStatus,
  getRiskBreakdown,
  getSpotAdoption,
  getTrend,
} from "../../services/api";
import EChartPanel from "../../components/admin/EChartPanel.vue";

/**
 * 数据看板（设计方案 §5.3 六张图，阶段四可视化）：
 * 景点采用排行 / 城市热度 / 审核漏斗 / 风险构成 / 规划趋势 / RAG 状态。
 * 每张图支持 图表 ⇄ 数值表 切换（不只展示图形，必须能看具体数值），
 * 查询失败显式降级（degraded/errors），不伪装成 0。
 */
const days = ref<7 | 30>(7);
const cityFilter = ref("");

function unwrap<T>(resp: AnalyticsResp<T>): { data: T | null; error: string | null } {
  if (resp.degraded && resp.errors.length > 0) {
    return { data: resp.data, error: `统计查询失败：${resp.errors[0]}` };
  }
  return { data: resp.data, error: null };
}

/* 图表一：景点采用 */
const spotRows = ref<SpotAdoptRow[] | null>(null);
const spotErr = ref<string | null>(null);
const spotLoading = ref(true);

/* 图表二：城市热度 */
const cityRows = ref<CityHeatRow[] | null>(null);
const cityErr = ref<string | null>(null);
const cityLoading = ref(true);

/* 图表三：审核漏斗 */
const funnel = ref<ModerationFunnel | null>(null);
const funnelErr = ref<string | null>(null);
const funnelLoading = ref(true);

/* 图表四：风险构成 */
const riskRows = ref<RiskBreakdownRow[] | null>(null);
const riskErr = ref<string | null>(null);
const riskLoading = ref(true);

/* 图表五：规划趋势 */
const trendRows = ref<TrendRow[] | null>(null);
const trendErr = ref<string | null>(null);
const trendLoading = ref(true);

/* 图表六：RAG 状态 */
const rag = ref<RagStatus | null>(null);
const ragErr = ref<string | null>(null);
const ragLoading = ref(true);

/* 图表 ⇄ 数值表 切换 */
const view = ref<Record<string, "chart" | "table">>({
  spot: "chart",
  city: "chart",
  funnel: "chart",
  risk: "chart",
  trend: "chart",
  rag: "chart",
});
function toggle(key: string) {
  view.value[key] = view.value[key] === "chart" ? "table" : "chart";
}

/* ============ ECharts option 构建 ============ */

const RISK_LABEL: Record<string, string> = { LOW: "低风险", MEDIUM: "中风险", HIGH: "高风险", CRITICAL: "严重" };

const RAG_LABEL: Record<string, string> = {
  READY: "已就绪",
  NOT_INDEXED: "未索引",
  INDEXING: "索引中",
  FAILED: "失败",
  PENDING: "待索引",
};

const TASK_LABEL: Record<string, string> = {
  READY: "就绪",
  PENDING: "待执行",
  RUNNING: "执行中",
  FAILED: "失败",
  SUPERSEDED: "已过期",
};

const COMMON_AXIS_LABEL = { fontSize: 11, color: "#6b6b67" };
const COMMON_LEGEND = {
  top: 4,
  left: "center",
  orient: "horizontal",
  itemGap: 22,
  icon: "circle",
  textStyle: { fontSize: 12, color: "#4a4a46" },
  itemHeight: 9,
  itemWidth: 12,
};

const spotOption = computed(() => {
  const rows = (spotRows.value || []).slice(0, 8);
  if (rows.length === 0) return null;
  const sorted = [...rows].reverse();
  return {
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    grid: { left: 4, right: 28, top: 10, bottom: 16, containLabel: true },
    xAxis: {
      type: "value",
      minInterval: 1,
      splitLine: { lineStyle: { type: "dashed", color: "#f0f0f0" } },
      axisLabel: COMMON_AXIS_LABEL,
    },
    yAxis: {
      type: "category",
      data: sorted.map((r) => {
        const name = (r.item_name || String(r.item_id)).slice(0, 12);
        return r.city ? `${name} · ${r.city}` : name;
      }),
      axisLabel: { ...COMMON_AXIS_LABEL, width: 150, overflow: "truncate" },
      axisTick: { show: false },
    },
    series: [
      {
        name: "采用次数",
        type: "bar",
        barWidth: 16,
        data: sorted.map((r) => Number(r.adopt_count)),
        itemStyle: { color: "#1677ff", borderRadius: [0, 4, 4, 0] },
        label: { show: true, position: "insideRight", fontSize: 10, color: "#fff", formatter: "{c}" },
      },
      {
        name: "用户数",
        type: "bar",
        barWidth: 16,
        data: sorted.map((r) => Number(r.adopt_users)),
        itemStyle: { color: "#69b1ff", borderRadius: [0, 4, 4, 0] },
        label: { show: true, position: "insideRight", fontSize: 10, color: "#fff", formatter: "{c}" },
      },
    ],
  };
});

const cityOption = computed(() => {
  const rows = cityRows.value || [];
  if (rows.length === 0) return null;
  const dense = rows.length > 6;
  return {
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    legend: COMMON_LEGEND,
    grid: { left: 4, right: 8, top: 40, bottom: dense ? 56 : 28, containLabel: true },
    xAxis: {
      type: "category",
      data: rows.map((r) => r.city),
      axisLabel: { ...COMMON_AXIS_LABEL, rotate: dense ? 35 : 0, interval: 0 },
      axisTick: { alignWithLabel: true },
    },
    yAxis: { type: "value", minInterval: 1, splitLine: { lineStyle: { type: "dashed", color: "#f0f0f0" } }, axisLabel: COMMON_AXIS_LABEL },
    series: [
      { name: "行程生成", type: "bar", barMaxWidth: 22, data: rows.map((r) => Number(r.trip_generated)), itemStyle: { color: "#1677ff" } },
      { name: "行程保存", type: "bar", barMaxWidth: 22, data: rows.map((r) => Number(r.trip_saved)), itemStyle: { color: "#36cfc9" } },
      { name: "景点采用", type: "bar", barMaxWidth: 22, data: rows.map((r) => Number(r.spot_adopt)), itemStyle: { color: "#95de64" } },
      { name: "活跃用户", type: "bar", barMaxWidth: 22, data: rows.map((r) => Number(r.active_users)), itemStyle: { color: "#ffd666" } },
    ],
  };
});

const funnelOption = computed(() => {
  const f = funnel.value;
  if (!f || !f.total) return null;
  const stages = [
    { name: "总任务", value: Number(f.total) },
    { name: "AI 放行", value: Number(f.auto_passed || 0) },
    { name: "待复核", value: Number(f.review || 0) },
    { name: "人工通过", value: Number(f.human_approved || 0) },
    { name: "人工拒绝", value: Number(f.human_rejected || 0) },
  ];
  return {
    tooltip: { trigger: "item", formatter: "{b}: {c}" },
    series: [
      {
        type: "funnel",
        left: "8%",
        width: "50%",
        top: "2%",
        bottom: "2%",
        sort: "none",
        minSize: "8%",
        label: {
          show: true,
          position: "right",
          formatter: "{b}: {c}",
          fontSize: 12,
          color: "#4a4a46",
          lineHeight: 18,
        },
        labelLine: { show: true, length: 14, length2: 10 },
        data: stages,
      },
    ],
  };
});

const riskOption = computed(() => {
  const rows = riskRows.value || [];
  if (rows.length === 0) return null;
  const colors: Record<string, string> = {
    LOW: "#95de64",
    MEDIUM: "#ffd666",
    HIGH: "#ff9c6e",
    CRITICAL: "#ff4d4f",
  };
  return {
    tooltip: { trigger: "item", formatter: "{b}: {c}（{d}%）" },
    legend: {
      orient: "vertical",
      right: 4,
      top: "middle",
      itemGap: 12,
      textStyle: { fontSize: 12, color: "#4a4a46" },
      icon: "circle",
      itemHeight: 10,
      itemWidth: 10,
    },
    series: [
      {
        type: "pie",
        radius: ["45%", "78%"],
        center: ["40%", "50%"],
        label: {
          show: true,
          formatter: "{b}\n{c}",
          fontSize: 11,
          color: "#4a4a46",
          lineHeight: 15,
        },
        labelLine: { length: 8, length2: 6 },
        data: rows.map((r) => ({
          name: RISK_LABEL[r.risk_level] || r.risk_level,
          value: Number(r.cnt),
          itemStyle: { color: colors[r.risk_level] || "#bfbfbf" },
        })),
      },
    ],
  };
});

const trendOption = computed(() => {
  const rows = trendRows.value || [];
  if (rows.length === 0) return null;
  const dates = rows.map((r) => String(r.stat_date).slice(5));
  return {
    tooltip: { trigger: "axis" },
    legend: COMMON_LEGEND,
    grid: { left: 4, right: 14, top: 40, bottom: 28, containLabel: true },
    xAxis: { type: "category", data: dates, boundaryGap: true, axisLabel: COMMON_AXIS_LABEL },
    yAxis: { type: "value", minInterval: 1, splitLine: { lineStyle: { type: "dashed", color: "#f0f0f0" } }, axisLabel: COMMON_AXIS_LABEL },
    series: [
      { name: "行程生成", type: "line", smooth: false, symbolSize: 7, lineStyle: { width: 2.5 }, data: rows.map((r) => Number(r.trips)) },
      { name: "行程保存", type: "line", smooth: false, symbolSize: 7, lineStyle: { width: 2.5 }, data: rows.map((r) => Number(r.saves)) },
      { name: "景点采用", type: "line", smooth: false, symbolSize: 7, lineStyle: { width: 2.5 }, data: rows.map((r) => Number(r.adopts)) },
      { name: "景点收藏", type: "line", smooth: false, symbolSize: 7, lineStyle: { width: 2.5 }, data: rows.map((r) => Number(r.favorites)) },
    ],
  };
});

const ragOption = computed(() => {
  const r = rag.value;
  if (!r) return null;
  const guides = r.guides || [];
  const tasks = r.tasks || [];
  if (guides.length === 0 && tasks.length === 0) return null;
  return {
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    labelLayout: { hideOverlap: true },
    grid: { left: 4, right: 8, top: 20, bottom: 20, containLabel: true },
    xAxis: { type: "category", data: ["攻略 RAG 状态", "索引任务状态"], axisLabel: { ...COMMON_AXIS_LABEL, fontSize: 12 } },
    yAxis: { type: "value", minInterval: 1, splitLine: { lineStyle: { type: "dashed", color: "#f0f0f0" } }, axisLabel: COMMON_AXIS_LABEL },
    series: guides
      .filter((g) => Number(g.cnt) > 0)
      .map((g) => {
        const label = RAG_LABEL[g.rag_status] || g.rag_status;
        return {
          name: `攻略:${label}`,
          type: "bar",
          stack: "guide",
          barWidth: 80,
          data: [Number(g.cnt), null],
          label: {
            show: true,
            position: "inside",
            rotate: 0,
            fontSize: 11,
            fontWeight: "bold",
            color: "#fff",
            textBorderColor: "rgba(0,0,0,0.35)",
            textBorderWidth: 2,
            formatter: (p: { value?: number | null }) => (!p.value || p.value <= 0 ? "" : `${label}: ${p.value}`),
          },
        };
      })
      .concat(
        tasks
          .filter((t) => Number(t.cnt) > 0)
          .map((t) => {
            const label = TASK_LABEL[t.status] || t.status;
            return {
              name: `任务:${label}`,
              type: "bar",
              stack: "task",
              barWidth: 80,
              data: [null, Number(t.cnt)],
              label: {
                show: true,
                position: "inside",
                rotate: 0,
                fontSize: 11,
                fontWeight: "bold",
                color: "#fff",
                textBorderColor: "rgba(0,0,0,0.35)",
                textBorderWidth: 2,
                formatter: (p: { value?: number | null }) => (!p.value || p.value <= 0 ? "" : `${label}: ${p.value}`),
              },
            };
          })
      ),
  };
});

async function loadAll() {
  spotLoading.value = cityLoading.value = funnelLoading.value = true;
  riskLoading.value = trendLoading.value = ragLoading.value = true;
  const [spot, city, fn, risk, trend, ragResp] = await Promise.allSettled([
    getSpotAdoption(days.value, cityFilter.value || undefined),
    getCityHeat(days.value),
    getModerationFunnel(days.value),
    getRiskBreakdown(days.value),
    getTrend(Math.min(days.value, 14)),
    getRagStatus(),
  ]);
  const failures: string[] = [];
  if (spot.status === "fulfilled") {
    const u = unwrap(spot.value);
    spotRows.value = u.data;
    spotErr.value = u.error;
    if (u.error) failures.push(u.error);
  } else spotErr.value = "请求失败";
  if (city.status === "fulfilled") {
    const u = unwrap(city.value);
    cityRows.value = u.data;
    cityErr.value = u.error;
    if (u.error) failures.push(u.error);
  } else cityErr.value = "请求失败";
  if (fn.status === "fulfilled") {
    const u = unwrap(fn.value);
    funnel.value = u.data;
    funnelErr.value = u.error;
    if (u.error) failures.push(u.error);
  } else funnelErr.value = "请求失败";
  if (risk.status === "fulfilled") {
    const u = unwrap(risk.value);
    riskRows.value = u.data;
    riskErr.value = u.error;
    if (u.error) failures.push(u.error);
  } else riskErr.value = "请求失败";
  if (trend.status === "fulfilled") {
    const u = unwrap(trend.value);
    trendRows.value = u.data;
    trendErr.value = u.error;
    if (u.error) failures.push(u.error);
  } else trendErr.value = "请求失败";
  if (ragResp.status === "fulfilled") {
    const u = unwrap(ragResp.value);
    rag.value = u.data;
    ragErr.value = u.error;
    if (u.error) failures.push(u.error);
  } else ragErr.value = "请求失败";
  if (failures.length > 0) {
    message.warning(failures[0]);
  }
  spotLoading.value = cityLoading.value = funnelLoading.value = false;
  riskLoading.value = trendLoading.value = ragLoading.value = false;
}

function switchWindow() {
  void loadAll();
}

const anyDegraded = computed(
  () =>
    !!(spotErr.value || cityErr.value || funnelErr.value || riskErr.value || trendErr.value || ragErr.value)
);

onMounted(loadAll);
</script>

<template>
  <div v-if="anyDegraded" class="ad-degraded">
    ⚠️ 部分统计查询失败，以下图表按显式降级展示（不显示假 0）。
    <button class="ad-retry" @click="loadAll">重试</button>
  </div>

  <div class="ad-card">
    <div class="ac-head">
      <div>
        <p class="ad-title">📈 数据看板（近 {{ days }} 天）</p>
        <p class="ad-sub">六张核心图：谁被规划最多、哪个城市最热、审核效率与风险、增长趋势、索引健康。每张图可切换数值表。</p>
      </div>
      <div class="ac-filters">
        <select v-model.number="days" class="ac-select" @change="switchWindow">
          <option :value="7">近 7 天</option>
          <option :value="30">近 30 天</option>
        </select>
        <input v-model="cityFilter" class="ac-select" placeholder="城市筛选（图一）" @keyup.enter="switchWindow" />
        <button class="ad-btn" @click="loadAll">刷新</button>
      </div>
    </div>
  </div>

  <div class="ac-grid">
    <!-- 图表一 -->
    <div class="ad-card ac-card">
      <div class="ac-card__head">
        <p class="ac-card__title">① 景点规划采用排行</p>
        <button class="ad-btn" @click="toggle('spot')">{{ view.spot === "chart" ? "数值表" : "图表" }}</button>
      </div>
      <EChartPanel v-if="view.spot === 'chart'" :option="spotOption" height="400px"
                   :loading="spotLoading" :error="spotErr" empty-text="窗口内暂无景点被规划采用" />
      <table v-else class="ac-table">
        <thead><tr><th>景点</th><th>城市</th><th>采用次数</th><th>用户数</th></tr></thead>
        <tbody>
          <tr v-for="r in spotRows || []" :key="r.item_id">
            <td>{{ r.item_name }}</td><td>{{ r.city }}</td><td>{{ r.adopt_count }}</td><td>{{ r.adopt_users }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 图表二 -->
    <div class="ad-card ac-card">
      <div class="ac-card__head">
        <p class="ac-card__title">② 城市热度排行</p>
        <button class="ad-btn" @click="toggle('city')">{{ view.city === "chart" ? "数值表" : "图表" }}</button>
      </div>
      <EChartPanel v-if="view.city === 'chart'" :option="cityOption" height="340px"
                   :loading="cityLoading" :error="cityErr" empty-text="窗口内暂无行程数据" />
      <table v-else class="ac-table">
        <thead><tr><th>城市</th><th>生成</th><th>保存</th><th>景点采用</th><th>活跃用户</th></tr></thead>
        <tbody>
          <tr v-for="r in cityRows || []" :key="r.city">
            <td>{{ r.city }}</td><td>{{ r.trip_generated }}</td><td>{{ r.trip_saved }}</td>
            <td>{{ r.spot_adopt }}</td><td>{{ r.active_users }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 图表三 -->
    <div class="ad-card ac-card">
      <div class="ac-card__head">
        <p class="ac-card__title">③ 内容审核漏斗</p>
        <button class="ad-btn" @click="toggle('funnel')">{{ view.funnel === "chart" ? "数值表" : "图表" }}</button>
      </div>
      <EChartPanel v-if="view.funnel === 'chart'" :option="funnelOption" height="360px"
                   :loading="funnelLoading" :error="funnelErr" empty-text="窗口内暂无审核任务" />
      <table v-else class="ac-table">
        <tbody>
          <tr><th>总任务</th><td>{{ funnel?.total ?? "—" }}</td><th>AI 自动放行</th><td>{{ funnel?.auto_passed ?? "—" }}</td></tr>
          <tr><th>待人工复核</th><td>{{ funnel?.review ?? "—" }}</td><th>AI 失败</th><td>{{ funnel?.ai_failed ?? "—" }}</td></tr>
          <tr><th>人工通过</th><td>{{ funnel?.human_approved ?? "—" }}</td><th>人工拒绝</th><td>{{ funnel?.human_rejected ?? "—" }}</td></tr>
          <tr><th>规则命中</th><td>{{ funnel?.rule_hits ?? "—" }}</td><th></th><td></td></tr>
        </tbody>
      </table>
    </div>

    <!-- 图表四 -->
    <div class="ad-card ac-card">
      <div class="ac-card__head">
        <p class="ac-card__title">④ 内容风险构成</p>
        <button class="ad-btn" @click="toggle('risk')">{{ view.risk === "chart" ? "数值表" : "图表" }}</button>
      </div>
      <EChartPanel v-if="view.risk === 'chart'" :option="riskOption" height="300px"
                   :loading="riskLoading" :error="riskErr" empty-text="窗口内暂无风险记录" />
      <table v-else class="ac-table">
        <thead><tr><th>风险等级</th><th>任务数</th></tr></thead>
        <tbody>
          <tr v-for="r in riskRows || []" :key="r.risk_level">
            <td>{{ RISK_LABEL[r.risk_level] || r.risk_level }}</td><td>{{ r.cnt }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 图表五 -->
    <div class="ad-card ac-card ac-card--wide">
      <div class="ac-card__head">
        <p class="ac-card__title">⑤ 规划与收藏趋势（≤14 天）</p>
        <button class="ad-btn" @click="toggle('trend')">{{ view.trend === "chart" ? "数值表" : "图表" }}</button>
      </div>
      <EChartPanel v-if="view.trend === 'chart'" :option="trendOption" height="300px"
                   :loading="trendLoading" :error="trendErr" empty-text="窗口内暂无事件数据" />
      <table v-else class="ac-table">
        <thead><tr><th>日期</th><th>行程生成</th><th>行程保存</th><th>景点采用</th><th>景点收藏</th></tr></thead>
        <tbody>
          <tr v-for="r in trendRows || []" :key="r.stat_date">
            <td>{{ String(r.stat_date).slice(0, 10) }}</td><td>{{ r.trips }}</td><td>{{ r.saves }}</td>
            <td>{{ r.adopts }}</td><td>{{ r.favorites }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 图表六 -->
    <div class="ad-card ac-card">
      <div class="ac-card__head">
        <p class="ac-card__title">⑥ RAG 索引状态</p>
        <button class="ad-btn" @click="toggle('rag')">{{ view.rag === "chart" ? "数值表" : "图表" }}</button>
      </div>
      <EChartPanel v-if="view.rag === 'chart'" :option="ragOption" height="360px"
                   :loading="ragLoading" :error="ragErr" empty-text="暂无攻略/索引任务" />
      <table v-else class="ac-table">
        <tbody>
          <tr><th>攻略 RAG 状态</th><td>{{ (rag?.guides || []).map(g => `${g.rag_status}:${g.cnt}`).join(" / ") || "—" }}</td></tr>
          <tr><th>索引任务状态</th><td>{{ (rag?.tasks || []).map(t => `${t.status}:${t.cnt}`).join(" / ") || "—" }}</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.ac-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.ac-filters {
  display: flex;
  gap: 8px;
  align-items: center;
}
.ac-select {
  padding: 6px 10px;
  border: 1px solid var(--border, #ddd);
  border-radius: 8px;
  background: transparent;
  color: inherit;
}
.ac-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  margin-top: 14px;
}
.ac-card--wide {
  grid-column: span 2;
}
@media (max-width: 960px) {
  .ac-grid {
    grid-template-columns: 1fr;
  }
  .ac-card--wide {
    grid-column: span 1;
  }
}
.ac-card__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.ac-card__title {
  font-weight: 600;
}
.ac-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.ac-table th,
.ac-table td {
  border-top: 1px dashed var(--border, #ddd);
  padding: 6px 8px;
  text-align: left;
}
</style>
