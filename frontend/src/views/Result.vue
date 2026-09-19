<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { message } from "ant-design-vue";
import { useRouter } from "vue-router";

import AmapTripMap from "../components/AmapTripMap.vue";
import {
  exportMarkdown,
  exportPdf,
  fetchWeatherForecast,
  reportBehavior,
  saveTrip,
} from "../services/api";
import { beginReplay, latestItinerary, latestTrace, latestTokenUsage } from "../stores/trip";
import type { DayPlan, FilteredCandidate, Itinerary, MealItem, PersonalizationSummary, SpotItem, TransportItem, WeatherForecastResponse } from "../types";

/** 行程产物统一来自 trip store（Home 生成 / Agent finished / History 打开 写入） */
const router = useRouter();
const itinerary = computed(() => latestItinerary.value);
const agentTrace = computed(() => latestTrace.value);
const tokenUsage = computed(() => latestTokenUsage.value);

function goPlan() {
  void router.push({ name: "plan" });
}

function goHistory() {
  void router.push({ name: "history" });
}

/** 🎬 动画回放：写入回放入参并进入 agent replay 视图 */
function playReplay() {
  if (!itinerary.value) return;
  beginReplay(itinerary.value, agentTrace.value);
  void router.push({ name: "agent" });
}

const saving = ref(false);
const exportingPdf = ref(false);
const exportingMarkdown = ref(false);
const weatherLoading = ref(false);
const weatherError = ref("");
const weather = ref<WeatherForecastResponse | null>(null);
const failedImageKeys = ref(new Set<string>());

// 后端 TokenUsage 序列化为 snake_case 的多个细分字段，这里按"输入/输出"归类求和展示
const tokenSummary = computed(() => {
  const u = tokenUsage.value;
  if (!u) return null;
  const promptKeys = ["prompt_tokens", "rewrite_prompt_tokens", "embedding_prompt_tokens", "planner_prompt_tokens", "rerank_prompt_tokens"];
  const completionKeys = ["completion_tokens", "rewrite_completion_tokens", "embedding_completion_tokens", "planner_completion_tokens", "rerank_completion_tokens"];
  const sum = (keys: string[]) => keys.reduce((s, k) => s + (Number(u[k]) || 0), 0);
  const prompt = sum(promptKeys);
  const completion = sum(completionKeys);
  if (prompt === 0 && completion === 0) return null;
  return { prompt, completion, total: prompt + completion };
});

function formatShortDate(dateText?: string | null): string {
  if (!dateText) return "待定";
  const parts = dateText.split("-");
  return parts.length !== 3 ? dateText : `${parts[1]}-${parts[2]}`;
}

function formatWeatherDate(dateText?: string | null, week?: string | null): string {
  const weekdayMap: Record<string, string> = {
    "1": "周一", "2": "周二", "3": "周三", "4": "周四",
    "5": "周五", "6": "周六", "7": "周日",
  };
  const weekday = week ? weekdayMap[week] || `周${week}` : "";
  return [formatShortDate(dateText), weekday].filter(Boolean).join(" ");
}

const budgetItems = computed(() => {
  if (!itinerary.value) return [];
  const b = itinerary.value.budget_breakdown;
  return [
    { label: "景点门票", value: `¥${b.tickets.toFixed(0)}` },
    { label: "酒店住宿", value: `¥${b.hotel.toFixed(0)}` },
    { label: "餐饮费用", value: `¥${b.meals.toFixed(0)}` },
    { label: "交通费用", value: `¥${b.transport.toFixed(0)}` },
  ];
});

/* ========== P1 确定性面板：预算健康度（PLAN §5.3；行程请求快照缺失则不展示） ========== */
const budgetHealth = computed(() => {
  const it = itinerary.value;
  if (!it) return null;
  const req = it.requested_budget;
  if (req == null || req <= 0 || it.estimated_budget == null) return null;
  const est = it.estimated_budget;
  const travelers = it.travelers || 1;
  const days = it.trip_days || it.days.length || 1;
  const usage = est / req;
  const pct = Math.min(999, Math.round(usage * 100));
  const remain = Math.max(0, req - est);
  const perDay = est / travelers / days;
  const over = est > req;
  const statusText = over ? "超出预算" : usage >= 0.9 ? "接近预算上限" : "在计划范围内";
  return { req, est, pct, remain, perDay, over, statusText };
});

/* ========== P1 确定性面板：数据可信度统计（PLAN §5.4/§6；全按 item.source 分类计数） ========== */
interface TrustBucket {
  key: string;
  label: string;
  tone: SourceTone;
  count: number;
}

const TRUST_RULES: { key: string; label: string; tone: SourceTone; match: (s?: string | null) => boolean }[] = [
  { key: "poi", label: "高德 POI", tone: "verified", match: (s) => !!s && s.includes("高德POI") },
  { key: "guide", label: "本地攻略", tone: "verified", match: (s) => !!s && s.includes("本地攻略") },
  { key: "route", label: "高德路线估算", tone: "verified", match: (s) => !!s && s.includes("高德路线") },
  { key: "search", label: "联网搜索", tone: "verified", match: (s) => !!s && s.includes("搜索") },
  { key: "llm", label: "模型建议（需核实）", tone: "model", match: (s) => !!s && (s.includes("LLM") || s.includes("模型") || s.includes("估算")) },
  { key: "other", label: "未标注来源", tone: "unknown", match: (s) => !s || !s.trim() },
];

const trustStats = computed<{ items: TrustBucket[]; resolved: number; spotTotal: number } | null>(() => {
  const it = itinerary.value;
  if (!it) return null;
  const counts = new Map<string, number>(TRUST_RULES.map((r) => [r.key, 0]));
  const bump = (src?: string | null) => {
    const hit = TRUST_RULES.find((r) => r.match(src));
    const key = hit ? hit.key : "other";
    counts.set(key, (counts.get(key) || 0) + 1);
  };
  let resolved = 0;
  let spotTotal = 0;
  for (const day of it.days || []) {
    for (const sp of day.spots || []) {
      spotTotal++;
      if (sp.address && !sp.address.includes("待核实")) resolved++;
      bump(sp.source);
    }
    for (const m of day.meals || []) bump(m.source);
    for (const t of day.transport || []) bump(t.source);
  }
  const items = TRUST_RULES.map((r) => ({ ...r, count: counts.get(r.key) || 0 }))
    .filter((r) => r.count > 0)
    .sort((a, b) => b.count - a.count);
  return { items, resolved, spotTotal };
});

const dayBudgetItems = computed(() => {
  if (!itinerary.value) return [];
  return itinerary.value.days.map((day) => {
    // 空数组防御：校验层在"删掉全部交通段"等场景下会把该键置 null，缺键统一兜底为 []
    const tickets = (day.spots ?? []).reduce((s, sp) => s + (sp.estimated_cost ?? 0), 0);
    const meals = (day.meals ?? []).reduce((s, m) => s + (m.estimated_cost ?? 0), 0);
    const transport = (day.transport ?? []).reduce((s, t) => s + (t.estimated_cost ?? 0), 0);
    const hotel = day.hotel?.estimated_cost ?? 0;
    return { key: day.day_index, title: `第${day.day_index}天`, subtitle: day.theme || "", tickets, meals, transport, hotel, total: tickets + meals + transport + hotel };
  });
});

const mapPoints = computed(() => {
  if (!itinerary.value) return [];
  return itinerary.value.days.flatMap((day) =>
    (day.spots ?? []).map((spot) => ({
      key: `${day.day_index}-${spot.name}`,
      dayIndex: day.day_index,
      date: day.date || "待定",
      theme: day.theme || "",
      name: spot.name,
      address: spot.address || spot.location || "待补充",
      latitude: spot.latitude,
      longitude: spot.longitude,
      poiId: spot.poi_id,
      poiType: spot.poi_type ?? null,
      imageUrl: spot.image_url,
      description: spot.description || "暂无说明",
      personalNote: spot.personal_note ?? null,
    }))
  );
});

/* ========== 每日完整时间轴（P0：完整展示已有数据，见 RESULT_PAGE_OPTIMIZATION_PLAN §5.1/§5.2/§9-P0） ========== */
type SourceTone = "verified" | "system" | "model" | "unknown";

/** 数据来源 → 可信度色调：已验证事实 / 系统计算 / 模型建议（需核实）/ 其他 */
function sourceTone(source?: string | null): SourceTone {
  const s = source || "";
  if (s.includes("高德POI") || s.includes("本地攻略") || s.includes("高德路线") || s.includes("天气")) return "verified";
  if (s.includes("系统")) return "system";
  if (s.includes("LLM") || s.includes("模型")) return "model";
  return "unknown";
}

interface TimelineEntry {
  kind: string;
  tag: string;
  title: string;
  timeLabel: string;
  sub: string | null;
  desc: string | null;
  fee: string | null;
  source: string | null;
  tone: SourceTone;
  personalNote: string | null;
}

/** 解析 "HH:MM" 为当日分钟数；无法解析返回 null（缺字段不编造时间） */
function parseClock(text?: string | null): number | null {
  if (!text) return null;
  const m = /^(\d{1,2}):(\d{2})/.exec(text.trim());
  if (!m) return null;
  const h = Number(m[1]);
  const mi = Number(m[2]);
  return h >= 0 && h <= 23 && mi >= 0 && mi <= 59 ? h * 60 + mi : null;
}

/** 景点名相关（相等或互相包含），用于把交通段锚定到相邻景点 */
function relateName(a?: string | null, b?: string | null): boolean {
  const x = (a || "").trim();
  const y = (b || "").trim();
  if (!x || !y || x.length < 2 || y.length < 2) return false;
  return x === y || x.includes(y) || y.includes(x);
}

/** 餐饮时段排序：早 → 午/中 → 晚 → 夜 → 其他 */
function mealOrder(mealType?: string): number {
  const t = mealType || "";
  if (t.includes("早")) return 0;
  if (t.includes("午") || t.includes("中")) return 1;
  if (t.includes("晚")) return 2;
  if (t.includes("夜")) return 3;
  return 4;
}

/** 景点时间标签：有起止给 "HH:MM-HH:MM"，只有起点给 "HH:MM"，否则空串 */
function spotTimeLabel(spot: SpotItem): string {
  const s = parseClock(spot.start_time);
  const e = parseClock(spot.end_time);
  if (s == null) return "";
  return e == null ? (spot.start_time || "").trim() : `${(spot.start_time || "").trim()}-${(spot.end_time || "").trim()}`;
}

/** 交通条目文案："出发地 → 目的地 · 距离 · 耗时" */
function transportText(t: TransportItem): string {
  const parts = [t.from_place || "", t.to_place || ""].filter(Boolean);
  const route = parts.length ? parts.join(" → ") : t.duration || "交通";
  const km = t.distance_km != null ? ` ${t.distance_km.toFixed(1)} km` : "";
  const min = t.estimated_minutes != null ? ` · ${t.estimated_minutes} 分钟` : t.duration ? ` · ${t.duration}` : "";
  return `${route}${km}${min}`;
}

interface DayTimeline {
  dayIndex: number;
  date: string | null;
  theme: string;
  timeline: TimelineEntry[];
  stats: {
    spots: number;
    meals: number;
    transports: number;
    totalKm: number | null;
    totalMinutes: number | null;
    visitHours: number | null;
    cost: number;
  };
}

/** 构建单日完整时间轴（编排全部由代码完成，LLM 不参与；无时间/无锚内容不编造时间） */
function buildDayTimeline(day: DayPlan): DayTimeline {
  // 空数组防御：个别历史行程 JSON 可能缺某类键，统一兜底为显式类型空数组
  const spots: SpotItem[] = day.spots ?? [];
  const meals: MealItem[] = day.meals ?? [];
  const transports: TransportItem[] = day.transport ?? [];
  const notes: string[] = day.notes ?? [];

  const stats = {
    spots: spots.length,
    meals: meals.length,
    transports: transports.length,
    totalKm: null as number | null,
    totalMinutes: null as number | null,
    visitHours: null as number | null,
    cost: 0,
  };
  const fee = (n?: number | null) => (n == null ? null : `¥${n.toFixed(0)}`);

  // —— 景点：有开始时间的按时段升序；无时间的进"其他安排"（不编造时间）
  const timed = spots
    .filter((s) => parseClock(s.start_time) != null)
    .sort((a, b) => (parseClock(a.start_time) ?? 0) - (parseClock(b.start_time) ?? 0));
  const untimed = spots.filter((s) => parseClock(s.start_time) == null);

  // —— 交通锚定：每条段就近归位（起点优先）：from 命中活动 → 紧随其后；to 命中 → 排其前。
  //    活动 = 有时间的景点 + 餐。此前只认景点，导致"酒店→午餐 / 晚餐→酒店"这类以餐厅为端点的段
  //    两端都不是景点，被丢进衔接段沉底（实测三亚 D2："酒店→人人捞"排到晚餐之后，时间倒流）。
  type Anchor = {
    minute: number;
    kind: "spot" | "meal";
    pre: TransportItem[];
    post: TransportItem[];
    spot?: SpotItem;
    meal?: MealItem;
  };
  const anchors: Anchor[] = timed.map((s) => ({
    minute: parseClock(s.start_time) ?? 0,
    kind: "spot",
    pre: [],
    post: [],
    spot: s,
  }));
  for (const m of meals.filter((m) => parseClock(m.start_time) != null)) {
    anchors.push({ minute: parseClock(m.start_time) ?? 0, kind: "meal", pre: [], post: [], meal: m });
  }
  const anchorFor = (name?: string | null): Anchor | undefined => {
    if (!name) return undefined;
    return anchors.find((a) => relateName(name, a.spot?.name ?? a.meal?.name ?? ""));
  };
  const hotelName = day.hotel?.name;
  const headTransports: TransportItem[] = [];
  const tailTransports: TransportItem[] = [];
  const looseTransports: TransportItem[] = [];
  for (const t of transports) {
    const fromA = anchorFor(t.from_place);
    if (fromA) {
      fromA.post.push(t);
      continue;
    }
    const toA = anchorFor(t.to_place);
    if (toA) {
      toA.pre.push(t);
      continue;
    }
    if (hotelName && relateName(t.from_place, hotelName)) {
      headTransports.push(t);
      continue;
    }
    if (hotelName && relateName(t.to_place, hotelName)) {
      tailTransports.push(t);
      continue;
    }
    looseTransports.push(t);
  }
  // 景点 + 餐按分钟交错排序（同分钟景点在前；现代 JS sort 稳定）
  anchors.sort((a, b) => a.minute - b.minute || (a.kind === "spot" ? -1 : 1));
  const untimedMeals: MealItem[] = meals
    .filter((m) => parseClock(m.start_time) == null)
    .sort((a, b) => mealOrder(a.meal_type) - mealOrder(b.meal_type));

  const timeline: TimelineEntry[] = [];
  const mealEntry = (m: MealItem): TimelineEntry => ({
    kind: "meal",
    tag: m.meal_type || "餐饮",
    title: m.name,
    timeLabel: (m.start_time || "").trim(),
    sub: m.notes || null,
    desc: null,
    fee: fee(m.estimated_cost),
    source: m.source ?? null,
    tone: sourceTone(m.source),
    personalNote: m.personal_note ?? null,
  });
  const transportEntry = (t: TransportItem): TimelineEntry => ({
    kind: "transport",
    tag: t.mode || "交通",
    title: transportText(t),
    timeLabel: "",
    sub: null,
    desc: null,
    fee: fee(t.estimated_cost),
    source: t.source ?? null,
    tone: sourceTone(t.source),
    personalNote: null,
  });
  // 酒店出发段（排最前）
  for (const t of headTransports) {
    timeline.push(transportEntry(t));
  }
  // 景点 + 餐按分钟交错，各自前后挂交通段
  for (const a of anchors) {
    for (const t of a.pre) {
      timeline.push(transportEntry(t));
    }
    if (a.kind === "meal") {
      timeline.push(mealEntry(a.meal!));
    } else {
      const sp = a.spot!;
      timeline.push({
        kind: "spot",
        tag: "景点",
        title: sp.name,
        timeLabel: spotTimeLabel(sp),
        sub: sp.address || sp.location || null,
        desc: sp.description || null,
        fee: fee(sp.estimated_cost),
        source: sp.source ?? null,
        tone: sourceTone(sp.source),
        personalNote: sp.personal_note ?? null,
      });
    }
    for (const t of a.post) {
      timeline.push(transportEntry(t));
    }
  }
  // —— 无开始时间的餐饮（旧数据/LLM 未给时间）：不编造钟点，按餐次顺序完整展示
  for (const m of untimedMeals) {
    timeline.push(mealEntry(m));
  }
  // —— 游览时长：仅当全部有时段景点都带结束时间才统计（缺字段不估算）
  if (timed.length > 0 && timed.every((s) => parseClock(s.end_time) != null)) {
    const minutes = timed.reduce((sum, s) => sum + ((parseClock(s.end_time) ?? 0) - (parseClock(s.start_time) ?? 0)), 0);
    stats.visitHours = Math.max(0, Math.round((minutes / 60) * 10) / 10);
  }
  // —— 交通总量（确定性累加，字段缺失则不展示对应项）
  // 只在「每一段都带该字段」时才给合计：否则累加出来的是"部分段之和"，却标成"合计"更误导
  // （实测三亚第 1 天 5 段里只有机场段有里程，页面却写「合计 11.2 km」）。
  // 口径与上面的游览时长一致：字段不齐宁可不展示，也不估算。
  const kms = transports.map((t) => t.distance_km).filter((v): v is number => v != null);
  if (transports.length && kms.length === transports.length) {
    stats.totalKm = Math.round(kms.reduce((a, b) => a + b, 0) * 10) / 10;
  }
  const mins = transports.map((t) => t.estimated_minutes).filter((v): v is number => v != null);
  if (transports.length && mins.length === transports.length) {
    stats.totalMinutes = mins.reduce((a, b) => a + b, 0);
  }
  // —— 返回酒店段 / 未锚定衔接段（保持原始顺序，完整展示）
  for (const t of tailTransports) {
    timeline.push(transportEntry(t));
  }
  for (const t of looseTransports) {
    timeline.push(transportEntry(t));
  }
  // —— 住宿
  const hotel = day.hotel;
  if (hotel && hotel.name) {
    timeline.push({ kind: "hotel", tag: hotel.level || "住宿", title: hotel.name, timeLabel: "", sub: hotel.address || hotel.location || null, desc: null, fee: fee(hotel.estimated_cost), source: null, tone: "system", personalNote: null });
  }
  // —— 当天备注：全部展示（不再只取最后一条）
  for (const note of notes) {
    if (!note || !note.trim()) continue;
    timeline.push({ kind: "note", tag: "备注", title: note, timeLabel: "", sub: null, desc: null, fee: null, source: null, tone: "unknown", personalNote: null });
  }
  // —— 无开始时间的景点 → 其他安排
  for (const sp of untimed) {
    timeline.push({
      kind: "spot",
      tag: "其他安排",
      title: sp.name,
      timeLabel: "",
      sub: sp.address || sp.location || null,
      desc: sp.description || null,
      fee: fee(sp.estimated_cost),
      source: sp.source ?? null,
      tone: sourceTone(sp.source),
      personalNote: sp.personal_note ?? null,
    });
  }

  // —— 当日花费（与按天预算口径一致：景点+餐饮+交通+住宿）
  const dayCost =
    spots.reduce((s, x) => s + (x.estimated_cost ?? 0), 0) +
    meals.reduce((s, x) => s + (x.estimated_cost ?? 0), 0) +
    transports.reduce((s, x) => s + (x.estimated_cost ?? 0), 0) +
    (day.hotel?.estimated_cost ?? 0);
  stats.cost = dayCost;

  return { dayIndex: day.day_index, date: day.date ?? null, theme: day.theme || "", timeline, stats };
}

/** 每天的时间轴（含确定性概览统计） */
const dayTimelines = computed(() => (itinerary.value ? itinerary.value.days.map(buildDayTimeline) : []));

/** 点位反馈目标（与 mapPoints 元素同构的轻量类型，避免整对象传递） */
interface FeedbackPoint {
  key: string;
  name: string;
  poiId?: string | null;
  poiType?: string | null;
}

/* ---------- 个性化规划摘要（P2：PLAN §5.5，后端确定性结构化字段；无画像时为空整块隐藏） ---------- */
const personalizationSummary = computed<PersonalizationSummary | null>(
  () => itinerary.value?.personalization_summary ?? null
);

/** 证据等级 → 展示文案（与后端 PersonalizationSummary.evidenceLevel 对齐） */
const SUMMARY_EVIDENCE_TEXT: Record<string, string> = {
  PROFILE_AND_BEHAVIOR: "画像来自问卷 + 历史行为反馈，可信度高",
  QUESTIONNAIRE: "画像来自你的主动选择",
  HISTORY_INFER_ONLY: "画像由历史行程推断",
  NONE: "",
};

/* ---------- 为什么没有推荐某些内容（P2：PLAN §5.6/§8.3；候选被过滤/降级且有依据时才展示） ---------- */
const filteredCandidates = computed<FilteredCandidate[] | null>(
  () => itinerary.value?.filtered_candidates ?? null
);

/** 证据来源 → 徽标文案与色调（与后端 evidence 字段对齐） */
const FILTERED_EVIDENCE_TEXT: Record<string, { text: string; cls: string }> = {
  USER_BEHAVIOR: { text: "你的不感兴趣反馈", cls: "fc-ev--hard" },
  HISTORY_TRIP: { text: "历史行程", cls: "fc-ev--soft" },
  WEATHER_API: { text: "天气预报", cls: "fc-ev--weather" },
};

/** 约束级别 → 徽标文案 */
const FILTERED_SEVERITY_TEXT: Record<string, string> = {
  HARD: "已排除",
  SOFT: "已降级",
};

/* ---------- 行为反馈（个性化阶段二）：点位收藏/不感兴趣 ---------- */
const actingKey = ref("");
/** key → SAVE | DISLIKE：同一次行程内已反馈过的景点置灰，避免重复上报 */
const spotMarks = ref<Record<string, "SAVE" | "DISLIKE">>({});

/** 负反馈原因选项（PLAN §2.2 问题三：TYPE/ITEM 决定是否把负面信号泛化到画像标签） */
const DISLIKE_REASON_OPTIONS = [
  { value: "TYPE", label: "不想再看这类地点" },
  { value: "ITEM", label: "只是不喜欢这个具体地点" },
  { value: "DISTANCE", label: "距离/位置不合适" },
  { value: "CROWDED", label: "太拥挤" },
  { value: "PRICE", label: "价格偏高" },
  { value: "PACE", label: "不适合当前节奏" },
] as const;

/** 待选原因的"不感兴趣"点位 key（空 = 无待确认） */
const pendingDislikeKey = ref("");
const dislikeBusy = ref(false);

async function onSpotAction(point: FeedbackPoint, action: "SAVE" | "DISLIKE") {
  if (!itinerary.value || actingKey.value || spotMarks.value[point.key]) return;
  if (action === "DISLIKE") {
    // 负反馈先问原因：避免一次误点就把"不喜欢某个具体商场"泛化成"不喜欢购物/城市漫游/拍照"
    pendingDislikeKey.value = point.key;
    return;
  }
  actingKey.value = point.key;
  try {
    await reportBehavior({
      itemType: "SPOT",
      itemId: point.poiId ?? null,
      itemName: point.name,
      poiType: point.poiType ?? null,
      actionType: action,
      tripId: itinerary.value.trip_id ?? null,
    });
    spotMarks.value = { ...spotMarks.value, [point.key]: action };
    message.success(`已收藏「${point.name}」，之后会优先安排这类景点`);
  } catch {
    message.error("反馈提交失败，请稍后重试。");
  } finally {
    actingKey.value = "";
  }
}

/** 提交不感兴趣 + 具体原因（reason=TYPE/ITEM 等；后端据此决定是否泛化降权） */
async function submitDislike(point: FeedbackPoint, reason: string) {
  const it = itinerary.value;
  if (!it || dislikeBusy.value) return;
  dislikeBusy.value = true;
  try {
    await reportBehavior({
      itemType: "SPOT",
      itemId: point.poiId ?? null,
      itemName: point.name,
      poiType: point.poiType ?? null,
      actionType: "DISLIKE",
      reason,
      tripId: it.trip_id ?? null,
    });
    spotMarks.value = { ...spotMarks.value, [point.key]: "DISLIKE" };
    pendingDislikeKey.value = "";
    const tip =
      reason === "TYPE"
        ? `已记录「${point.name}」，之后会少安排这类景点`
        : reason === "ITEM"
          ? `已记录「${point.name}」本人，不影响你对这类地点的偏好`
          : `已记录「${point.name}」的反馈，下次规划会参考`;
    message.success(tip);
  } catch {
    message.error("反馈提交失败，请稍后重试。");
  } finally {
    dislikeBusy.value = false;
  }
}

function cancelDislike() {
  pendingDislikeKey.value = "";
}

/* ---------- 行为反馈（阶段二）：行程整体评分（低分可选原因） ---------- */
const RATE_ASPECT_OPTIONS = [
  { value: "pace", label: "节奏不合适" },
  { value: "food", label: "餐饮不合口味" },
  { value: "hotel", label: "住宿不满意" },
  { value: "travel_style", label: "景点风格不符" },
  { value: "other", label: "其他" },
] as const;
const submittedRating = ref<number | null>(null);
const pendingRating = ref<number | null>(null);
const pickedAspects = ref<string[]>([]);
const ratingBusy = ref(false);

function toggleAspect(value: string) {
  const list = pickedAspects.value;
  pickedAspects.value = list.includes(value) ? list.filter((v) => v !== value) : [...list, value];
}

function pickRating(n: number) {
  if (ratingBusy.value) return;
  if (n <= 2) {
    pendingRating.value = n; // 低分先问原因
    pickedAspects.value = [];
  } else {
    void submitRating(n, []);
  }
}

function confirmLowRating() {
  if (pendingRating.value != null) {
    void submitRating(pendingRating.value, pickedAspects.value);
  }
}

async function submitRating(rating: number, aspects: string[]) {
  const it = itinerary.value;
  if (!it || !it.trip_id) {
    message.error("缺少行程标识，无法评分。");
    return;
  }
  ratingBusy.value = true;
  try {
    await reportBehavior({
      itemType: "TRIP",
      itemName: `${it.destination}行程`,
      actionType: "RATE",
      rating,
      aspects,
      tripId: it.trip_id,
    });
    submittedRating.value = rating;
    pendingRating.value = null;
    pickedAspects.value = [];
    message.success(
      rating >= 4
        ? "谢谢好评！之后会继续按这个方向安排。"
        : rating === 3
          ? "收到，我们会继续优化。"
          : "谢谢反馈！已记住你不满意的方面，下次会调整。"
    );
  } catch {
    message.error("评分提交失败，请稍后重试。");
  } finally {
    ratingBusy.value = false;
  }
}

/* 切换行程（新生成/打开历史/回放）时重置本行程的反馈状态 */
watch(
  () => itinerary.value?.trip_id,
  () => {
    actingKey.value = "";
    spotMarks.value = {};
    pendingDislikeKey.value = "";
    dislikeBusy.value = false;
    submittedRating.value = null;
    pendingRating.value = null;
    pickedAspects.value = [];
  }
);

const technicalTipKeywords = ["LLM", "RAG", "LangChain", "Chroma", "演示", "测试", "规则", "模型", "源码"];
const rainWeatherKeywords = ["雨", "阵雨", "雷阵雨", "小雨", "中雨", "大雨"];
const sunnyTipKeywords = ["防晒", "太阳", "日照", "晒"];

const weatherText = computed(() => {
  if (!weather.value) return "";
  return weather.value.days.map((d) => `${d.day_weather || ""}${d.night_weather || ""}`).join(" ");
});

const hasRainyWeather = computed(() => rainWeatherKeywords.some((k) => weatherText.value.includes(k)));

const displayTips = computed(() => {
  if (!itinerary.value) return [];
  const tips = itinerary.value.tips.map((t) => t.trim()).filter(Boolean).filter((t) => !technicalTipKeywords.some((k) => t.includes(k)));
  const weatherAware = hasRainyWeather.value ? tips.filter((t) => !sunnyTipKeywords.some((k) => t.includes(k))) : tips;
  if (hasRainyWeather.value) {
    weatherAware.push("天气可能有雨，建议随身带伞或轻便雨衣。");
    weatherAware.push("阴雨天路面湿滑，建议穿防滑鞋。");
  }
  return Array.from(new Set(weatherAware));
});

function buildVisibleItinerary(): Itinerary | null {
  if (!itinerary.value) return null;
  return { ...itinerary.value, tips: displayTips.value };
}

function markImageAsFailed(pointKey: string) {
  failedImageKeys.value = new Set([...failedImageKeys.value, pointKey]);
}

async function loadWeather() {
  if (!itinerary.value?.destination) { weather.value = null; return; }
  // 优先使用行程自带的生成时刻天气快照，与行程每日备注口径一致；缺失（旧行程）时回退实时拉取
  if (itinerary.value.weather && itinerary.value.weather.days?.length) {
    weather.value = itinerary.value.weather;
    return;
  }
  weatherLoading.value = true;
  weatherError.value = "";
  try {
    const firstDay = itinerary.value.days[0]?.date ?? undefined;
    const lastDay = itinerary.value.days[itinerary.value.days.length - 1]?.date ?? undefined;
    weather.value = await fetchWeatherForecast(
      itinerary.value.destination,
      firstDay,
      lastDay
    );
  }
  catch { weather.value = null; weatherError.value = "天气信息加载失败。"; }
  finally { weatherLoading.value = false; }
}

watch(() => itinerary.value?.destination, () => { void loadWeather(); }, { immediate: true });

async function openPdfExport() {
  const it = buildVisibleItinerary(); if (!it) return;
  exportingPdf.value = true;
  try {
    // 先同步当前行程（含天气提示过滤后的 tips），再以 blob 方式下载（axios 自动带 token）
    const saved = await saveTrip(it, agentTrace.value);
    adoptServerTripId(saved?.trip_id);
    await exportPdf(saved?.trip_id || it.trip_id);
  } catch {
    message.error("导出 PDF 失败。");
  } finally {
    exportingPdf.value = false;
  }
}

async function openMarkdownExport() {
  const it = buildVisibleItinerary(); if (!it) return;
  exportingMarkdown.value = true;
  try {
    const saved = await saveTrip(it, agentTrace.value);
    adoptServerTripId(saved?.trip_id);
    await exportMarkdown(saved?.trip_id || it.trip_id);
  } catch {
    message.error("导出 Markdown 失败。");
  } finally {
    exportingMarkdown.value = false;
  }
}

/**
 * 采用服务端返回的 trip_id。
 * 服务端对 trip_id 有最终分配权（id 被占用时会改派唯一 id），若不回写，
 * 页面仍持旧 id，下一次保存/导出会再生成一条新记录。
 */
function adoptServerTripId(serverTripId?: string | null) {
  if (!serverTripId || !itinerary.value || itinerary.value.trip_id === serverTripId) return;
  itinerary.value.trip_id = serverTripId;
}

async function handleSave() {
  const it = buildVisibleItinerary(); if (!it) return;
  saving.value = true;
  try {
    const saved = await saveTrip(it, agentTrace.value);
    adoptServerTripId(saved?.trip_id);
    message.success("行程已保存。");
  }
  catch { message.error("保存行程失败。"); }
  finally { saving.value = false; }
}
</script>

<template>
  <section v-if="itinerary" class="result-page">
    <!-- 侧边栏 -->
    <aside class="sidebar">
      <div class="sidebar__section">
        <div class="sidebar__label">操作</div>
        <button class="ios-btn ios-btn--text" @click="goPlan">← 返回规划</button>
        <button class="ios-btn ios-btn--text" :disabled="saving" @click="handleSave">{{ saving ? "保存中..." : "保存行程" }}</button>
        <button class="ios-btn ios-btn--text" @click="goHistory">历史列表</button>
      </div>
      <div class="sidebar__divider" />
      <div class="sidebar__section">
        <div class="sidebar__label">导出</div>
        <button class="ios-btn ios-btn--text" :disabled="exportingPdf" @click="openPdfExport">{{ exportingPdf ? "准备中..." : "导出 PDF" }}</button>
        <button class="ios-btn ios-btn--text" :disabled="exportingMarkdown" @click="openMarkdownExport">{{ exportingMarkdown ? "准备中..." : "导出 Markdown" }}</button>
      </div>
    </aside>

    <!-- 主内容 -->
    <div class="result-content">
      <!-- 行程概览 -->
      <div class="ios-card">
        <h2 class="ios-card__title">{{ itinerary.destination }}旅行计划</h2>
        <div class="ios-info"><span class="ios-info__label">行程 ID</span><span>{{ itinerary.trip_id }}</span></div>
        <div class="ios-info"><span class="ios-info__label">日期</span><span>{{ itinerary.days[0]?.date || "待定" }} 至 {{ itinerary.days[itinerary.days.length - 1]?.date || "待定" }}</span></div>
        <div v-if="tokenSummary" class="ios-info"><span class="ios-info__label">本次 Token 消耗</span><span>输入 {{ tokenSummary.prompt }} · 输出 {{ tokenSummary.completion }} · 共计 {{ tokenSummary.total }}</span></div>
        <p class="ios-summary">{{ itinerary.summary }}</p>
        <div v-if="displayTips.length" class="ios-tips">
          <div class="ios-tips__title">旅行提示</div>
          <ul><li v-for="tip in displayTips" :key="tip">{{ tip }}</li></ul>
        </div>
      </div>

      <!-- 个性化规划摘要（P2：PLAN §5.5 —— 确定性结构化说明，不让 LLM 自由编这段） -->
      <div
        v-if="personalizationSummary && (personalizationSummary.matched_preferences?.length || personalizationSummary.applied_constraints?.length || personalizationSummary.novelty_note)"
        class="ios-card ios-card--full"
      >
        <div class="ios-card__header">🧩 本次规划结合了</div>
        <ul class="p-summary">
          <li v-for="p in personalizationSummary.matched_preferences" :key="`m-${p}`">✓ 你的<span class="p-summary__tag">{{ p }}</span>偏好</li>
          <li v-for="c in personalizationSummary.applied_constraints" :key="`c-${c}`">✓ {{ c }}</li>
          <li v-if="personalizationSummary.novelty_note" class="p-summary__novelty">↺ {{ personalizationSummary.novelty_note }}</li>
        </ul>
        <div v-if="personalizationSummary.evidence_level && SUMMARY_EVIDENCE_TEXT[personalizationSummary.evidence_level]" class="ios-muted p-summary__note">
          {{ SUMMARY_EVIDENCE_TEXT[personalizationSummary.evidence_level] }}
        </div>
      </div>

      <!-- 为什么没有推荐某些内容（P2：PLAN §5.6 —— 被过滤/降级的候选逐条解释，确定性来源） -->
      <div v-if="filteredCandidates && filteredCandidates.length" class="ios-card ios-card--full">
        <div class="ios-card__header">🚫 本次为什么没安排这些</div>
        <div class="ios-muted" style="margin: -2px 0 10px;">以下候选进入了候选池，但因你的偏好/历史记录被排除或降级（来源见每条徽标）</div>
        <ul class="filtered-list">
          <li v-for="(fc, idx) in filteredCandidates" :key="`fc-${idx}-${fc.name}`" class="filtered-item">
            <div class="filtered-item__head">
              <span class="filtered-item__name">{{ fc.name }}</span>
              <span v-if="fc.bucket" class="ios-badge">{{ fc.bucket }}</span>
              <span v-if="fc.severity && FILTERED_SEVERITY_TEXT[fc.severity]" class="ios-badge" :class="fc.severity === 'HARD' ? 'ios-badge--warn' : 'ios-badge--info'">
                {{ FILTERED_SEVERITY_TEXT[fc.severity] }}
              </span>
            </div>
            <div class="filtered-item__reason">{{ fc.reason }}</div>
            <div v-if="fc.evidence && FILTERED_EVIDENCE_TEXT[fc.evidence]" class="filtered-item__ev" :class="FILTERED_EVIDENCE_TEXT[fc.evidence].cls">
              📎 {{ FILTERED_EVIDENCE_TEXT[fc.evidence].text }}
            </div>
          </li>
        </ul>
      </div>

      <!-- Agent 推理轨迹 -->
      <div v-if="agentTrace && agentTrace.length > 0" class="ios-card ios-card--full">
        <div class="ios-card__header">
          <span>🤖 Agent 推理过程</span>
          <span class="ios-muted" style="font-weight: normal; margin-left: 8px;">共 {{ agentTrace.length }} 步</span>
          <button class="ios-btn ios-btn--primary ios-btn--sm" style="margin-left: auto;" @click="playReplay">
            🎬 动画回放
          </button>
        </div>
        <div class="agent-trace">
          <div v-for="step in agentTrace" :key="step.step" class="agent-step">
            <div class="agent-step__header">
              <span class="agent-step__num">Step {{ step.step }}</span>
              <span v-if="step.action" class="agent-step__action">{{ step.action }}</span>
            </div>
            <div class="agent-step__thought">💭 {{ step.thought }}</div>
            <div v-if="step.tool_calls && step.tool_calls.length > 0" class="agent-step__tools">
              <span v-for="(tool, idx) in step.tool_calls" :key="idx" class="agent-tool">
                🔧 {{ tool.tool || 'tool' }}
              </span>
            </div>
            <div v-if="step.observation" class="agent-step__obs">
              <span class="agent-step__obs-label">观察：</span>{{ step.observation }}
            </div>
          </div>
        </div>
      </div>

      <!-- 数据来源说明 -->
      <div v-if="itinerary?.source_notes && itinerary.source_notes.length > 0" class="ios-card ios-card--full">
        <div class="ios-card__header">📊 数据来源说明</div>
        <div class="ios-source-notes">
          <div v-for="(note, idx) in itinerary.source_notes" :key="idx" class="ios-source-note">
            {{ note }}
          </div>
        </div>
      </div>

      <!-- 预算 -->
      <div class="ios-card">
        <div class="ios-card__header">预算明细</div>
        <div class="ios-budget-grid">
          <div v-for="item in budgetItems" :key="item.label" class="ios-budget-item">
            <span class="ios-budget-item__label">{{ item.label }}</span>
            <span class="ios-budget-item__value">{{ item.value }}</span>
          </div>
        </div>
        <div class="ios-budget-total">
          <span>预估总费用</span>
          <strong>¥{{ itinerary.estimated_budget.toFixed(0) }}</strong>
        </div>
        <!-- 预算健康度（P1 确定性计算，PLAN §5.3；行程含请求预算快照时才展示） -->
        <div v-if="budgetHealth" class="ios-budget-health">
          <div class="ios-budget-health__bar">
            <span
              class="ios-budget-health__fill"
              :class="{ 'is-over': budgetHealth.over }"
              :style="{ width: Math.min(budgetHealth.pct, 100) + '%' }"
            />
          </div>
          <div class="ios-budget-health__row">
            <span>预算使用率 <b>{{ budgetHealth.pct }}%</b></span>
            <span>剩余预算 <b>¥{{ budgetHealth.remain.toFixed(0) }}</b></span>
            <span>人均每日 <b>¥{{ budgetHealth.perDay.toFixed(0) }}</b></span>
            <span class="ios-budget-health__status" :class="{ 'is-over': budgetHealth.over }">{{ budgetHealth.statusText }}</span>
          </div>
        </div>
      </div>

      <!-- 数据可信度（P1 确定性统计，PLAN §5.4/§6：绿=已验证事实 · 蓝=系统计算 · 橙=模型建议 · 灰=未标注） -->
      <div v-if="trustStats && trustStats.items.length" class="ios-card ios-card--full">
        <div class="ios-card__header">🛡️ 数据可信度</div>
        <div class="ios-trust">
          <span v-for="s in trustStats.items" :key="s.key" class="ios-src ios-src--lg" :class="`ios-src--${s.tone}`">{{ s.label }} {{ s.count }}</span>
          <span v-if="trustStats.spotTotal" class="ios-trust__addr">📍 地址已校验 {{ trustStats.resolved }}/{{ trustStats.spotTotal }} 处景点</span>
        </div>
        <div class="ios-trust__legend">图例：绿 = 已验证事实（高德 POI / 本地攻略 / 天气） · 蓝 = 系统计算 · 橙 = 模型建议（需核实） · 灰 = 未标注来源</div>
      </div>

      <!-- 地图 -->
      <div class="ios-card ios-card--map">
        <div class="ios-card__header">景点地图</div>
        <AmapTripMap :points="mapPoints" />
      </div>

      <!-- 天气 -->
      <div class="ios-card">
        <div class="ios-card__header">天气信息</div>
        <div v-if="weatherLoading" class="ios-empty">正在加载...</div>
        <div v-else-if="weatherError" class="ios-empty">{{ weatherError }}</div>
        <div v-else-if="weather" class="ios-weather-grid">
          <div v-for="day in weather.days" :key="`${day.date}-${day.week}`" class="ios-weather-item">
            <div class="ios-weather-item__date">{{ formatWeatherDate(day.date, day.week) }}</div>
            <div class="ios-weather-item__temp">{{ day.day_temp || "-" }}° / {{ day.night_temp || "-" }}°</div>
            <div class="ios-weather-item__desc">{{ day.day_weather || "未知" }} / {{ day.night_weather || "未知" }}</div>
          </div>
        </div>
        <div v-else class="ios-empty">暂无天气信息</div>
      </div>

      <!-- 按天花费 -->
      <div class="ios-card ios-card--full">
        <div class="ios-card__header">按天花费</div>
        <div class="ios-day-budget-grid">
          <div v-for="item in dayBudgetItems" :key="item.key" class="ios-day-budget">
            <div class="ios-day-budget__head"><span>{{ item.title }}</span><span>{{ item.subtitle }}</span></div>
            <div class="ios-day-budget__body">
              <div class="ios-row-between"><span>门票</span><span>¥{{ item.tickets.toFixed(0) }}</span></div>
              <div class="ios-row-between"><span>餐饮</span><span>¥{{ item.meals.toFixed(0) }}</span></div>
              <div class="ios-row-between"><span>交通</span><span>¥{{ item.transport.toFixed(0) }}</span></div>
              <div class="ios-row-between"><span>住宿</span><span>¥{{ item.hotel.toFixed(0) }}</span></div>
              <div class="ios-row-between ios-row-between--bold"><span>当日合计</span><span>¥{{ item.total.toFixed(0) }}</span></div>
            </div>
          </div>
        </div>
      </div>

      <!-- 地图点位明细 -->
      <div class="ios-card ios-card--full">
        <div class="ios-card__header">地图点位明细</div>
        <div class="ios-point-grid">
          <div v-for="point in mapPoints" :key="point.key" class="ios-point">
            <div class="ios-point__head"><span>第{{ point.dayIndex }}天 · {{ point.name }}</span><span>{{ formatShortDate(point.date) }}</span></div>
            <img
              v-if="point.imageUrl && !failedImageKeys.has(point.key)"
              class="ios-point__img"
              :src="point.imageUrl"
              :alt="`${point.name} 图片`"
              @error="markImageAsFailed(point.key)"
            />
            <div v-else class="ios-point__img ios-point__img--empty">暂无图片</div>
            <div class="ios-point__info"><span class="ios-muted">主题：</span>{{ point.theme }}</div>
            <div class="ios-point__info"><span class="ios-muted">地址：</span>{{ point.address }}</div>
            <div v-if="point.personalNote" class="ios-point__reason">🎯 {{ point.personalNote }}</div>
            <div class="ios-point__desc">{{ point.description }}</div>
            <div class="ios-point__actions">
              <button
                type="button"
                class="point-btn point-btn--save"
                :class="{ 'point-btn--done': spotMarks[point.key] === 'SAVE' }"
                :disabled="!!actingKey || spotMarks[point.key] !== undefined"
                @click="onSpotAction(point, 'SAVE')"
              >
                {{ spotMarks[point.key] === 'SAVE' ? "✓ 已收藏" : "♡ 收藏" }}
              </button>
              <button
                type="button"
                class="point-btn point-btn--dislike"
                :class="{ 'point-btn--done-dislike': spotMarks[point.key] === 'DISLIKE' }"
                :disabled="!!actingKey || spotMarks[point.key] !== undefined"
                @click="onSpotAction(point, 'DISLIKE')"
              >
                {{ spotMarks[point.key] === 'DISLIKE' ? "✓ 已忽略" : "✕ 不感兴趣" }}
              </button>
            </div>
            <!-- 不感兴趣 → 先选原因（负反馈粒度：只有"这类地点"才泛化到画像标签，PLAN §2.2 问题三） -->
            <div v-if="pendingDislikeKey === point.key" class="dislike-reasons">
              <div class="dislike-reasons__title">为什么对「{{ point.name }}」不感兴趣？</div>
              <div class="dislike-reasons__chips">
                <button
                  v-for="opt in DISLIKE_REASON_OPTIONS"
                  :key="opt.value"
                  type="button"
                  class="dislike-chip"
                  :disabled="dislikeBusy"
                  @click="submitDislike(point, opt.value)"
                >{{ opt.label }}</button>
              </div>
              <div class="dislike-reasons__actions">
                <button type="button" class="ios-btn ios-btn--sm ios-btn--text" :disabled="dislikeBusy" @click="cancelDislike">取消</button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 每日行程（完整时间轴：全部景点/交通/餐饮/住宿/备注，P0，见 RESULT_PAGE_OPTIMIZATION_PLAN §5.1/§5.2） -->
      <div class="ios-card ios-card--full">
        <div class="ios-card__header">每日行程</div>
        <div class="ios-day-list">
          <details v-for="d in dayTimelines" :key="d.dayIndex" class="ios-day" :open="d.dayIndex === 1">
            <summary class="ios-day__head">
              <span>第{{ d.dayIndex }}天 · {{ d.theme || "" }}</span>
              <span class="ios-muted">{{ formatShortDate(d.date) }}</span>
            </summary>
            <div class="ios-day__body">
              <!-- 每日概览统计（确定性计算，PLAN §5.2；缺时间字段不估算游览时长） -->
              <div v-if="d.stats.spots || d.stats.meals || d.stats.transports" class="ios-day__stats">
                <span>📍 景点 {{ d.stats.spots }}</span>
                <span>🍽 餐饮 {{ d.stats.meals }}</span>
                <span>🚌 交通 {{ d.stats.transports }} 段</span>
                <span v-if="d.stats.totalKm != null">· 合计 {{ d.stats.totalKm.toFixed(1) }} km</span>
                <span v-if="d.stats.totalMinutes != null">/ 约 {{ d.stats.totalMinutes }} 分钟</span>
                <span v-if="d.stats.visitHours != null">· 游览约 {{ d.stats.visitHours }} 小时</span>
                <span>· 当日 ¥{{ d.stats.cost.toFixed(0) }}</span>
              </div>

              <!-- 完整时间轴 -->
              <div v-if="d.timeline.length" class="ios-tl">
                <div v-for="(e, i) in d.timeline" :key="i" class="ios-tl__row">
                  <div class="ios-tl__time">{{ e.timeLabel }}</div>
                  <div class="ios-tl__rail">
                    <span class="ios-tl__dot" :class="`ios-tl__dot--${e.kind}`" />
                  </div>
                  <div class="ios-tl__card" :class="`ios-tl__card--${e.kind}`">
                    <div class="ios-tl__head">
                      <span class="ios-tl__tag">{{ e.tag }}</span>
                      <span class="ios-tl__title">{{ e.title }}</span>
                      <span v-if="e.fee" class="ios-tl__fee">{{ e.fee }}</span>
                      <span v-if="e.source" class="ios-src" :class="`ios-src--${e.tone}`">{{ e.source }}</span>
                    </div>
                    <div v-if="e.sub" class="ios-tl__sub">{{ e.sub }}</div>
                    <div v-if="e.personalNote" class="ios-tl__reason">🎯 {{ e.personalNote }}</div>
                    <div v-if="e.desc" class="ios-tl__desc">{{ e.desc }}</div>
                  </div>
                </div>
              </div>
              <div v-else class="ios-empty" style="text-align: left">当天暂无具体安排</div>
            </div>
          </details>
        </div>
      </div>

      <!-- 行程评分（行为反馈，个性化阶段二） -->
      <div class="ios-card ios-card--full">
        <div class="ios-card__header">✍️ 这次行程你还满意吗？</div>
        <div v-if="submittedRating" class="rate-done ios-muted">
          已收到你的 {{ submittedRating }} 星评价 —— 反馈会更新我的偏好理解，可在「偏好」页查看变化。
        </div>
        <template v-else>
          <div class="rate-stars">
            <span class="ios-muted">点击星星评分：</span>
            <button
              v-for="n in 5"
              :key="n"
              type="button"
              class="rate-star"
              :class="{ 'rate-star--active': pendingRating !== null && n <= pendingRating }"
              :disabled="ratingBusy"
              @click="pickRating(n)"
            >★</button>
          </div>
          <div v-if="pendingRating !== null && pendingRating <= 2" class="rate-aspects">
            <div class="rate-aspects__title">哪些方面不满意？（可多选，帮助我们改进）</div>
            <div class="rate-aspects__chips">
              <button
                v-for="opt in RATE_ASPECT_OPTIONS"
                :key="opt.value"
                type="button"
                :class="['rate-chip', { 'rate-chip--active': pickedAspects.includes(opt.value) }]"
                @click="toggleAspect(opt.value)"
              >{{ opt.label }}</button>
            </div>
            <div class="rate-aspects__actions">
              <button class="ios-btn ios-btn--primary ios-btn--sm" :disabled="ratingBusy" @click="confirmLowRating">
                提交反馈
              </button>
              <button class="ios-btn ios-btn--sm" :disabled="ratingBusy" @click="pendingRating = null">
                取消
              </button>
            </div>
          </div>
        </template>
      </div>
    </div>
  </section>

  <section v-else class="empty-state">
    <div class="ios-card" style="text-align:center; max-width:480px; margin:80px auto;">
      <h2 style="margin:0 0 12px;">还没有生成结果</h2>
      <p class="ios-muted" style="margin:0 0 20px;">先回到规划页生成一条行程。</p>
      <button class="ios-btn ios-btn--primary" @click="goPlan">返回规划页</button>
    </div>
  </section>
</template>

<style scoped>
.result-page {
  display: grid;
  grid-template-columns: 180px 1fr;
  gap: 16px;
}

/* 侧边栏 */
.sidebar {
  align-self: start;
  position: sticky;
  top: 76px;
  padding: 16px;
  border-radius: 12px;
  background: var(--surface-white);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.sidebar__section {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.sidebar__label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  padding: 4px 8px;
  margin-bottom: 4px;
}

.sidebar__divider {
  height: 0.5px;
  background: rgba(0, 0, 0, 0.06);
  margin: 8px 0;
}

/* iOS 按钮 */
.ios-btn {
  border: none;
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
  text-align: left;
}

.ios-btn:active { transform: scale(0.97); }
.ios-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.ios-btn--text {
  background: transparent;
  color: var(--brand-teal);
}

.ios-btn--primary {
  background: var(--brand-coral);
  color: var(--surface-white);
}

.ios-btn--sm {
  padding: 8px 16px;
  font-size: 13px;
}

/* 主内容 */
.result-content {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
  min-width: 0;
}

/* iOS 卡片 */
.ios-card {
  padding: 20px;
  border-radius: 12px;
  background: var(--surface-white);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.ios-card--full { grid-column: 1 / -1; }
.ios-card--map { min-height: 320px; }

.ios-card__title {
  margin: 0 0 16px;
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
}

.ios-card__header {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 14px;
  padding-bottom: 10px;
  border-bottom: 0.5px solid rgba(0, 0, 0, 0.06);
}

/* 信息行 */
.ios-info {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 0.5px solid rgba(0, 0, 0, 0.04);
  font-size: 14px;
  color: var(--text-secondary);
}

.ios-info__label { color: var(--text-muted); }

.ios-summary {
  margin: 14px 0 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--text-secondary);
}

.ios-muted { color: var(--text-muted); font-size: 13px; }

/* 旅行提示 */
.ios-tips {
  margin-top: 16px;
  padding: 14px 16px;
  border-radius: 10px;
  background: rgba(23, 33, 31, 0.05);
}

.ios-tips__title { font-size: 13px; font-weight: 600; color: var(--text-secondary); margin-bottom: 8px; }
.ios-tips ul { margin: 0; padding-left: 18px; font-size: 13px; color: var(--text-secondary); line-height: 1.8; }

/* 预算 */
.ios-budget-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}

.ios-budget-item {
  display: flex;
  justify-content: space-between;
  padding: 10px 12px;
  border-radius: 8px;
  background: rgba(23, 33, 31, 0.05);
}

.ios-budget-item__label { font-size: 13px; color: var(--text-muted); }
.ios-budget-item__value { font-size: 15px; font-weight: 600; color: var(--brand-teal); }

.ios-budget-total {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
  padding: 14px 16px;
  border-radius: 10px;
  background: var(--brand-teal);
  color: var(--surface-white);
  font-size: 15px;
}

.ios-budget-total strong { font-size: 22px; }

/* 天气 */
.ios-weather-grid { display: grid; gap: 8px; }

.ios-weather-item {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 10px 12px;
  border-radius: 8px;
  background: rgba(23, 33, 31, 0.05);
}

.ios-weather-item__date { font-size: 13px; font-weight: 600; color: var(--text-secondary); min-width: 60px; }
.ios-weather-item__temp { font-size: 18px; font-weight: 700; color: var(--brand-teal); min-width: 80px; }
.ios-weather-item__desc { font-size: 13px; color: var(--text-secondary); }

.ios-empty { font-size: 14px; color: var(--text-muted); }

/* 按天花费 */
.ios-day-budget-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 10px;
}

.ios-day-budget {
  border-radius: 10px;
  overflow: hidden;
  border: 0.5px solid rgba(0, 0, 0, 0.06);
}

.ios-day-budget__head {
  display: flex;
  justify-content: space-between;
  padding: 10px 12px;
  background: rgba(23, 33, 31, 0.05);
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
}

.ios-day-budget__body {
  display: grid;
  gap: 6px;
  padding: 12px;
}

.ios-row-between {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: var(--text-secondary);
}

.ios-row-between--bold {
  padding-top: 8px;
  border-top: 0.5px solid rgba(0, 0, 0, 0.06);
  font-weight: 600;
  color: var(--text-primary);
}

/* 点位明细 */
.ios-point-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 10px;
}

.ios-point {
  border-radius: 10px;
  overflow: hidden;
  border: 0.5px solid rgba(0, 0, 0, 0.06);
}

.ios-point__head {
  display: flex;
  justify-content: space-between;
  padding: 10px 12px;
  background: rgba(23, 33, 31, 0.05);
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
}

.ios-point__img {
  display: block;
  width: 100%;
  height: 140px;
  background-color: rgba(23, 33, 31, 0.05);
  object-fit: cover;
}

.ios-point__img--empty {
  display: grid;
  place-items: center;
  font-size: 13px;
  color: var(--text-muted);
}

.ios-point__info { padding: 6px 12px 0; font-size: 13px; color: var(--text-secondary); }
.ios-point__desc { padding: 8px 12px 12px; font-size: 13px; color: var(--text-secondary); line-height: 1.6; }

/* 个性化推荐理由（阶段四） */
.ios-point__reason {
  margin: 6px 12px 0;
  padding: 6px 10px;
  border-radius: 8px;
  background: rgba(47, 119, 112, 0.08);
  border: 0.5px solid rgba(47, 119, 112, 0.25);
  font-size: 12px;
  color: var(--brand-teal);
  line-height: 1.5;
}

/* 每日行程 */
.ios-day-list { display: grid; gap: 8px; }

.ios-day {
  border-radius: 10px;
  border: 0.5px solid rgba(0, 0, 0, 0.06);
  overflow: hidden;
}

.ios-day__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 14px;
  background: rgba(23, 33, 31, 0.05);
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  cursor: pointer;
  list-style: none;
}

.ios-day__head::-webkit-details-marker { display: none; }

.ios-day__head::after {
  content: "▸";
  font-size: 14px;
  color: var(--text-muted);
  transition: transform 0.2s ease;
}

.ios-day[open] .ios-day__head::after {
  transform: rotate(90deg);
}

.ios-day__body {
  display: grid;
  gap: 8px;
  padding: 14px;
  font-size: 14px;
  color: var(--text-secondary);
  line-height: 1.7;
  border-top: 0.5px solid rgba(0, 0, 0, 0.06);
}

/* 每日概览统计（确定性计算） */
.ios-day__stats {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 10px;
  padding: 8px 12px;
  background: rgba(23, 33, 31, 0.05);
  border-radius: 8px;
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.6;
}

/* 每日完整时间轴 */
.ios-tl {
  display: grid;
  gap: 0;
}

.ios-tl__row {
  display: grid;
  grid-template-columns: 62px 14px 1fr;
  gap: 6px;
}

.ios-tl__time {
  font-size: 12px;
  color: var(--text-muted);
  text-align: right;
  padding-top: 7px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.ios-tl__rail {
  position: relative;
  display: flex;
  justify-content: center;
}

/* 时间轴竖线（用行首圆点 + 伪元素延伸到卡片之间） */
.ios-tl__rail::before {
  content: "";
  position: absolute;
  top: 0;
  bottom: 0;
  width: 2px;
  background: rgba(23, 33, 31, 0.08);
}

.ios-tl__dot {
  position: relative;
  z-index: 1;
  align-self: flex-start;
  margin-top: 10px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  border: 2px solid var(--brand-teal);
  background: var(--surface-white);
}

.ios-tl__dot--spot { border-color: var(--brand-teal); }
.ios-tl__dot--transport { border-color: var(--brand-teal); background: var(--brand-teal); width: 8px; height: 8px; margin-top: 12px; }
.ios-tl__dot--meal { border-color: var(--warning); }
.ios-tl__dot--hotel { border-color: var(--success); }
.ios-tl__dot--note, .ios-tl__dot--other { border-color: var(--text-muted); background: var(--text-muted); width: 8px; height: 8px; margin-top: 12px; }

.ios-tl__card {
  background: var(--surface-white);
  border: 0.5px solid rgba(0, 0, 0, 0.07);
  border-radius: 10px;
  padding: 8px 12px;
  margin-bottom: 8px;
}

.ios-tl__card--transport { background: rgba(47, 119, 112, 0.08); }
.ios-tl__card--meal { background: rgba(201, 138, 45, 0.1); }
.ios-tl__card--hotel { background: rgba(60, 140, 112, 0.1); }
.ios-tl__card--note { background: rgba(23, 33, 31, 0.02); }

.ios-tl__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.ios-tl__tag {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-secondary);
  background: rgba(23, 33, 31, 0.05);
  padding: 1px 6px;
  border-radius: 4px;
  white-space: nowrap;
}

.ios-tl__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.ios-tl__fee {
  margin-left: auto;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
}

.ios-tl__sub {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}

.ios-tl__reason {
  margin-top: 4px;
  font-size: 12px;
  color: var(--brand-teal);
  background: rgba(47, 119, 112, 0.08);
  border: 0.5px solid rgba(47, 119, 112, 0.25);
  border-radius: 6px;
  padding: 2px 8px;
  display: inline-block;
  line-height: 1.5;
}

.ios-tl__desc {
  margin-top: 4px;
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.6;
}

/* 数据来源可信度徽标（对齐 PLAN §6：已验证/系统计算/模型建议/待核实） */
.ios-src {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 6px;
  white-space: nowrap;
}

.ios-src--verified { color: #1B7F3B; background: rgba(52, 199, 89, 0.12); border: 0.5px solid rgba(52, 199, 89, 0.35); }
.ios-src--system { color: var(--brand-deep); background: rgba(47, 119, 112, 0.1); border: 0.5px solid rgba(47, 119, 112, 0.3); }
.ios-src--model { color: #B25E00; background: rgba(255, 149, 0, 0.14); border: 0.5px solid rgba(255, 149, 0, 0.4); }
.ios-src--unknown { color: var(--text-secondary); background: rgba(142, 142, 147, 0.12); border: 0.5px solid rgba(142, 142, 147, 0.3); }

.ios-src--lg { font-size: 12px; padding: 3px 10px; }

/* 预算健康度（P1） */
.ios-budget-health {
  margin-top: 10px;
  display: grid;
  gap: 6px;
}

.ios-budget-health__bar {
  height: 6px;
  border-radius: 3px;
  background: rgba(23, 33, 31, 0.08);
  overflow: hidden;
}

.ios-budget-health__fill {
  display: block;
  height: 100%;
  border-radius: 3px;
  background: var(--success);
  transition: width 0.3s ease;
}

.ios-budget-health__fill.is-over {
  background: var(--danger);
}

.ios-budget-health__row {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;
  font-size: 12px;
  color: var(--text-secondary);
}

.ios-budget-health__row b {
  color: var(--text-primary);
}

.ios-budget-health__status {
  font-weight: 600;
  color: var(--success);
}

.ios-budget-health__status.is-over {
  color: var(--danger);
}

/* 数据可信度（P1） */
.ios-trust {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.ios-trust__addr {
  font-size: 12px;
  color: var(--text-secondary);
  background: rgba(47, 119, 112, 0.08);
  border: 0.5px solid rgba(47, 119, 112, 0.25);
  border-radius: 6px;
  padding: 3px 10px;
}

.ios-trust__legend {
  font-size: 11px;
  color: var(--text-muted);
  line-height: 1.6;
}

/* 空状态 */
.empty-state { min-height: 400px; }

@media (max-width: 960px) {
  .result-page { grid-template-columns: 1fr; }
  .sidebar { position: static; display: flex; gap: 16px; flex-wrap: wrap; }
  .sidebar__section { flex-direction: row; flex-wrap: wrap; gap: 8px; }
  .sidebar__divider { display: none; }
  .sidebar__label { display: none; }
  .result-content { grid-template-columns: 1fr; }
}

/* Agent 推理轨迹 */
.agent-trace { display: grid; gap: 10px; }

.agent-step {
  border-radius: 10px;
  padding: 12px 14px;
  background: rgba(23, 33, 31, 0.05);
  border-left: 3px solid var(--brand-teal);
}

.agent-step__header {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 6px;
}

.agent-step__num {
  font-size: 12px;
  font-weight: 600;
  color: var(--brand-teal);
  background: rgba(47, 119, 112, 0.1);
  padding: 2px 8px;
  border-radius: 6px;
}

.agent-step__action {
  font-size: 12px;
  color: var(--text-muted);
  font-style: italic;
}

.agent-step__thought {
  font-size: 14px;
  color: var(--text-primary);
  line-height: 1.5;
  margin-bottom: 6px;
}

.agent-step__tools {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}

.agent-tool {
  font-size: 12px;
  background: var(--surface-white);
  padding: 3px 8px;
  border-radius: 6px;
  border: 0.5px solid rgba(0, 0, 0, 0.08);
}

.agent-step__obs {
  font-size: 13px;
  color: var(--text-secondary);
  background: var(--surface-white);
  padding: 8px 10px;
  border-radius: 8px;
  line-height: 1.5;
}

.agent-step__obs-label {
  font-weight: 600;
  color: var(--text-secondary);
}

/* 数据来源 */
.ios-source-notes { display: grid; gap: 6px; }

.ios-source-note {
  font-size: 13px;
  color: var(--text-secondary);
  padding: 8px 12px;
  background: rgba(23, 33, 31, 0.05);
  border-radius: 8px;
  line-height: 1.5;
}

/* 点位反馈按钮（收藏/不感兴趣） */
.ios-point__actions {
  display: flex;
  gap: 8px;
  padding: 0 12px 12px;
}

.point-btn {
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  background: var(--surface-white);
  padding: 5px 12px;
  font-size: 12px;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.2s ease;
  font-family: inherit;
}

.point-btn:disabled { opacity: 0.55; cursor: not-allowed; }
.point-btn--save:hover:not(:disabled) { border-color: var(--brand-teal); color: var(--brand-teal); }
.point-btn--dislike:hover:not(:disabled) { border-color: var(--danger); color: var(--danger); }
.point-btn--done { background: var(--brand-teal); border-color: var(--brand-teal); color: var(--surface-white); }
.point-btn--done-dislike { background: var(--text-muted); border-color: var(--text-muted); color: var(--surface-white); }

/* 行程评分 */
.rate-stars {
  display: flex;
  align-items: center;
  gap: 6px;
}

.rate-star {
  border: none;
  background: transparent;
  font-size: 26px;
  line-height: 1;
  color: var(--border-soft);
  cursor: pointer;
  padding: 0 2px;
  transition: transform 0.15s ease, color 0.15s ease;
  font-family: inherit;
}

.rate-star:hover { transform: scale(1.15); }
.rate-star--active { color: var(--warning); }
.rate-star:disabled { opacity: 0.5; cursor: not-allowed; }

.rate-done {
  padding: 10px 14px;
  border-radius: 10px;
  background: rgba(23, 33, 31, 0.05);
}

.rate-aspects {
  margin-top: 14px;
  padding: 14px;
  border-radius: 10px;
  background: rgba(23, 33, 31, 0.05);
}

.rate-aspects__title {
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 10px;
  font-weight: 600;
}

.rate-aspects__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.rate-chip {
  border: 1px solid var(--border-soft);
  border-radius: 16px;
  background: var(--surface-white);
  padding: 5px 12px;
  font-size: 12px;
  color: var(--text-secondary);
  cursor: pointer;
  font-family: inherit;
}

.rate-chip--active { border-color: var(--brand-teal); background: var(--brand-teal); color: var(--surface-white); }
.rate-aspects__actions { display: flex; gap: 8px; }

/* 个性化规划摘要（P2：PLAN §5.5） */
.p-summary {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--text-secondary);
  font-size: 13px;
}
.p-summary__tag {
  display: inline-block;
  background: rgba(47, 119, 112, 0.10);
  color: var(--brand-deep);
  border-radius: 6px;
  padding: 0 6px;
  margin: 0 4px;
  font-weight: 600;
}
.p-summary__novelty { color: #8A6D1D; }
.p-summary__note { margin-top: 8px; font-size: 12px; }

/* 为什么没有推荐某些内容（P2：PLAN §5.6/§8.3） */
.filtered-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.filtered-item {
  background: rgba(23, 33, 31, 0.02);
  border: 1px solid rgba(23, 33, 31, 0.04);
  border-radius: 10px;
  padding: 8px 12px;
}
.filtered-item__head {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.filtered-item__name { font-weight: 600; color: var(--text-secondary); font-size: 13px; }
.filtered-item__reason { margin-top: 4px; font-size: 12px; color: var(--text-secondary); }
.filtered-item__ev {
  margin-top: 4px;
  font-size: 11px;
  display: inline-block;
  border-radius: 6px;
  padding: 1px 6px;
}
.filtered-item__ev.fc-ev--hard { background: rgba(255, 149, 0, 0.12); color: var(--warning); }
.filtered-item__ev.fc-ev--soft { background: rgba(47, 119, 112, 0.10); color: var(--brand-deep); }
.filtered-item__ev.fc-ev--weather { background: rgba(47, 119, 112, 0.12); color: var(--brand-teal); }
.ios-badge {
  display: inline-block;
  font-size: 10px;
  font-weight: 600;
  border-radius: 6px;
  padding: 1px 6px;
  background: rgba(23, 33, 31, 0.04);
  color: #6E6E73;
}
.ios-badge--warn { background: rgba(255, 149, 0, 0.15); color: var(--warning); }
.ios-badge--info { background: rgba(47, 119, 112, 0.10); color: var(--brand-deep); }

/* 不感兴趣原因选择（负反馈粒度，PLAN §2.2 问题三） */
.dislike-reasons {
  margin-top: 10px;
  padding: 10px 12px;
  background: rgba(23, 33, 31, 0.02);
  border-radius: 10px;
  border: 1px solid rgba(23, 33, 31, 0.04);
}
.dislike-reasons__title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: 8px;
}
.dislike-reasons__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}
.dislike-chip {
  border: 1px solid var(--border-soft);
  border-radius: 14px;
  background: var(--surface-white);
  padding: 4px 10px;
  font-size: 12px;
  color: var(--text-secondary);
  cursor: pointer;
  font-family: inherit;
}
.dislike-chip:hover { border-color: var(--danger); color: var(--danger); }
.dislike-chip:disabled { opacity: 0.5; cursor: default; }
.dislike-reasons__actions { display: flex; }
</style>
