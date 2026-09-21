/**
 * 结果页（生成完的行程）持久化 —— 端到端回归探针（真实 Chromium + 真实前端/后端）。
 *
 * 复验目标：生成/打开一份行程后，那页行程不能"跳走或刷新一下就没了"。
 * 口径与规划页草稿一致：同一标签页内跳页面、F5 刷新都还在；
 * 只有退出登录（或登录失效）、关闭标签页/重启浏览器才清空。
 *
 * 数据来源说明（不跑真实生成，避免消耗模型额度/时间）：
 *   用真实已保存行程做种子 —— 由 `frontend/probe/seed_trip.py` 从 trip_record 导出一份真实行程 JSON 到
 *   `_seed_trip.txt`，本探针注册一个一次性账号把它存成自己的行程，再经「历史列表 → 查看详情」
 *   走真实写入口（openSaved）。跑完执行 `python frontend/probe/seed_trip.py --clean` 清掉该账号与其数据。
 *   ⚠️ 生成侧写入口 setFinished 与 openSaved 共用同一个落盘函数，本探针只覆盖后者。
 *
 * 前置：后端 8080、前端 5173 在跑；playwright-core 已装（见 windows-sandbox-shell-workarounds §10）。
 *
 * 用法（仓库根目录）：
 *   node frontend/probe/trip_result_persistence.mjs
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, "..", "..");
const APP = process.env.APP_URL || "http://127.0.0.1:5173";
const API = process.env.API_URL || "http://127.0.0.1:8080";
const KEY = "ai_travel_trip_workspace";
const SEED_FILE = process.env.SEED_FILE || path.join(REPO, "_seed_trip.txt");
const SHOT_DIR = path.join(HERE, "shots");

const SUFFIX = String(Date.now()).slice(-8);
const PROBE_USER = `rdprobe_${SUFFIX}`;
const PROBE_PWD = "test1234";
const TRIP_ID = `probe-trip-${SUFFIX}`;

function resolvePlaywright() {
  const cands = [
    process.env.PW_CORE,
    path.join(HERE, "..", "node_modules", "playwright-core", "index.mjs"),
    path.join(os.homedir(), ".workbuddy", "binaries", "node", "workspace", "node_modules", "playwright-core", "index.mjs"),
  ].filter(Boolean);
  for (const c of cands) if (fs.existsSync(c)) return pathToFileURL(c).href;
  throw new Error("找不到 playwright-core，请先安装并设置 PW_CORE。候选：\n  " + cands.join("\n  "));
}

function chromeExe() {
  const base = process.env.PLAYWRIGHT_BROWSERS_PATH || path.join(os.homedir(), "AppData", "Local", "ms-playwright");
  const dirs = fs
    .readdirSync(base)
    .filter((d) => /^chromium-\d+$/.test(d))
    .sort((a, b) => Number(b.split("-")[1]) - Number(a.split("-")[1]));
  for (const d of dirs) {
    const exe = path.join(base, d, "chrome-win64", "chrome.exe");
    if (fs.existsSync(exe)) return exe;
  }
  throw new Error("ms-playwright 里没有可用的 chromium：" + base);
}

const { chromium } = await import(resolvePlaywright());

let pass = 0;
let fail = 0;
function check(name, ok, extra = "") {
  console.log(`${ok ? "PASS" : "FAIL"} | ${name}${extra ? " | " + extra : ""}`);
  ok ? pass++ : fail++;
}

const readKey = (page) => page.evaluate((k) => sessionStorage.getItem(k), KEY);
const readWorkspace = (page) =>
  page.evaluate((k) => {
    const raw = sessionStorage.getItem(k);
    return raw ? JSON.parse(raw) : null;
  }, KEY);

/** 结果页快照：只看可见结构 */
async function resultSnapshot(page) {
  return page.evaluate(() => ({
    onResult: !!document.querySelector(".result-page"),
    title: document.querySelector(".ios-card__title")?.textContent?.trim() ?? "",
    dayCount: document.querySelectorAll(".ios-day").length,
    tripIdRow: Array.from(document.querySelectorAll(".ios-info")).map((e) => e.textContent.trim()),
  }));
}

async function api(path, { method = "GET", token, body } = {}) {
  const r = await fetch(API + path, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: "Bearer " + token } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let data = {};
  try {
    data = await r.json();
  } catch {
    /* 非 JSON 响应（如 PDF 流）：忽略 */
  }
  return { status: r.status, data };
}

/* ---------- 0. 种子数据：一次性账号 + 一份真实行程 ---------- */
if (!fs.existsSync(SEED_FILE)) {
  console.error(`缺少种子文件 ${SEED_FILE}，先跑：python frontend/probe/seed_trip.py`);
  process.exit(2);
}
const seed = JSON.parse(fs.readFileSync(SEED_FILE, "utf-8"));
seed.itinerary.trip_id = TRIP_ID;
const seedDestination = seed.itinerary.destination;
const seedDays = seed.itinerary.days.length;

const reg = await api("/auth/register", {
  method: "POST",
  body: { username: PROBE_USER, password: PROBE_PWD, nickname: "结果页持久化探针" },
});
if (!reg.data?.token) throw new Error("探针账号注册失败：" + JSON.stringify(reg).slice(0, 200));
const token = reg.data.token;
const userId = reg.data.user?.id ?? reg.data.user_id ?? "";

const saved = await api("/trip/save", {
  method: "POST",
  token,
  body: { trip_id: TRIP_ID, itinerary: seed.itinerary, user_id: userId, trace: [] },
});
check("0. 种子行程已保存为该账号的历史行程", saved.status === 200, `status=${saved.status}`);

/* ---------- 浏览器 ---------- */
const browser = await chromium.launch({ executablePath: chromeExe(), headless: true });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
await ctx.addInitScript(
  ([t, u]) => {
    localStorage.setItem("ai_travel_token", t);
    localStorage.setItem("ai_travel_user", u);
  },
  [token, JSON.stringify(reg.data.user ?? {})]
);
const page = await ctx.newPage();
const errors = [];
page.on("pageerror", (e) => errors.push(String(e)));

try {
  fs.mkdirSync(SHOT_DIR, { recursive: true });

  /* ---- 1. 历史列表打开行程（真实写入口 openSaved） ---- */
  await page.goto(APP + "/history");
  await page.waitForSelector(".history-card", { timeout: 20000 });
  const cardText = await page.locator(".history-card").first().innerText();
  check("1. 历史列表出现该行程", cardText.includes(seedDestination), cardText.split("\n")[0]);
  await page.locator(".history-card").first().getByRole("button", { name: "查看详情" }).click();
  await page.waitForURL(/\/result/, { timeout: 15000 });
  await page.waitForSelector(".result-page", { timeout: 15000 });
  let s = await resultSnapshot(page);
  check("1. 进入结果页并渲染出行程", s.onResult && s.dayCount === seedDays,
    `title=${s.title} days=${s.dayCount}`);
  check("1. 行程 ID 与种子一致", s.tripIdRow.some((t) => t.includes(TRIP_ID)));

  let ws = await readWorkspace(page);
  check("1. openSaved 已把产物落到会话存储（真实写入口生效）",
    ws?.itinerary?.trip_id === TRIP_ID && ws?.itinerary?.days?.length === seedDays,
    ws ? `trip_id=${ws.itinerary.trip_id} days=${ws.itinerary.days.length}` : "key 不存在");

  /* ---- 2. F5 刷新（本次修复的核心） ---- */
  await page.reload();
  await page.waitForSelector(".result-page", { timeout: 15000 });
  s = await resultSnapshot(page);
  check("2. 刷新后仍在结果页（不再被踢回规划页）", /\/result/.test(page.url()), `url=${page.url()}`);
  check("2. 刷新后行程内容一致", s.dayCount === seedDays && s.title.includes(seedDestination),
    `title=${s.title} days=${s.dayCount}`);
  await page.screenshot({ path: path.join(SHOT_DIR, "trip_result_restored.png"), fullPage: false });

  /* ---- 3. 跳走再回来：首页出现「最近生成的行程」入口 ---- */
  await page.locator(".nav-tab", { hasText: "首页" }).click();
  await page.waitForURL(/\/$|\/dashboard/, { timeout: 15000 }).catch(() => {});
  await page.waitForSelector(".resume", { timeout: 15000 });
  const resumeText = await page.locator(".resume").innerText();
  check("3. 首页出现「最近生成的行程」入口", resumeText.includes(seedDestination),
    resumeText.replace(/\s+/g, " "));
  await page.locator(".resume__go").click();
  await page.waitForURL(/\/result/, { timeout: 15000 });
  await page.waitForSelector(".result-page", { timeout: 15000 });
  s = await resultSnapshot(page);
  check("3. 从首页入口能回到结果页且内容一致", s.dayCount === seedDays && s.title.includes(seedDestination));

  /* ---- 4. 反证：去掉会话存储后刷新 → 正是修复前"被踢回规划页"的行为 ---- */
  await page.evaluate((k) => sessionStorage.removeItem(k), KEY);
  await page.reload();
  await page.waitForURL(/\/plan/, { timeout: 15000 });
  check("4. 反证：无会话存储时刷新结果页 → 被踢回规划页（修复前行为）", /\/plan/.test(page.url()),
    `url=${page.url()}`);

  /* ---- 5. 退出登录：产物必须一起清（换账号不会看到别人的行程） ---- */
  await page.goto(APP + "/history");
  await page.waitForSelector(".history-card", { timeout: 20000 });
  await page.locator(".history-card").first().getByRole("button", { name: "查看详情" }).click();
  await page.waitForURL(/\/result/, { timeout: 15000 });
  check("5. 重新打开后存储再次写入", (await readKey(page)) !== null);
  await page.locator(".nav-bar__logout").click();
  await page.waitForURL(/\/login/, { timeout: 15000 });
  check("5. 退出登录后行程产物被清除", (await readKey(page)) === null);
  await page.goto(APP + "/result");
  await page.waitForURL(/\/plan|\/login/, { timeout: 15000 });
  check("5. 退出后直接打开结果页也进不去", /\/plan|\/login/.test(page.url()), `url=${page.url()}`);

  check("6. 全流程无页面级 JS 异常", errors.length === 0, errors.slice(0, 3).join(" || "));
} catch (e) {
  check("流程执行完整", false, String(e).slice(0, 500));
} finally {
  await browser.close();
}

console.log(`\n探针账号（跑完请执行 python frontend/probe/seed_trip.py --clean 清理）：${PROBE_USER}`);
console.log(`\nRESULT ${pass} PASS / ${fail} FAIL`);
process.exit(fail === 0 ? 0 : 1);
