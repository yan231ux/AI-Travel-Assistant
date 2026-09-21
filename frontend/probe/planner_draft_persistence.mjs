/**
 * 规划页草稿持久化 —— 端到端回归探针（真实 Chromium + 真实前端/后端）。
 *
 * 复验目标：规划表单填好之后跳到别的界面再回来，内容必须还在；
 * 只有退出账号 / 关闭标签页或重启浏览器 / 手动清空，才允许丢失。
 *
 * 前置：
 *   1) 后端已在 8080 运行、前端已在 5173 运行（见项目 MEMORY 的启动命令）；
 *   2) 演示账号 demo_ops_viewer / test1234 已存在（不存在会自动注册，见 login()）；
 *   3) 装了 playwright-core（只驱动浏览器，不需要下载额外内核）：
 *        cd <managed-node-workspace> && npm install playwright-core
 *      再用环境变量指定它的位置（下面 resolvePlaywright() 有默认候选路径）。
 *
 * 用法（在仓库根目录）：
 *   node frontend/probe/planner_draft_persistence.mjs
 *   PW_CORE=/path/to/playwright-core/index.mjs APP_URL=http://127.0.0.1:5173 \
 *     node frontend/probe/planner_draft_persistence.mjs
 *
 * 覆盖用例：见各段落注释（首次进入 / 填写落盘 / 跳走再回来 / 刷新 / 深链加入行程 /
 * 换城市 / 手动清空 / 退出登录 / 无 JS 异常）。
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const APP = process.env.APP_URL || "http://127.0.0.1:5173";
const API = process.env.API_URL || "http://127.0.0.1:8080";
const DRAFT_KEY = "ai_travel_plan_draft";
const SHOT_DIR = path.join(HERE, "shots");

/** 找到 playwright-core 的 ESM 入口：环境变量 → 前端工程 → 托管 Node 工作区 */
function resolvePlaywright() {
  const cands = [
    process.env.PW_CORE,
    path.join(HERE, "..", "node_modules", "playwright-core", "index.mjs"),
    path.join(os.homedir(), ".workbuddy", "binaries", "node", "workspace", "node_modules", "playwright-core", "index.mjs"),
  ].filter(Boolean);
  for (const c of cands) {
    if (fs.existsSync(c)) return pathToFileURL(c).href;
  }
  throw new Error(
    "找不到 playwright-core，请先安装并设置 PW_CORE。候选路径：\n  " + cands.join("\n  ")
  );
}

/** 找到 Chromium 可执行文件：优先用 ms-playwright 缓存里已下载好的内核 */
function chromeExe() {
  const base = process.env.PLAYWRIGHT_BROWSERS_PATH || path.join(os.homedir(), "AppData", "Local", "ms-playwright");
  if (!fs.existsSync(base)) throw new Error("找不到 ms-playwright 浏览器缓存目录：" + base);
  const dirs = fs
    .readdirSync(base)
    .filter((d) => /^chromium-\d+$/.test(d))
    .sort((a, b) => Number(b.split("-")[1]) - Number(a.split("-")[1]));
  for (const d of dirs) {
    const exe = path.join(base, d, "chrome-win64", "chrome.exe");
    if (fs.existsSync(exe)) return exe;
  }
  throw new Error("ms-playwright 里没有可用的 chromium：");
}

const { chromium } = await import(resolvePlaywright());

let pass = 0;
let fail = 0;
function check(name, ok, extra = "") {
  console.log(`${ok ? "PASS" : "FAIL"} | ${name}${extra ? " | " + extra : ""}`);
  ok ? pass++ : fail++;
}

async function login() {
  const post = async (p, body) => {
    const r = await fetch(API + p, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    return { status: r.status, body: await r.json().catch(() => ({})) };
  };
  const cred = { username: "demo_ops_viewer", password: "test1234", nickname: "规划草稿演示" };
  let r = await post("/auth/register", cred);
  if (!r.body?.token) r = await post("/auth/login", cred);
  if (!r.body?.token) throw new Error("演示账号登录失败: " + JSON.stringify(r).slice(0, 300));
  return r.body;
}

const readKey = (page) => page.evaluate((k) => sessionStorage.getItem(k), DRAFT_KEY);
const readDraft = (page) =>
  page.evaluate((k) => {
    const raw = sessionStorage.getItem(k);
    return raw ? JSON.parse(raw) : null;
  }, DRAFT_KEY);

/** 表单快照：只用页面上的可见结构取，不依赖内部实现 */
async function formSnapshot(page) {
  return page.evaluate(() => {
    const cards = Array.from(document.querySelectorAll(".ios-card"));
    const card1 = cards[0];
    const card2 = cards[1];
    const nums1 = card1 ? card1.querySelectorAll('input[type="number"]') : [];
    const dayInput = document.querySelector(".ios-info-row input");
    return {
      destination: card1?.querySelector(".ios-field--full input")?.value ?? null,
      travelers: nums1[0] ? nums1[0].value : null,
      dayCount: dayInput ? dayInput.value : null,
      budget: card2?.querySelector('input[type="number"]')?.value ?? null,
      notes: document.querySelector("textarea.ios-textarea")?.value ?? null,
      chips: Array.from(document.querySelectorAll(".ios-chip--active"))
        .map((b) => b.textContent.trim())
        .sort(),
      joined: Array.from(document.querySelectorAll(".joined-spot__chip")).map((e) =>
        e.textContent.replace("📍", "").replace("✕", "").trim()
      ),
      tipShown: !!document.querySelector(".draft-tip"),
    };
  });
}

const NOTES = "不想太早起床，想安排看日落的地点";

const auth = await login();
const browser = await chromium.launch({ executablePath: chromeExe(), headless: true });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
// 直接注入登录态，跳过登录页 UI（本探针只关心规划页草稿，与登录表单无关）
await ctx.addInitScript(
  ([t, u]) => {
    localStorage.setItem("ai_travel_token", t);
    localStorage.setItem("ai_travel_user", u);
  },
  [auth.token, JSON.stringify(auth.user)]
);
const page = await ctx.newPage();
const errors = [];
page.on("pageerror", (e) => errors.push(String(e)));

try {
  fs.mkdirSync(SHOT_DIR, { recursive: true });

  /* ---- 1. 首次进入：无草稿 ---- */
  await page.goto(APP + "/plan");
  await page.waitForSelector(".plan-page", { timeout: 20000 });
  let s = await formSnapshot(page);
  check("1. 首次进入表单为空（未预填目的地）", s.destination === "", `destination=${JSON.stringify(s.destination)}`);
  check("1. 首次进入不显示“已恢复”提示", s.tipShown === false);
  check("1. 首次进入无草稿存储", (await readKey(page)) === null);

  /* ---- 2. 填写表单：是否真的落盘 ---- */
  await page.locator(".ios-card >> nth=0 >> .ios-field--full input").fill("三亚");
  await page.locator('.ios-card >> nth=0 >> input[type="number"]').first().fill("3");
  await page.locator('.ios-card >> nth=1 >> input[type="number"]').fill("5000");
  await page.locator("textarea.ios-textarea").fill(NOTES);
  await page.locator('.ios-chip:text-is("美食")').click();
  await page.locator('.ios-chip:text-is("休闲")').click();
  await page.locator('.ios-chip:text-is("少辣")').click();
  await page.waitForTimeout(500); // 等落盘节流（200ms）

  const stored = await readDraft(page);
  check("2. 填写后草稿已写入 sessionStorage", !!stored, stored ? `destination=${stored.form.destination}` : "null");
  check(
    "2. 草稿字段完整（目的地/人数/预算/偏好/备注）",
    stored?.form?.destination === "三亚" &&
      stored?.form?.travelers === 3 &&
      stored?.form?.budget === 5000 &&
      stored?.form?.preferences?.includes("美食") &&
      stored?.form?.dietaryPreferences?.includes("少辣") &&
      stored?.form?.notes?.startsWith("不想太早起床"),
    JSON.stringify(stored?.form ?? null)
  );

  /* ---- 3. 跳走再回来（核心需求） ---- */
  await page.locator(".nav-tab", { hasText: "发现" }).click();
  await page.waitForURL(/\/recommendations/, { timeout: 15000 });
  check("3. 已离开规划页（规划表单已卸载）", (await page.locator(".plan-page").count()) === 0);
  await page.locator(".nav-tab", { hasText: "规划" }).click();
  await page.waitForSelector(".plan-page", { timeout: 15000 });
  s = await formSnapshot(page);
  check("3. 返回后目的地保留", s.destination === "三亚", `destination=${JSON.stringify(s.destination)}`);
  check("3. 返回后人数保留", s.travelers === "3", `travelers=${s.travelers}`);
  check("3. 返回后预算保留", s.budget === "5000", `budget=${s.budget}`);
  check("3. 返回后备注保留", s.notes === NOTES);
  check(
    "3. 返回后偏好标签保留（美食/休闲/少辣）",
    ["少辣", "休闲", "美食"].every((c) => s.chips.includes(c)),
    `chips=${JSON.stringify(s.chips)}`
  );
  check("3. 返回后天数仍与日期区间一致", s.dayCount === "3", `dayCount=${s.dayCount}`);
  check("3. 返回后显示“已恢复上次填写的内容”提示", s.tipShown === true);
  await page.screenshot({ path: path.join(SHOT_DIR, "planner_draft_restored.png"), fullPage: true });

  /* ---- 4. 浏览器刷新（会话级存储语义） ---- */
  await page.reload();
  await page.waitForSelector(".plan-page", { timeout: 20000 });
  s = await formSnapshot(page);
  check("4. 刷新后目的地仍保留", s.destination === "三亚", `destination=${JSON.stringify(s.destination)}`);
  check("4. 刷新后备注仍保留", s.notes === NOTES);

  /* ---- 5. 深链“加入行程”的景点标签也要跨页面保留 ---- */
  await page.goto(APP + "/plan?city=三亚&spot=天涯海角&spot_id=spot_三亚_1");
  await page.waitForSelector(".plan-page", { timeout: 20000 });
  s = await formSnapshot(page);
  check("5. 深链“加入行程”后出现景点标签", s.joined.includes("天涯海角"), `joined=${JSON.stringify(s.joined)}`);
  await page.locator(".nav-tab", { hasText: "首页" }).click();
  await page.waitForURL((u) => !/\/plan/.test(u.pathname), { timeout: 15000 });
  await page.locator(".nav-tab", { hasText: "规划" }).click();
  await page.waitForSelector(".plan-page", { timeout: 15000 });
  s = await formSnapshot(page);
  check("5. 跳走再回来“已加入景点”仍在", s.joined.includes("天涯海角"), `joined=${JSON.stringify(s.joined)}`);

  /* ---- 6. 换城市：目的地跟随 + 旧城市的景点标签清空（不跨城市混排） ---- */
  await page.goto(APP + "/plan?city=上海");
  await page.waitForSelector(".plan-page", { timeout: 20000 });
  s = await formSnapshot(page);
  check("6. 换城市后目的地跟随新城市", s.destination === "上海", `destination=${JSON.stringify(s.destination)}`);
  check("6. 换城市后旧城市的“已加入景点”被清空", s.joined.length === 0, `joined=${JSON.stringify(s.joined)}`);
  check("6. 换城市不影响其它字段（预算仍保留）", s.budget === "5000", `budget=${s.budget}`);

  /* ---- 7. 手动清空：内存 + 存储一起复位 ---- */
  await page.locator(".draft-tip__clear").click();
  await page.waitForTimeout(400);
  s = await formSnapshot(page);
  check("7. 清空后目的地复位", s.destination === "", `destination=${JSON.stringify(s.destination)}`);
  check("7. 清空后备注复位", s.notes === "");
  check("7. 清空后标签复位", s.chips.length === 0, `chips=${JSON.stringify(s.chips)}`);
  check("7. 清空后提示条消失", s.tipShown === false);
  check("7. 清空后存储被移除", (await readKey(page)) === null);

  /* ---- 8. 退出登录：草稿必须一起清（换账号不串号） ---- */
  await page.locator(".ios-card >> nth=0 >> .ios-field--full input").fill("成都");
  await page.waitForTimeout(400);
  check("8. 退出前草稿已在存储中", (await readKey(page)) !== null);
  await page.locator(".nav-bar__logout").click();
  await page.waitForURL(/\/login/, { timeout: 15000 });
  check("8. 退出登录后草稿被清除", (await readKey(page)) === null);

  check("9. 全流程无页面级 JS 异常", errors.length === 0, errors.slice(0, 3).join(" || "));
} catch (e) {
  check("流程执行完整", false, String(e).slice(0, 500));
} finally {
  await browser.close();
}

console.log(`\nRESULT ${pass} PASS / ${fail} FAIL`);
process.exit(fail === 0 ? 0 : 1);
