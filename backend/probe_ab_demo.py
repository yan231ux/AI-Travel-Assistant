# -*- coding: utf-8 -*-
"""推荐 A/B 实验「演示数据」种子脚本（2026-09-21）。

要解决的演示困境：
    监控页的 A/B 效果对照要靠真实流量累积，而演示时没有真实用户。
    更致命的是——曝光只对"有 travel_style 画像的登录用户"写日志（personalized 分支），
    所以**对照组账号如果没填过画像，就永远不会产生曝光 → 对照组恒为空 → 演示不出对照**。
    （本库 2026-09 的实测：4 个 CONTROL 账号全是无画像的探针号，对照组 0 条曝光。）

本脚本做的事（全部走真实接口，不伪造任何数据）：
    1. 复用/创建一个 SPOT_FEED 的 ACTIVE 实验；
    2. 注册一批演示账号，**填入旅行风格画像**（这一步是让曝光能落库的前提）；
    3. 用与后端完全一致的 FNV-1a 分桶算法**本地预测**每个账号落 CONTROL 还是 TREATMENT，
       并打印实际分布（保证两组都有数据）；
    4. 每个账号真实浏览「为你推荐」（三亚/上海/北京 —— 有攻略池且质量混合的城市），
       产生带 ab_variant 的曝光日志；
    5. 给部分账号真实上报收藏/不感兴趣，填充反馈漏斗；
    6. 调管理端监控接口，打印「对照 vs 处理组」对照表。

用法：
    python probe_ab_demo.py            # 默认 12 个演示账号 × 3 城市
    python probe_ab_demo.py 20         # 自定义账号数

答辩演示建议：答辩前跑一次本脚本，打开 后台→推荐运营→推荐流监控（时间窗"近 1 天"），
即可看到对照/处理组两侧的曝光、命中率、收藏率对比。
"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8080"
OK = "\u2705"
NG = "\u274c"

# 演示城市：攻略池 ≥ MIN_QUALITY_POOL(6) 且质量混合（有 POI_ONLY 可被质量门过滤），
# 观察得到处理组"过滤后"的差异；纯 GUIDE 城市过滤前后一样，看不出效果。
DEMO_CITIES = ["三亚", "上海", "北京"]
DEMO_STYLES = ["自然风景", "拍照打卡", "城市漫游"]


def call(method, path, token=None, body=None):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body).encode("utf-8") if body is not None else None,
        method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


# ---------- 与后端 AbBucket.java 逐行对齐的确定性分桶 ----------

def fnv1a64(s):
    h = 0xCBF29CE484222325
    for ch in s:
        h ^= ord(ch)
        h = (h * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return h


def to_signed(x):
    return x - (1 << 64) if x >= (1 << 63) else x


def variant_of(user_id, exp_name, traffic, control):
    """返回 CONTROL / TREATMENT / None（不参与流量）。与 Java Math.floorMod 语义一致。"""
    if user_id is None or str(user_id).strip() == "":
        return None
    key = "%s#%s#traffic%d#control%d" % (user_id, exp_name, traffic, control)
    pct = to_signed(fnv1a64(key)) % 100  # Python % 即 floorMod
    if traffic <= 0 or pct >= traffic:
        return None
    cut = traffic * max(0, min(100, control)) // 100
    return "CONTROL" if pct < cut else "TREATMENT"


def reg_or_login(uname, pwd="test1234"):
    st, b = call("POST", "/auth/register",
                 body={"username": uname, "password": pwd, "nickname": "AB演示"})
    tok = b.get("token") or (b.get("data") or {}).get("token")
    uid = ((b.get("user") or (b.get("data") or {}).get("user")) or {}).get("id")
    if not tok:
        st, b = call("POST", "/auth/login", body={"username": uname, "password": pwd})
        tok = b.get("token") or (b.get("data") or {}).get("token")
        uid = ((b.get("user") or (b.get("data") or {}).get("user")) or {}).get("id")
    return tok, uid


def reset_demo_data():
    """清掉历史演示痕迹（只动 abdemo_% 账号自己的曝光/行为/分桶/账号，不碰真实数据）。

    答辩前跑一次，可让监控页只剩本次演示的干净读数。
    """
    import subprocess
    where = "user_id IN (SELECT id FROM users WHERE username LIKE 'abdemo_%')"
    sql = ("DELETE FROM spot_feed_log WHERE %s;"
           "DELETE FROM post_feed_log WHERE %s;"
           "DELETE FROM user_behavior WHERE %s;"
           "DELETE FROM ab_assignment WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'abdemo_%%');"
           "DELETE FROM user_preference WHERE %s;"
           "DELETE FROM user_profile WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'abdemo_%%');"
           "DELETE FROM users WHERE username LIKE 'abdemo_%%';" % (where, where, where, where))
    r = subprocess.run(["docker", "exec", "trip-planner-mysql", "mysql", "-uroot", "-proot",
                        "--default-character-set=utf8mb4", "-D", "trip_planner", "-e", sql],
                       capture_output=True)
    out = (r.stdout or b"").decode("utf-8", "replace") + (r.stderr or b"").decode("utf-8", "replace")
    print("已重置演示数据" if r.returncode == 0 else ("重置失败: " + out[:300]))


def ensure_experiment(admin):
    st, b = call("GET", "/admin/experiments", admin)
    items = b.get("items") or []
    for e in items:
        if e.get("feed_type") == "SPOT_FEED" and e.get("status") == "ACTIVE":
            return e
    name = "ab_demo_%d" % int(time.time())
    st, b = call("POST", "/admin/experiments", admin, {
        "name": name, "feedType": "SPOT_FEED", "strategy": "QUALITY_GATE",
        "trafficPercent": 100, "controlPercent": 50,
        "description": "演示用 A/B：攻略质量门",
    })
    return b.get("experiment") or {}


def main():
    args = [a for a in sys.argv[1:]]
    if "--reset" in args:
        reset_demo_data()
        args = [a for a in args if a != "--reset"]
    n_users = int(args[0]) if args else 12
    ts = str(int(time.time()))

    admin = None
    for path in ("/auth/admin-login", "/auth/login"):
        st, b = call("POST", path, body={"username": "admin", "password": "admin123"})
        admin = b.get("token") or (b.get("data") or {}).get("token")
        if admin:
            break
    if not admin:
        print(NG, "admin 登录失败，无法读取监控接口")
        return 1

    exp = ensure_experiment(admin)
    exp_name = exp.get("name")
    traffic = exp.get("traffic_percent", 100)
    control = exp.get("control_percent", 50)
    print("实验: %s  feed=%s  traffic=%s%%  control=%s%%  status=%s"
          % (exp_name, exp.get("feed_type"), traffic, control, exp.get("status")))

    # 均衡分组：每组只要 target 人。注册→本地预测变体，某组满员就不再给它喂数据，
    # 这样对照/处理两组人数对称，演示时读数可比（不会出现 9:3 这种一边倒）。
    target = max(1, n_users // 2)
    buckets = {"CONTROL": [], "TREATMENT": []}
    cap = n_users * 4
    i = 0
    while i < cap and (len(buckets["CONTROL"]) < target or len(buckets["TREATMENT"]) < target):
        uname = "abdemo_%s_%03d" % (ts, i)
        i += 1
        tok, uid = reg_or_login(uname)
        if not tok or uid is None:
            print(NG, "账号 %s 注册/登录失败" % uname)
            continue
        # 关键：没有 travel_style 画像就不会写曝光（personalized=false），
        # 对照组没画像 = 对照组永远空白，这正是演示失败的根因。
        call("PUT", "/user/profile/questionnaire", tok,
             {"travelStyles": DEMO_STYLES, "pace": "适中"})

        v = variant_of(str(uid), exp_name, traffic, control)
        if v not in buckets or len(buckets[v]) >= target:
            continue  # 不参与流量 / 该组已满 → 这个账号不喂数据（留着无害）
        buckets[v].append((uname, uid, tok))

        # 真实浏览「为你推荐」→ 写带 ab_variant 的曝光日志
        for city in DEMO_CITIES:
            call("GET", "/recommendations/spots?city=%s&sort=personalized&page=1&pageSize=12"
                 % urllib.parse.quote(city), tok)

        # 每个账号都上报行为 → 保证两组都有反馈数据（只有一组有反馈演示不出对比）；
        # 每 3 个账号再多一条不感兴趣，让负反馈率不为空。
        # 注意：推荐接口返回的是 snake_case（spot_id/poi_id），不是驼峰。
        st, feed = call("GET", "/recommendations/spots?city=%s&sort=personalized&page=1&pageSize=12"
                        % urllib.parse.quote(DEMO_CITIES[0]), tok)
        items = (((feed.get("data") or {}).get("items")) or [])

        def item_key(it):
            return it.get("poi_id") or it.get("spot_id")

        for it in items[:2]:
            call("POST", "/user/behavior", tok, {
                "itemType": "SPOT", "itemId": item_key(it),
                "itemName": it.get("name"), "actionType": "SAVE"})
        if i % 3 == 0 and len(items) >= 4:
            it = items[-1]
            call("POST", "/user/behavior", tok, {
                "itemType": "SPOT", "itemId": item_key(it),
                "itemName": it.get("name"), "actionType": "DISLIKE"})

    print("\n实际分桶（本地按后端同款哈希预测，应与服务端一致）：")
    print("  CONTROL   %d 人: %s" % (len(buckets["CONTROL"]),
                                     ", ".join(u for u, _, _ in buckets["CONTROL"])))
    print("  TREATMENT %d 人: %s" % (len(buckets["TREATMENT"]),
                                     ", ".join(u for u, _, _ in buckets["TREATMENT"])))

    # ---------- 读监控：对照 vs 处理组 ----------
    st, mon = call("GET", "/admin/feed-monitor?days=1", admin)
    feeds = mon.get("feeds") or {}
    spot = feeds.get("SPOT_FEED") or {}
    vm = spot.get("by_variant_metrics") or {}
    print("\n==== 推荐景点流 · A/B 效果对照（近 1 天） ====")
    print("%-12s %8s %10s %10s %10s" % ("分组", "曝光", "命中率", "收藏率", "负反馈率"))
    label = {"CONTROL": "对照(现状)", "TREATMENT": "处理组(新策略)", "NONE": "无实验"}
    for k in ("CONTROL", "TREATMENT", "NONE"):
        if k not in vm:
            continue
        r = vm[k]
        print("%-12s %8s %9s%% %9s%% %9s%%"
              % (label[k], r["exposures"], r["hit_rate"], r["save_rate"], r["dislike_rate"]))
    print("（全站汇总）曝光 %s / 命中率 %s%% / 收藏率 %s%% / 负反馈率 %s%%"
          % (spot.get("exposures"), spot.get("hit_rate"),
             spot.get("save_rate"), spot.get("dislike_rate")))

    ok = ("CONTROL" in vm and "TREATMENT" in vm
          and vm["CONTROL"]["exposures"] > 0 and vm["TREATMENT"]["exposures"] > 0)
    print("\n%s 两组是否都有曝光（能否演示对照）: %s"
          % (OK if ok else NG, "是" if ok else "否"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
