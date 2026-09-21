import { reactive, watch } from "vue";

/**
 * 规划页（/plan）草稿单例：跨页面导航保留用户已经填好的规划条件。
 *
 * 背景：表单原先整块放在 PlannerView 组件里（本地 reactive），组件一卸载就没了——
 * 点顶部导航去首页/发现/社区、或生成完行程跳到结果页再回来，目的地、日期、人数、
 * 预算、偏好、备注、"已加入景点"全部清空，只能重填。现把这份状态收敛到本模块，
 * 组件只做绑定，卸载不再丢：
 * - 保留时机：同一标签页内任意页面跳转、浏览器刷新；
 * - 清空时机：① 退出登录 / 登录失效（stores/trip.ts 的 clearAll → clearPlannerDraft）
 *             ② 关闭标签页或重启浏览器（sessionStorage 天然清空）
 *             ③ 用户在规划页点"清空"
 *
 * 为什么用 sessionStorage 而不是 localStorage：需求是"只有退出账号或系统重启才丢"。
 * sessionStorage 恰好是"同一标签页内导航/刷新都在、关掉页面即清空"，而且按标签页隔离，
 * 天然不会出现 A 账号草稿被 B 账号看到的串号问题。若要连浏览器重启也保留，
 * 只需把下面的 storage 换成 localStorage（一处改动，语义随之变化）。
 */

const STORAGE_KEY = "ai_travel_plan_draft";

/** 规划页"已加入行程"的景点（来源：景点卡/热门榜的"加入行程"深链） */
export interface PlannerDraftSpot {
  name: string;
  spotId?: string;
  poiId?: string;
}

/** 规划表单字段（与 /plan 页面输入项一一对应；日期用 YYYY-MM-DD 字符串，便于直接序列化） */
export interface PlannerDraftForm {
  destination: string;
  startDate: string;
  endDate: string;
  travelers: number;
  budget: number;
  hotelLevel: string;
  pace: string;
  preferences: string[];
  dietaryPreferences: string[];
  notes: string;
}

export interface PlannerDraft {
  form: PlannerDraftForm;
  spots: PlannerDraftSpot[];
}

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

/** 相对今天偏移 offsetDays 的日期（YYYY-MM-DD） */
function isoDate(offsetDays: number): string {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${m}-${day}`;
}

/** 新建表单的默认值（默认今天出发、玩 3 天）——与改造前的页面默认值保持一致 */
function defaultForm(): PlannerDraftForm {
  return {
    destination: "",
    startDate: isoDate(0),
    endDate: isoDate(2),
    travelers: 2,
    budget: 3200,
    hotelLevel: "舒适型",
    pace: "轻松",
    preferences: [],
    dietaryPreferences: [],
    notes: "",
  };
}

function toStrArray(v: unknown): string[] {
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : [];
}

function toNum(v: unknown, fallback: number, min: number, max: number): number {
  const n = typeof v === "number" ? v : Number(v);
  if (!Number.isFinite(n)) return fallback;
  return Math.min(max, Math.max(min, Math.round(n)));
}

function sanitizeSpot(v: unknown): PlannerDraftSpot | null {
  if (!v || typeof v !== "object") return null;
  const o = v as Record<string, unknown>;
  const name = typeof o.name === "string" ? o.name.trim() : "";
  if (!name) return null;
  return {
    name,
    spotId: typeof o.spotId === "string" && o.spotId ? o.spotId : undefined,
    poiId: typeof o.poiId === "string" && o.poiId ? o.poiId : undefined,
  };
}

/**
 * 读取并逐字段校正草稿。存储不可用（隐私模式/超配额）、没有草稿、结构损坏 → null，
 * 调用方退回默认值。逐字段校正而不是整体信任 JSON：草稿是跨版本存活的数据，
 * 老版本字段缺失/类型变化不能让页面直接崩。
 */
function load(): PlannerDraft | null {
  let raw: string | null = null;
  try {
    raw = sessionStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!parsed || typeof parsed !== "object") return null;
    const root = parsed as Record<string, unknown>;
    const src = (root.form ?? {}) as Record<string, unknown>;
    const def = defaultForm();

    const startDate =
      typeof src.startDate === "string" && DATE_RE.test(src.startDate)
        ? src.startDate
        : def.startDate;
    let endDate =
      typeof src.endDate === "string" && DATE_RE.test(src.endDate) ? src.endDate : def.endDate;
    // ISO 日期字符串可直接按字典序比大小；结束早于开始 → 收敛成同一天
    if (endDate < startDate) endDate = startDate;

    const form: PlannerDraftForm = {
      destination: typeof src.destination === "string" ? src.destination : def.destination,
      startDate,
      endDate,
      travelers: toNum(src.travelers, def.travelers, 1, 99),
      budget: toNum(src.budget, def.budget, 0, 1000000),
      hotelLevel:
        typeof src.hotelLevel === "string" && src.hotelLevel ? src.hotelLevel : def.hotelLevel,
      pace: typeof src.pace === "string" && src.pace ? src.pace : def.pace,
      preferences: toStrArray(src.preferences),
      dietaryPreferences: toStrArray(src.dietaryPreferences),
      notes: typeof src.notes === "string" ? src.notes : def.notes,
    };

    const spots = Array.isArray(root.spots)
      ? root.spots
          .map(sanitizeSpot)
          .filter((x): x is PlannerDraftSpot => x !== null)
      : [];

    return { form, spots };
  } catch {
    return null;
  }
}

/** 草稿里是否有"用户动过"的内容（决定要不要显示"已恢复上次填写"提示，避免空白表单也弹提示） */
function hasContent(d: PlannerDraft): boolean {
  const f = d.form;
  const def = defaultForm();
  return (
    f.destination.trim() !== "" ||
    f.notes.trim() !== "" ||
    f.preferences.length > 0 ||
    f.dietaryPreferences.length > 0 ||
    d.spots.length > 0 ||
    f.travelers !== def.travelers ||
    f.budget !== def.budget ||
    f.hotelLevel !== def.hotelLevel ||
    f.pace !== def.pace ||
    f.startDate !== def.startDate ||
    f.endDate !== def.endDate
  );
}

const restored = load();

/** 规划表单 + 已加入景点（单例；页面直接绑定，卸载不清） */
export const plannerDraft = reactive<PlannerDraft>(
  restored ?? { form: defaultForm(), spots: [] }
);

/**
 * 草稿里是否有"用户动过"的内容（页面对照默认值判断，决定要不要提示"已恢复上次填写"）。
 * 暴露成函数而不是模块级常量：页面在挂载时取一次快照 —— 只有"进来就发现内容还在"
 * 才提示；用户随后边填边改不应该让提示条突然跳出来。
 */
export function plannerDraftHasContent(): boolean {
  return hasContent(plannerDraft);
}

let saveTimer: number | undefined;

function save() {
  saveTimer = undefined;
  try {
    if (hasContent(plannerDraft)) {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(plannerDraft));
    } else {
      // 空草稿不落存储：用户清空 / 退出登录后，复位动作本身也会触发本回调，
      // 若这里无条件写回，刚删掉的键会被"把默认值又存回去"复活，下次进来还会误报"已恢复"。
      sessionStorage.removeItem(STORAGE_KEY);
    }
  } catch {
    /* 存储不可用/超配额：降级为"本次不持久化"，不影响表单正常使用 */
  }
}

// 输入框逐字变更都会触发：合并到下一轮事件循环再落盘，避免每次按键都序列化写存储。
// flush 保持默认（pre），页面级 watcher 不会因为落盘产生额外重渲染。
watch(
  plannerDraft,
  () => {
    if (saveTimer !== undefined) window.clearTimeout(saveTimer);
    saveTimer = window.setTimeout(save, 200);
  },
  { deep: true }
);

/** 清空草稿（退出登录 / 登录失效 / 用户手动清空）：内存态与存储一起复位 */
export function clearPlannerDraft() {
  if (saveTimer !== undefined) {
    window.clearTimeout(saveTimer);
    saveTimer = undefined;
  }
  Object.assign(plannerDraft.form, defaultForm());
  plannerDraft.spots.splice(0, plannerDraft.spots.length);
  try {
    sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    /* 同上：存储不可用时无所谓，内存已复位 */
  }
}
