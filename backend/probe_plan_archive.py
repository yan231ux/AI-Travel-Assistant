#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
采集方案存档复用探针（ReAct 优化批次 3）。

验证命题：同用户 + 同目的地 + 同偏好，**第二次生成是否沿用上次成功的采集方案**。
这正是"全自主"与"个性化可复现"冲突的解法：只自主一次 → 候选池稳定 → 排序结果可复现/可归因。

关键操作要点：
  内存 planCache（TTL 1h）会在同一进程内先命中，于是第二次生成会显示"计划缓存命中"而不是
  "存档复用"。要验证**持久化**存档（重启不丢、可解释、带复用计数），必须：
      Phase 1: 起后端 → 跑 first  → 归档写入 DB
      重启后端（清空内存 planCache）
      Phase 2: 起后端 → 跑 second → 应从 DB 命中并显示"沿用上次成功的采集方案"
  这也正是存档相对内存缓存的价值所在。

用法：
    python probe_plan_archive.py <base_url> <phase: first|second> [username]
例：
    python probe_plan_archive.py http://127.0.0.1:8081 first  archive_demo
    python probe_plan_archive.py http://127.0.0.1:8081 second archive_demo
"""
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import date, timedelta

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8081"
PHASE = sys.argv[2] if len(sys.argv) > 2 else "first"
USERNAME = sys.argv[3] if len(sys.argv) > 3 else "archive_demo"
PASSWORD = "Test123456"

# 日期保持在天气预报窗口内（~16 天）：复用验证不依赖"缺口补轮"，跑得更快更稳定
START = date.today() + timedelta(days=10)
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


def ensure_token():
    """注册并登录同一账号：已存在则直接登录（保证两阶段是同一个 user_id → 同一个 plan_key）"""
    st, reg = call("POST", "/auth/register",
                   body={"username": USERNAME, "password": PASSWORD, "nickname": "存档复用验证"})
    token = (reg or {}).get("token") if isinstance(reg, dict) else None
    print("注册: HTTP %s  token: %s" % (st, bool(token)))
    if token:
        return token
    st, login = call("POST", "/auth/login", body={"username": USERNAME, "password": PASSWORD})
    token = (login or {}).get("token") if isinstance(login, dict) else None
    print("登录: HTTP %s  token: %s" % (st, bool(token)))
    if not token:
        print("账号未就绪:", str(login)[:300])
    return token


def main():
    print("=" * 78)
    print("阶段 = %s   后端 = %s   用户 = %s   日期 = %s ~ %s"
          % (PHASE, BASE, USERNAME, START.isoformat(), END.isoformat()))
    print("=" * 78)

    token = ensure_token()
    if not token:
        return 1

    t0 = time.time()
    st, r = call("POST", "/trip/generate-with-trace", token=token, body={
        "destination": "北京",
        "start_date": START.isoformat(),
        "end_date": END.isoformat(),
        "travelers": 2,
        "budget": 5000,
        "pace": "适中",
        "preferences": ["自然风景", "拍照"],
    })
    cost = time.time() - t0

    if not isinstance(r, dict):
        print("响应不可解析:", str(r)[:400])
        return 1

    print("HTTP: %s   总耗时: %.1fs   success: %s" % (st, cost, r.get("success")))
    trace = r.get("trace") or []

    first_thought = None
    rounds = 0
    for step in trace:
        if step.get("action") != "plan_search":
            continue
        rounds += 1
        calls = step.get("tool_calls") or step.get("toolCalls") or []
        desc = " | ".join("%s(\"%s\")" % (c.get("tool"), c.get("query")) for c in calls)
        print("\n[第 %d 轮 plan_search]" % rounds)
        print("  thought : %s" % step.get("thought"))
        print("  calls   : %s" % (desc or "（无）"))
        if rounds == 1:
            first_thought = step.get("thought") or ""

    usage = r.get("token_usage") or {}
    prompt = usage.get("promptTokens", usage.get("prompt_tokens"))
    print("\nplan_search 轮数: %d" % rounds)
    print("think 阶段 token: prompt=%s completion=%s"
          % (prompt, usage.get("completionTokens", usage.get("completion_tokens"))))

    reused = bool(first_thought and "沿用上次成功的采集方案" in first_thought)
    print("\n是否复用存档方案: %s" % ("是" if reused else "否"))

    if PHASE == "first":
        expected = not reused
        print("阶段校验: %s（首次生成应为「否」= 走完整自主决策并归档）"
              % ("通过" if expected else "不通过"))
    else:
        print("阶段校验: %s（重启后第二次生成应为「是」= 命中持久化存档）"
              % ("通过" if reused else "不通过"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
