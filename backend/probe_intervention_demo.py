# -*- coding: utf-8 -*-
"""人工干预效果端到端探针：证明「后台置顶/降权」真的改变用户端推荐顺序。

为什么单独做这条：A/B 效果对照要靠曝光/行为数据累积，而**人工干预是秒级可见的**——
运营在后台把一个景点置顶，用户端「为你推荐」刷新后它就到最前。答辩里这是最稳的
"能演示"路径（不依赖任何流量）。本探针全程走真实接口，验证完自动清理干预。

用法：python probe_intervention_demo.py
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


def feed_order(token):
    st, b = call("GET", "/recommendations/spots?city=%s&sort=personalized&page=1&pageSize=12"
                 % urllib.parse.quote(CITY), token)
    items = (((b.get("data") or {}).get("items")) or [])
    return [it.get("spot_id") for it in items]


def find_iv_id(admin, target_id, action):
    """保存接口不回传 id → 用列表接口按 (对象, 动作) 回捞，保证探针一定能自清理。"""
    st, b = call("GET", "/admin/recommendations/interventions?scope=ALL&page=1&pageSize=100", admin)
    for it in ((b.get("data") or {}).get("items") or []):
        if it.get("target_id") == target_id and it.get("action") == action:
            return it.get("id")
    return None


def main():
    ts = str(int(time.time()))
    # 登录：管理员 + 普通用户
    st, b = call("POST", "/auth/admin-login", body={"username": "admin", "password": "admin123"})
    admin = b.get("token")
    if not admin:
        st, b = call("POST", "/auth/login", body={"username": "admin", "password": "admin123"})
        admin = b.get("token")
    st, b = call("POST", "/auth/register",
                 body={"username": "ivdemo_" + ts, "password": "test1234", "nickname": "干预演示"})
    viewer = b.get("token")
    if not admin or not viewer:
        print(NG, "登录失败 admin=%s viewer=%s" % (bool(admin), bool(viewer)))
        return 1
    # 有 travel_style 画像才会走个性化推荐（干预作用于该排序层）
    call("PUT", "/user/profile/questionnaire", viewer, {"travelStyles": ["自然风景", "拍照打卡"]})

    before = feed_order(viewer)
    check("取到推荐列表（%s）" % CITY, len(before) >= 6, "%d 条" % len(before))
    if len(before) < 6:
        return 1
    print("  干预前顺序:", before[:6], "...")

    target = before[-1]  # 原本排最后的景点
    st, b = call("POST", "/admin/recommendations/interventions", admin, {
        "target_type": "SPOT", "target_id": target, "action": "PIN",
        "reason": "演示：运营置顶验证",
    })
    iv_id = find_iv_id(admin, target, "PIN")
    check("后台置顶该景点", st == 200 and iv_id is not None, "target=%s id=%s" % (target, iv_id))

    after_pin = feed_order(viewer)
    check("用户端刷新后该景点升到第 1 位", after_pin and after_pin[0] == target,
          "第1位=%s（原本第%d位）" % (after_pin[0] if after_pin else None, len(before)))

    # 降权同一条：先删置顶，再加降权
    if iv_id:
        call("DELETE", "/admin/recommendations/interventions/%s" % iv_id, admin)
    st, b = call("POST", "/admin/recommendations/interventions", admin, {
        "target_type": "SPOT", "target_id": before[0], "action": "DEMOTE",
        "reason": "演示：运营降权验证",
    })
    demote_id = find_iv_id(admin, before[0], "DEMOTE")
    after_demote = feed_order(viewer)
    check("降权原第 1 位景点后其不再居首",
          after_demote and after_demote[0] != before[0],
          "新第1位=%s（被降权的是 %s）" % (after_demote[0] if after_demote else None, before[0]))

    # 清理：移除演示干预，恢复算法排序
    if demote_id:
        call("DELETE", "/admin/recommendations/interventions/%s" % demote_id, admin)
    restored = feed_order(viewer)
    check("移除干预后恢复算法排序（原第 1 位回到首位）",
          restored and restored[0] == before[0],
          "第1位=%s" % (restored[0] if restored else None))

    passed = sum(1 for _, ok, _ in results if ok)
    print("\n==== 人工干预效果探针: %d/%d 通过 ====" % (passed, len(results)))
    for name, ok, detail in results:
        if not ok:
            print("  FAIL: %s  %s" % (name, detail))
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
