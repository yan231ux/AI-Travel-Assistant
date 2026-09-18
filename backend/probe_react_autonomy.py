#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
ReAct 决策模式对比探针：legacy（首轮 LLM 决策 + 规则补轮） vs autonomous（每轮 LLM 决策 + 失败回灌自纠）。

**为什么要用"超出天气预报窗口的日期"造缺口**：
Open-Meteo 只提供约 16 天预报窗口，日期超出后天气接口返回 400 → 天气数据为空 →
规则判定"数据不足" → **必然进入第 2 轮**。这样两种模式的补轮差异才能被稳定观察到。
（否则 qwen3-max 首轮常把工具选全，reflect 一轮就判足够，补轮逻辑压根不触发，差异看不见。）

**判读要点**：
  - 两个模式都应 success=true（天气缺失不阻断生成）
  - 第 2 轮 plan_search 的 thought：
      legacy     → "补充缺失数据: weather_forecast"（规则，query 与上次相同，注定再失败一次）
      autonomous → "LLM 基于缺口重新决策: ..."（模型换关键词/换工具）
  - 对比两者 think 阶段 token 与总耗时（autonomous 多一次 LLM 决策往返）

用法：
    python probe_react_autonomy.py <base_url> <label>
例：
    python probe_react_autonomy.py http://127.0.0.1:8080 legacy
    python probe_react_autonomy.py http://127.0.0.1:8081 autonomous
"""
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import date, timedelta

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"
LABEL = sys.argv[2] if len(sys.argv) > 2 else "legacy"

# 故意超出 Open-Meteo 的 ~16 天预报窗口 → 天气缺口 → 强制进入第 2 轮补轮
START = date.today() + timedelta(days=25)
END = START + timedelta(days=2)


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
    except Exception as e:
        return 0, str(e)


def main():
    print("=" * 78)
    print("模式 = %s   后端 = %s   日期 = %s ~ %s（故意超出天气预报窗口）"
          % (LABEL, BASE, START.isoformat(), END.isoformat()))
    print("=" * 78)

    ts = int(time.time())
    st, reg = call("POST", "/auth/register",
                   body={"username": "react_%s_%d" % (LABEL, ts), "password": "Test123456",
                         "nickname": "ReAct对比"})
    token = (reg or {}).get("token") if isinstance(reg, dict) else None
    print("注册: %s  token: %s" % (st, bool(token)))
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

    if not isinstance(r, dict):
        print("响应不可解析:", str(r)[:400])
        return 1

    print("HTTP: %s   总耗时: %.1fs   success: %s" % (st, cost, r.get("success")))
    trace = r.get("trace") or []
    print("trace 步数: %d" % len(trace))

    plan_rounds = 0
    for step in trace:
        action = step.get("action")
        if action == "plan_search":
            plan_rounds += 1
            calls = step.get("tool_calls") or step.get("toolCalls") or []
            desc = " | ".join("%s(\"%s\")" % (c.get("tool"), c.get("query")) for c in calls)
            print("\n[第 %d 轮 plan_search]" % plan_rounds)
            print("  thought : %s" % step.get("thought"))
            print("  calls   : %s" % (desc or "（无）"))
        elif action == "assess":
            print("[assess] %s" % str(step.get("thought"))[:160])

    print("\nplan_search 轮数: %d  （>=2 表示补轮确实发生）" % plan_rounds)
    usage = r.get("token_usage") or {}
    print("token: prompt=%s completion=%s planner=%s/%s"
          % (usage.get("promptTokens", usage.get("prompt_tokens")),
             usage.get("completionTokens", usage.get("completion_tokens")),
             usage.get("plannerPromptTokens"), usage.get("plannerCompletionTokens")))
    it = r.get("itinerary") or {}
    days = it.get("days") or [] if isinstance(it, dict) else []
    budget = it.get("estimatedBudget", it.get("estimated_budget")) if isinstance(it, dict) else None
    print("行程天数: %s   预算: %s" % (len(days), budget))
    errs = r.get("errors") or []
    if errs:
        print("errors: %s" % json.dumps(errs[:3], ensure_ascii=False))

    print("\n结论[%s]: %s" % (LABEL, "可用" if (r.get("success") and days) else "不可用"))
    return 0 if r.get("success") else 1


if __name__ == "__main__":
    raise SystemExit(main())
