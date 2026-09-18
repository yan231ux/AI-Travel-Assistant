#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
行程生成冒烟探针：换模型 / 换 Key 后必须先跑这个。

为什么需要单独探它：**模型可用 ≠ 模型能生成行程**。
2026-09-13 实测踩过——把模型换成 qwen3.8-max 后，内容审核完全正常（6~16s），
但整份行程 JSON 一次生成输出量太大，加上推理链，直接顶到
`ItineraryGenerator.GENERATION_TIMEOUT_SECONDS`(180s) 而失败，接口返回
`success:false / 生成行程超时，请稍后重试`。

判读要点：
  success=true 且 days>=1        → 可用（实测 qwen3-max 约 90s、qwen-turbo 更快）
  success=false + "生成行程超时" → 模型太慢（推理模型基本都会踩），换非推理模型
  success=false + 其他文案      → 看 errors 首条定位（Key 无效 / 模型名不存在等）

注意：日期取**未来 16 天内**。Open-Meteo 只提供约 16 天预报窗口，
超出会返回 400（`start_date is out of allowed range`），天气模块降级——
虽不阻断生成，但会刷一堆 ERROR 日志干扰判断。
"""
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import date, timedelta

BASE = "http://127.0.0.1:8080"

# 未来 16 天内（Open-Meteo 预报窗口），避免天气接口 400 干扰判读
START = date.today() + timedelta(days=7)
END = START + timedelta(days=2)


def call(method, path, token=None, body=None):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=600) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, (json.loads(raw) if raw.strip().startswith(("{", "[")) else raw)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw
    except Exception as e:
        return 0, str(e)


def main():
    ts = int(time.time())
    st, reg = call("POST", "/auth/register",
                   body={"username": "gv_gen_%d" % ts, "password": "Test123456", "nickname": "生成冒烟"})
    token = (reg or {}).get("token") if isinstance(reg, dict) else None
    print("注册:", st, "拿到 token:", bool(token))
    if not token:
        print("账号未就绪，终止:", str(reg)[:300])
        return 1

    t0 = time.time()
    st, r = call("POST", "/trip/generate-with-trace", token=token, body={
        "destination": "北京",
        "start_date": START.isoformat(),
        "end_date": END.isoformat(),
        "travelers": 2,
        "budget": 5000,
        "pace": "适中",
        "preferences": ["历史文化", "美食"],
    })
    cost = time.time() - t0
    print("HTTP: %s  耗时: %.1fs" % (st, cost))

    if not isinstance(r, dict):
        print("响应不可解析:", str(r)[:400])
        return 1

    ok = bool(r.get("success"))
    print("success:", ok)
    print("errors:", r.get("errors"))
    trace = r.get("trace") or []
    print("trace 步数:", len(trace) if isinstance(trace, list) else trace)

    it = r.get("itinerary") or {}
    if isinstance(it, dict) and it:
        days = it.get("days") or []
        print("destination:", it.get("destination"), "| 天数:", len(days) if isinstance(days, list) else days)
        if isinstance(days, list) and days:
            for d in days[:3]:
                spots = (d or {}).get("spots") or []
                print("  第%s天 %s 主题=%s 景点数=%s" % (
                    d.get("day_index"), d.get("date"), d.get("theme"),
                    len(spots) if isinstance(spots, list) else spots))
        print("预算:", it.get("estimated_budget"), "| token_usage:", it.get("token_usage"))
    else:
        print("itinerary 为空（生成未完成）")

    print("\n结论:", "可用" if (ok and isinstance(it, dict) and it.get("days")) else "不可用 —— 见上面 errors")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
