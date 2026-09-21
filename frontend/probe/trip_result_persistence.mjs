/**
 * 结果页（生成完的行程）持久化 —— 端到端回归探针（真实 Chromium + 真实前端/后端）。
 *
 * 复验目标：生成/打开一份行程后，那页行程不能"跳走或刷新一下就没了"。
 * 口径：同一标签页内跳页面、F5 刷新、关掉标签页/新开标签页/浏览器重启都还在；
 * 只有退出登录（或登录失效）才清空。
 * ⚠️ 持久数据按账号隔离 —— 换个账号登录不能看到上一个人的行程（step 4/7 专门验这个）。
 *
 * 数据来源说明（不跑真实生成，避免消耗模型额度/时间）：
 *   用真实已保存行程做种子 —— 由 `frontend/probe/seed_trip.py` 从 trip_record 导出一份真实行程 JSON 到
 *   `_seed_trip.txt`，本探针注册一个一次性账号把它存成自己的行程，再经「历史列表 → 查看详情」
 *   走真实写入口（openSaved）。跑完执行 `python frontend/probe/seed_trip.py --clean` 清掉账号与其数据。
 *   ⚠️ 生成侧写入口 setFinished 与 openSaved 共用同一个落盘函数，本探针只覆盖后者；
 *      真实生成路径由 _probe_real_gen.mjs 覆盖。
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
/** 会话级键（同标签页导航/刷新） */
const KEY = "ai_travel_trip_workspace";
/** 持久级键（关标签页/新标签页/浏览器重启） */
const DURABLE_KEY = "ai_travel_trip_workspace_durable";
const SEED_FILE = process.env.SEED_FILE || path.join(REPO, "_seed_trip.txt");
const SHOT_DIR = path.join(HERE, "shots");

const SUFFIX = String(Date.now()).slice(-8);
const PROBE_USER = `rdprobe_${SUFFIX}`;
/** 第二个账号：只用来验"换账号看不到上一个人的行程" */
const PROBE_USER_B = `rdprobe_b_${SUFFIX}`;
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
const readDurable = (page) => page.evaluate((k) => localStorage.getItem(k), DURABLE_KEY);
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

async function register(username) {
  const reg = await api("/auth/register", {
    method: "POST",
    body: { username, password: PROBE_PWD, nickname: "结果页持久化探针" },
  });
  if (!reg.data?.token) throw new Error(`账号注册失败 ${username}：` + JSON.stringify(reg).slice(0, 200));
  return reg.data;
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

const regA = await register(PROBE_USER);
const token = regA.token;
const userId = regA.user?.id ?? regA.user_id ?? "";
const regB = await register(PROBE_USER_B);

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
  [token, JSON.stringify(regA.user ?? {})]
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

  const durableRaw = await readDurable(page);
  check("1. 产物同时落到持久镜像（关标签页也不丢的基础）",
    !!durableRaw && durableRaw.includes(TRIP_ID), durableRaw ? `${durableRaw.length} 字符` : "key 不存在");

  /* ---- 2. F5 刷新（会话级就能覆盖的核心场景） ---- */
  await page.reload();
  await page.waitForSelector(".result-page", { timeout: 15000 });
  s = await resultSnapshot(page);
  check("2. 刷新后仍在结果页（不再被踢回规划页）", /\/result/.test(page.url()), `url=${page.url()}`);
  check("2. 刷新后行程内容一致", s.dayCount === seedDays && s.title.includes(seedDestination),
    `title=${s.title} days=${s.dayCount}`);
  await page.screenshot({ path: path.join(SHOT_DIR, "trip_result_restored.png"), fullPage: false });

  /* ---- 3. 跳走再回来：首页「最近生成的行程」入口 ---- */
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

  /* ---- 3b. 跳走再回来：规划页「上次生成的行程」入口（用户最常回到的 tab） ---- */
  await page.locator(".nav-tab", { hasText: "规划" }).click();
  await page.waitForURL(/\/plan/, { timeout: 15000 });
  await page.waitForSelector(".last-trip", { timeout: 15000 });
  const planResumeText = await page.locator(".last-trip").innerText();
  check("3b. 规划页出现「上次生成的行程」入口", planResumeText.includes(seedDestination),
    planResumeText.replace(/\s+/g, " "));
  await page.locator(".last-trip__go").click();
  await page.waitForURL(/\/result/, { timeout: 15000 });
  await page.waitForSelector(".result-page", { timeout: 15000 });
  s = await resultSnapshot(page);
  check("3b. 从规划页入口能回到结果页且内容一致", s.dayCount === seedDays && s.title.includes(seedDestination));

  /* ---- 4. 新标签页（没有会话存储）→ 只能靠持久镜像恢复 ---- */
  const tab2 = await ctx.newPage();
  // 新标签页在应用脚本跑起来之前就把会话级数据抹掉并记下当时状态：
  // 这样"结果页仍能渲染"就只可能来自持久镜像（否则这条测试证明不了持久级在起作用）。
  await tab2.addInitScript((k) => {
    window.__initialSession = sessionStorage.getItem(k);
    sessionStorage.removeItem(k);
  }, KEY);
  await tab2.goto(APP + "/plan");
  await tab2.waitForSelector(".plan-page", { timeout: 20000 });
  check("4. 新标签页开局没有会话级数据（证明下面靠的不是会话级）",
    (await tab2.evaluate(() => window.__initialSession)) === null);
  check("4. 新标签页读得到同一账号的持久镜像",
    (await tab2.evaluate((k) => localStorage.getItem(k), DURABLE_KEY)) !== null);
  await tab2.goto(APP + "/result");
  await tab2.waitForSelector(".result-page", { timeout: 15000 });
  const s2 = await resultSnapshot(tab2);
  check("4. 新标签页直接打开结果页也能渲染出行程（关标签页后不丢）",
    s2.onResult && s2.dayCount === seedDays && s2.title.includes(seedDestination),
    `title=${s2.title} days=${s2.dayCount}`);
  await tab2.close();

  /* ---- 5. 换账号：持久镜像里还是 A 的数据，但 B 不该看到 ---- */
  const state = await ctx.storageState();
  const ctxB = await browser.newContext({ viewport: { width: 1280, height: 900 }, storageState: state });
  await ctxB.addInitScript(
    ([t, u]) => {
      localStorage.setItem("ai_travel_token", t);
      localStorage.setItem("ai_travel_user", u);
    },
    [regB.token, JSON.stringify(regB.user ?? {})]
  );
  const tabB = await ctxB.newPage();
  // 抢在应用脚本之前看一眼 localStorage：那会儿 A 的镜像还在。
  // （页面加载后会被 ownerId 校验判定"不是我的"并顺手清掉，所以必须在 init 脚本里取。）
  await tabB.addInitScript((k) => {
    window.__durableBefore = localStorage.getItem(k);
  }, DURABLE_KEY);
  await tabB.goto(APP + "/plan");
  await tabB.waitForSelector(".plan-page", { timeout: 20000 });
  check("5. 前提：B 的浏览器里确实躺着 A 的持久镜像（否则这条测试没意义）",
    (await tabB.evaluate(() => window.__durableBefore))?.includes(TRIP_ID) === true);
  check("5. 不属于自己的镜像会被顺手清掉（不留痕）",
    (await tabB.evaluate((k) => localStorage.getItem(k), DURABLE_KEY)) === null);
  await tabB.goto(APP + "/result");
  await tabB.waitForURL(/\/plan/, { timeout: 15000 });
  check("5. 换账号 B 打不开 A 的结果页（按账号隔离生效）", /\/plan/.test(tabB.url()), `url=${tabB.url()}`);
  await tabB.goto(APP + "/");
  await tabB.waitForSelector(".hero", { timeout: 20000 });
  check("5. 换账号 B 的首页不出现 A 的「最近生成的行程」",
    (await tabB.locator(".resume").count()) === 0);
  await ctxB.close();

  /* ---- 6. 反证：两级存储都清掉 → 正是修复前"被踢回规划页"的行为 ---- */
  await page.evaluate(
    ([a, b]) => {
      sessionStorage.removeItem(a);
      localStorage.removeItem(b);
    },
    [KEY, DURABLE_KEY]
  );
  await page.goto(APP + "/result");
  await page.waitForURL(/\/plan/, { timeout: 15000 });
  check("6. 反证：无任何持久数据时打开结果页 → 被踢回规划页（修复前行为）", /\/plan/.test(page.url()),
    `url=${page.url()}`);

  /* ---- 7. 退出登录：产物必须一起清（换账号不会看到别人的行程） ---- */
  await page.goto(APP + "/history");
  await page.waitForSelector(".history-card", { timeout: 20000 });
  await page.locator(".history-card").first().getByRole("button", { name: "查看详情" }).click();
  await page.waitForURL(/\/result/, { timeout: 15000 });
  check("7. 重新打开后会话级与持久级都已写入",
    (await readKey(page)) !== null && (await readDurable(page)) !== null);
  await page.locator(".nav-bar__logout").click();
  await page.waitForURL(/\/login/, { timeout: 15000 });
  check("7. 退出登录后会话级清空", (await readKey(page)) === null);
  check("7. 退出登录后持久镜像也清空（下次登录不会诈尸）", (await readDurable(page)) === null);
  await page.goto(APP + "/result");
  await page.waitForURL(/\/plan|\/login/, { timeout: 15000 });
  check("7. 退出后直接打开结果页也进不去", /\/plan|\/login/.test(page.url()), `url=${page.url()}`);

  check("8. 全流程无页面级 JS 异常", errors.length === 0, errors.slice(0, 3).join(" || "));
} catch (e) {
  check("流程执行完整", false, String(e).slice(0, 500));
} finally {
  await browser.close();
}

console.log(`\n探针账号（跑完请执行 python frontend/probe/seed_trip.py --clean 清理）：${PROBE_USER}, ${PROBE_USER_B}`);
console.log(`\nRESULT ${pass} PASS / ${fail} FAIL`);
process.exit(fail === 0 ? 0 : 1);
