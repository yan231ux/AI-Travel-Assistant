# -*- coding: utf-8 -*-
"""缺口 B 验证探针：运营干预 meta 是否真的下发到用户端推荐接口。

背景：后端一直在 RecommendationFeed 里返回 interventions / featured_city / featured_reason，
但前端用户端从未消费（只有后台管理页用了），用户看不到"运营精选"。本次前端已接上，
本探针负责确认**接口侧确实带这些字段**，以及前端渲染所依赖的字段名/取值正确。

校验点：
  1. 置顶一个景点后，用户端 GET /recommendations/spots 的 interventions 含该 spot 且 action=PIN；
  2. 该景点确实排在第 1 位（排序层生效）；
  3. 给城市加 FEATURED 后，featured_city=true 且 featured_reason 有值；
  4. 清理后三个字段回到空/false（不残留）。

用法：python probe_ops_meta_visible.py
"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8080"
CITY = "三亚"
OK = "\u2705"
NG = "\u274c"
results = []


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


def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    print(("%s %s" % (OK if cond else NG, name)) + ("  -> " + detail if detail else ""))


def feed(token):
    st, b = call("GET", "/recommendations/spots?city=%s&sort=personalized&page=1&pageSize=12"
                 % urllib.parse.quote(CITY), token)
    return (b.get("data") or {})


def find_iv(admin, target_type, target_id, action):
    st, b = call("GET", "/admin/recommendations/interventions?scope=ALL&page=1&pageSize=100", admin)
    for it in ((b.get("data") or {}).get("items") or []):
        if (it.get("target_type") == target_type and it.get("target_id") == target_id
                and it.get("action") == action):
            return it.get("id")
    return None


def main():
    ts = str(int(time.time()))
    st, b = call("POST", "/auth/admin-login", body={"username": "admin", "password": "admin123"})
    admin = b.get("token")
    st, b = call("POST", "/auth/register",
                 body={"username": "opsdemo_" + ts, "password": "test1234", "nickname": "运营可见性演示"})
    viewer = b.get("token")
    if not admin or not viewer:
        print(NG, "登录失败")
        return 1
    call("PUT", "/user/profile/questionnaire", viewer, {"travelStyles": ["自然风景", "拍照打卡"]})

    base = feed(viewer)
    items = base.get("items") or []
    check("取到推荐列表", len(items) >= 6, "%d 条" % len(items))
    if len(items) < 6:
        return 1
    check("未干预时 interventions 为空", not base.get("interventions"),
          json.dumps(base.get("interventions"), ensure_ascii=False)[:120])
    check("未干预时 featured_city 非 true", base.get("featured_city") is not True,
          "featured_city=%s" % base.get("featured_city"))

    target = items[-1].get("spot_id")
    pin_id = feat_id = None
    try:
        call("POST", "/admin/recommendations/interventions", admin, {
            "target_type": "SPOT", "target_id": target, "action": "PIN",
            "reason": "编辑实测精选"})
        pin_id = find_iv(admin, "SPOT", target, "PIN")
        call("POST", "/admin/recommendations/interventions", admin, {
            "target_type": "CITY", "target_id": CITY, "action": "FEATURED",
            "reason": "本周三亚海岛主题精选"})
        feat_id = find_iv(admin, "CITY", CITY, "FEATURED")

        after = feed(viewer)
        ivs = after.get("interventions") or []
        pin_hit = next((m for m in ivs if m.get("spot_id") == target), None)
        check("interventions 含被置顶景点且 action=PIN",
              bool(pin_hit) and pin_hit.get("action") == "PIN",
              json.dumps(pin_hit, ensure_ascii=False))
        check("PIN 的 reason 随之下发（前端要拿它做「运营精选」说明）",
              bool(pin_hit) and pin_hit.get("reason") == "编辑实测精选",
              "reason=%s" % (pin_hit or {}).get("reason"))
        check("置顶景点排在第 1 位",
              (after.get("items") or [{}])[0].get("spot_id") == target,
              "第1位=%s" % (after.get("items") or [{}])[0].get("spot_id"))
        check("featured_city=true", after.get("featured_city") is True,
              "featured_city=%s" % after.get("featured_city"))
        check("featured_reason 随之下发", after.get("featured_reason") == "本周三亚海岛主题精选",
              "featured_reason=%s" % after.get("featured_reason"))
        # 字段名必须与前端 types 一致（snake_case），否则角标永远不出现
        check("字段名为 snake_case（frontend 消费一致）",
              "interventions" in after and "featured_city" in after and "featured_reason" in after,
              "keys=%s" % [k for k in after.keys() if k.startswith(("inter", "featured"))])
    finally:
        for iv in (pin_id, feat_id):
            if iv:
                call("DELETE", "/admin/recommendations/interventions/%s" % iv, admin)

    restored = feed(viewer)
    check("清理后 interventions 复位", not restored.get("interventions"),
          json.dumps(restored.get("interventions"), ensure_ascii=False)[:120])
    check("清理后 featured_city 复位", restored.get("featured_city") is not True,
          "featured_city=%s" % restored.get("featured_city"))

    passed = sum(1 for _, ok, _ in results if ok)
    print("\n==== 运营 meta 可见性探针: %d/%d 通过 ====" % (passed, len(results)))
    for name, ok, detail in results:
        if not ok:
            print("  FAIL: %s  %s" % (name, detail))
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
