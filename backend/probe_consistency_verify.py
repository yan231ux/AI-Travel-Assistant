#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
生成后一致性修复验收探针（2026-09-18，三亚案例评审批次）。

复现用户报的三亚 5 天 / ¥8000 场景，然后按"页面自洽"逐条核对：

  ① 住宿口径统一：tips / 每日 notes 里不得再出现 LLM 写的住宿金额或档次断言
     （原先"高档型…780 元/晚，4 晚 3120 元，占预算 39%" 与预算明细的 ¥200/晚、10% 两套数字打架）；
     并且必须有一条系统按最终数据重述的「住宿：…」事实行。
  ② 交通段端点对齐：每一段的起点/终点要么是机场/车站这类中转枢纽，
     要么能在**当天**真实落地的地点（酒店/景点/餐厅）里找到同名或互相包含的名字——
     不允许再出现"当过 4 次端点、却从来不是任何一餐"的幽灵餐厅。
  ③ 每日餐次完整：除最后一天外，每天都要有午餐和晚餐；最后一天至少午餐。
  ④ 简介/图片串用：不同景点不得共用同一段简介。

判读：全部 PASS → 本批次修复在真实链路上生效；任一条 FAIL 会打印反例原文。
"""
import json
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import date, timedelta

BASE = "http://127.0.0.1:8080"
START = date.today() + timedelta(days=7)
END = START + timedelta(days=4)  # 5 天行程

TRANSIT_HUBS = ("机场", "火车站", "高铁站", "动车站", "地铁站", "汽车站", "客运站",
                "码头", "港口", "轮渡", "口岸", "服务区")
LODGING_WORD = re.compile(r"(酒店|住宿|房价|房费|公寓|民宿|客栈|旅舍|青旅|招待所)")
MONEY_CLAIM = re.compile(r"(\d+\s*(元|万)|[¥￥]\s*\d+)")
LODGING_LEVEL = re.compile(r"(高档|豪华|舒适型|经济型|轻奢)")

FAILS = []


def norm(s):
    if not s:
        return ""
    t = re.sub(r"[\s\u3000]", "", str(s))
    for suf in ("风景区", "景区", "游览区", "公园", "广场", "旅游区"):
        if len(t) > len(suf) and t.endswith(suf):
            return t[: -len(suf)]
    return t


def same_place(a, b):
    na, nb = norm(a), norm(b)
    if not na or not nb:
        return False
    if na == nb:
        return True
    return min(len(na), len(nb)) >= 2 and (na in nb or nb in na)


def check(cond, label, detail=""):
    print(("  PASS  " if cond else "  FAIL  ") + label + ("" if cond else "  ← " + str(detail)))
    if not cond:
        FAILS.append(label)


def call(method, path, token=None, body=None):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=900) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, (json.loads(raw) if raw.strip().startswith(("{", "[")) else raw)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw
    except Exception as e:  # noqa: BLE001
        return 0, str(e)


def main():
    ts = int(time.time())
    st, reg = call("POST", "/auth/register",
                   body={"username": "gv_cons_%d" % ts, "password": "Test123456", "nickname": "一致性验收"})
    token = (reg or {}).get("token") if isinstance(reg, dict) else None
    print("注册:", st, "token:", bool(token))
    if not token:
        print("账号未就绪:", str(reg)[:300])
        return 1

    t0 = time.time()
    st, r = call("POST", "/trip/generate-with-trace", token=token, body={
        "destination": "三亚",
        "start_date": START.isoformat(),
        "end_date": END.isoformat(),
        "travelers": 2,
        "budget": 8000,
        "pace": "适中",
        "preferences": ["自然风景", "拍照"],
    })
    print("HTTP: %s  耗时: %.1fs" % (st, time.time() - t0))
    if not isinstance(r, dict):
        print("响应不可解析:", str(r)[:400])
        return 1
    print("success:", r.get("success"), "| errors:", r.get("errors"))
    it = r.get("itinerary") or {}
    days = it.get("days") or []
    if not (r.get("success") and days):
        print("生成未成功，无法验收")
        return 1

    tips = it.get("tips") or []
    notes = it.get("source_notes") or []
    print("\n--- tips(%d) ---" % len(tips))
    for t in tips:
        print("   ", t)
    print("--- source_notes(%d) ---" % len(notes))
    for n in notes:
        print("   ", n)

    print("\n================ ① 住宿口径统一 ================")
    stale = [t for t in list(tips) + [n for d in days for n in (d.get("notes") or [])]
             if t and LODGING_WORD.search(t) and (MONEY_CLAIM.search(t) or LODGING_LEVEL.search(t))
             and not str(t).startswith("住宿：")]
    check(not stale, "住宿金额/档次断言已清空", stale)
    sys_tip = [t for t in tips if t and str(t).startswith("住宿：")]
    check(bool(sys_tip), "存在系统按最终数据重述的住宿事实行", tips)

    print("\n================ ② 交通段端点对齐 ================")
    bad = []
    for d in days:
        anchors = []
        h = d.get("hotel") or {}
        if h.get("name"):
            anchors.append(h["name"])
        for s in d.get("spots") or []:
            if s.get("name"):
                anchors.append(s["name"])
        for m in d.get("meals") or []:
            if m.get("name"):
                anchors.append(m["name"])
        for t in d.get("transport") or []:
            for key in ("from_place", "to_place"):
                p = t.get(key)
                if not p or not str(p).strip():
                    continue
                if any(w in str(p) for w in TRANSIT_HUBS):
                    continue
                if any(same_place(p, a) for a in anchors):
                    continue
                bad.append((d.get("day_index"), key, p, anchors))
    check(not bad, "没有端点落空的交通段", bad[:4])

    print("\n================ ③ 每日餐次完整 ================")
    miss = []
    for i, d in enumerate(days):
        meals = d.get("meals") or []
        types = [str(m.get("meal_type") or "") for m in meals]
        has_lunch = any(("午" in t or "中" in t) for t in types)
        has_dinner = any("晚" in t for t in types)
        day_notes = " ".join(d.get("notes") or [])
        last = (i == len(days) - 1)
        if not has_lunch and "午餐" not in day_notes:
            miss.append((d.get("day_index"), "缺午餐", types))
        if not last and not has_dinner and "晚餐" not in day_notes:
            miss.append((d.get("day_index"), "缺晚餐", types))
    check(not miss, "每天午/晚餐齐全（最后一天不要求晚餐）", miss)

    print("\n================ ④ 简介不得跨景点复用 ================")
    seen = {}
    dup = []
    for d in days:
        for s in d.get("spots") or []:
            desc = norm(s.get("description"))
            if len(desc) < 40:
                continue
            if desc in seen and not same_place(s.get("name"), seen[desc]):
                dup.append((s.get("name"), seen[desc]))
            seen.setdefault(desc, s.get("name"))
    check(not dup, "没有整段复用的简介", dup)

    print("\n================ 逐日明细 ================")
    for d in days:
        print("第%s天 景点=%s 餐=%s 交通=%s" % (
            d.get("day_index"),
            [s.get("name") for s in (d.get("spots") or [])],
            [(m.get("meal_type"), m.get("name")) for m in (d.get("meals") or [])],
            [(t.get("from_place"), t.get("to_place")) for t in (d.get("transport") or [])]))
        dist = [(t.get("distance_km")) for t in (d.get("transport") or [])]
        if dist:
            print("     距离字段: %s（前端只在全部有值时才显示「合计」）" % dist)
        for n in d.get("notes") or []:
            print("     note:", n)

    print("\n结论:", "全部通过" if not FAILS else "存在未通过项：" + "；".join(FAILS))
    return 0 if not FAILS else 2


if __name__ == "__main__":
    raise SystemExit(main())
