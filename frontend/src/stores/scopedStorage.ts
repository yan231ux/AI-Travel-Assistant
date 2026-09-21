/**
 * 按账号隔离的「两级本地持久化」工具。
 *
 * 场景：用户在页面上辛苦填好 / 生成出来的东西，不该因为"切到别的页面再回来"就没了。
 * 两级的分工：
 *   ① sessionStorage（会话级）：同一标签页内跳页面、F5 刷新都在 —— 快路径，也最不容易过期；
 *   ② localStorage（持久级）：关掉标签页、新开标签页、浏览器重启后仍在 —— 补会话级的缺口。
 * 读的时候会话级优先，命中持久级则顺手回写一份会话级，后续读走快路径、两边口径一致。
 *
 * ⚠️ 持久级必须按账号隔离：localStorage 是跨标签页、跨会话共享的，若不记"主人"，
 * 同一个浏览器上换个账号登录就会直接看到上一个账号的行程/草稿（隐私事故）。
 * 因此写入时带上当前登录账号 id（ownerId），读取时校验"就是当前这个人"，
 * 不一致（或当前未登录）一律当没有，并且顺手把这份别人的数据清掉。
 *
 * 过期策略：持久级带 savedAt + 保鲜期，过期即丢 —— 用户口径是"除非退出账号才丢"，
 * 但不该让几个月前的一份残留数据在下次打开时突然诈尸。
 *
 * 一切操作都做了异常兜底：隐私模式 / 存储被禁用 / 超配额时静默降级为"不持久化"，
 * 绝不让存储问题打断页面正常渲染。
 */

/** 与 services/api.ts 写登录态用的是同一个键（这里只读，用于给持久数据定主人） */
const USER_KEY = "ai_travel_user";

/** 持久级默认保鲜期（7 天） */
export const SCOPED_TTL_MS = 7 * 24 * 60 * 60 * 1000;

/** 持久级的信封：谁存的、什么时候存的、存了什么 */
interface ScopedEnvelope {
  ownerId: string;
  savedAt: number;
  data: unknown;
}

/**
 * 当前登录账号 id（读 localStorage 里的登录态，模块加载期即可用、无需等待接口返回）。
 * 拿不到（未登录 / 老会话字段缺失）时返回空串，调用方按"没有主人"处理。
 */
export function currentOwnerId(): string {
  try {
    const raw = localStorage.getItem(USER_KEY);
    if (!raw) return "";
    const u = JSON.parse(raw) as { id?: string | number } | null;
    if (!u || u.id === undefined || u.id === null) return "";
    return String(u.id);
  } catch {
    return "";
  }
}

/** 读持久级：只认"同一个账号 + 没过期"的那份，其余一律当没有（并顺手清掉） */
function readScoped(key: string, ttlMs: number): unknown {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return null;
    const env = JSON.parse(raw) as ScopedEnvelope | null;
    if (!env || typeof env !== "object") return null;
    const me = currentOwnerId();
    const expired = Date.now() - (env.savedAt ?? 0) > ttlMs;
    if (!me || env.ownerId !== me || expired) {
      localStorage.removeItem(key);
      return null;
    }
    return env.data ?? null;
  } catch {
    return null;
  }
}

/** 写持久级：先清旧数据或直接拒绝 */
function writeScoped(key: string, data: unknown): void {
  try {
    const ownerId = currentOwnerId();
    if (!ownerId) {
      // 没有主人就不留痕：宁可不持久化，也不冒"被别人看到"的风险
      localStorage.removeItem(key);
      return;
    }
    const env: ScopedEnvelope = { ownerId, savedAt: Date.now(), data };
    localStorage.setItem(key, JSON.stringify(env));
  } catch {
    /* 存储不可用/超配额：降级为不持久化 */
  }
}

function clearScoped(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    /* ignore */
  }
}

/**
 * 两级读取。`validate` 负责把 unknown 校正成可信对象（各调用方结构不同、校验逻辑也不同），
 * 返回 null 视为"这份数据不能用"，会话级会清掉、持久级也会清掉。
 */
export function readTwoTier<T>(
  sessionKey: string,
  durableKey: string,
  validate: (raw: unknown) => T | null,
  ttlMs: number = SCOPED_TTL_MS
): T | null {
  // ① 会话级优先
  try {
    const raw = sessionStorage.getItem(sessionKey);
    if (raw) {
      const value = validate(JSON.parse(raw));
      if (value !== null) return value;
      sessionStorage.removeItem(sessionKey);
    }
  } catch {
    /* 存储不可用 → 直接看持久级 */
  }

  // ② 持久级兜底（新标签页 / 关掉重开 / 浏览器重启之后走到这里）
  const data = readScoped(durableKey, ttlMs);
  if (data === null || data === undefined) return null;
  const value = validate(data);
  if (value === null) {
    clearScoped(durableKey);
    return null;
  }
  try {
    sessionStorage.setItem(sessionKey, JSON.stringify(data));
  } catch {
    /* 回写失败无所谓：下次读还是走持久级 */
  }
  return value;
}

/** 两级写入（会话级 + 持久级） */
export function writeTwoTier<T>(sessionKey: string, durableKey: string, data: T): void {
  try {
    sessionStorage.setItem(sessionKey, JSON.stringify(data));
  } catch {
    /* 会话级写不进去不影响持久级 */
  }
  writeScoped(durableKey, data);
}

/** 两级清空（退出登录 / 手动清空） */
export function clearTwoTier(sessionKey: string, durableKey: string): void {
  try {
    sessionStorage.removeItem(sessionKey);
  } catch {
    /* ignore */
  }
  clearScoped(durableKey);
}
