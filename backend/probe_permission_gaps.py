#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
C 线权限审查探针：验证两类"权限点被绕过/错配"的疑点（真实 HTTP，跑完自愈还原角色）

疑点 A：/admin/analytics/recompute 是【写操作】（重算预聚合），却挂在 ANALYTICS_VIEW 下 ——
        该权限点被设计成"只读看板，所有管理端角色都可看"，于是审核员也能触发写操作。
疑点 B：/admin/dashboard/summary 只要"属于管理端"即可访问，但它返回 recent_ops（审计日志最新 10 条，
        含 detail）—— 而审计查询 /admin/audit-logs 明确要求 AUDIT_VIEW（仅超管）。
        同一份信息，一个入口 403，另一个入口能拿到 => AUDIT_VIEW 被绕过。
"""
import json
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"


def call(method, path, token=None, body=None):
    data = None
    headers = {"Content-Type": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
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


st, resp = call("POST", "/auth/login", body={"username": "admin", "password": "admin123"})
admin_token = (resp or {}).get("token")
print("admin 登录:", st)

uname = "gv_probe_%d" % int(time.time())
st, reg = call("POST", "/auth/register",
               body={"username": uname, "password": "Test123456", "nickname": "权限探针"})
uid = (reg or {}).get("user", {}).get("id")
st, r = call("POST", "/admin/users/%s/role" % uid, token=admin_token,
             body={"role": "CONTENT_REVIEWER", "reason": "权限审查探针", "confirm": True})
print("提为内容审核员:", st, (r or {}).get("data", {}).get("role_label"))

st, lg = call("POST", "/auth/login", body={"username": uname, "password": "Test123456"})
tok = (lg or {}).get("token")
print("审核员 token 拿到:", bool(tok))
print()

print("---------- 疑点 A：只读角色能否触发写操作 ----------")
st, r = call("GET", "/admin/analytics/trend?days=7", token=tok)
print("GET  /admin/analytics/trend      (读, ANALYTICS_VIEW) ->", st)
st, r = call("POST", "/admin/analytics/recompute?days=1", token=tok)
verdict_a = st == 200
print("POST /admin/analytics/recompute  (写, ANALYTICS_VIEW) ->", st,
      "  <== 写操作被放行" if verdict_a else "")
if st == 200 and isinstance(r, dict):
    print("     响应:", {k: r.get(k) for k in ("days", "recomputed_dimensions", "expected_dimensions")})
print()

print("---------- 疑点 B：AUDIT_VIEW 是否被 dashboard 绕过 ----------")
st, r = call("GET", "/admin/audit-logs?days=7", token=tok)
print("GET  /admin/audit-logs           (需 AUDIT_VIEW, 仅超管) ->", st, "（应 403）")
st, r = call("GET", "/admin/dashboard/summary", token=tok)
# 注意：summary 响应为 {success, data:{...}}，recent_ops 在 data 里
data = (r or {}).get("data") if isinstance(r, dict) else None
ops = (data or {}).get("recent_ops") if isinstance(data, dict) else None
verdict_b = bool(ops)
print("GET  /admin/dashboard/summary    (仅需管理端身份) ->", st,
      "  <== 绕过" if verdict_b else "")
if ops:
    print("     recent_ops 条数:", len(ops))
    for x in ops[:3]:
        print("       -", x.get("actor"), "|", x.get("action"), "|", x.get("category"),
              "| detail:", str(x.get("detail"))[:80])
print()

print("---------- 自愈：还原角色 ----------")
st, r = call("POST", "/admin/users/%s/role" % uid, token=admin_token,
             body={"role": "USER", "reason": "探针结束收回权限", "confirm": True})
print("还原为 USER:", st)

print()
if verdict_a:
    print(">>> 疑点 A 成立：ANALYTICS_VIEW（只读）能触发 recompute 写操作")
if verdict_b:
    print(">>> 疑点 B 成立：dashboard summary 泄露审计近况，AUDIT_VIEW 可被绕过")
if not verdict_a and not verdict_b:
    print(">>> 两个疑点均未复现")
