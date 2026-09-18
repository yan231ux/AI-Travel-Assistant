#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
自检修复验证：针对三处越权缺陷的真实 HTTP 端到端复测。

覆盖：
  Fix C(原 P0)  POST /admin/guides/{id}/reindex      必须 GUIDE_MANAGE；普通用户/审核员 403（原来能进业务逻辑）
  Fix A(原缺陷) POST /admin/analytics/recompute      写操作，仅 SUPER_ADMIN；审核员 403（原来 200 真重算）
  Fix B(原旁路) GET  /admin/dashboard/summary        无 AUDIT_VIEW 时 recent_ops 整体隐藏（原来泄露审计明细）

自愈：测试用户角色最终还原为 USER，审计保留。
"""
import json
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"
ok_cnt = 0
fail_cnt = 0


def call(method, path, token=None, body=None):
    url = BASE + path
    data = None
    headers = {"Content-Type": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
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


def check(label, cond, detail=""):
    global ok_cnt, fail_cnt
    if cond:
        ok_cnt += 1
        print("  [PASS] " + label)
    else:
        fail_cnt += 1
        print("  [FAIL] " + label + ("  -> " + str(detail)[:300] if detail else ""))


def assign(admin_token, uid, role, reason):
    return call("POST", "/admin/users/%s/role" % uid, token=admin_token,
                body={"role": role, "reason": reason, "confirm": True})


st, resp = call("POST", "/auth/login", body={"username": "admin", "password": "admin123"})
admin_token = (resp or {}).get("token") if isinstance(resp, dict) else None
check("管理员登录 200", st == 200 and bool(admin_token), (st, resp))

uname = "gv_fix_%d" % int(time.time())
st, reg = call("POST", "/auth/register",
               body={"username": uname, "password": "Test123456", "nickname": "修复验证用户"})
uid = ((reg or {}).get("user") or {}).get("id") if isinstance(reg, dict) else None
token = (reg or {}).get("token") if isinstance(reg, dict) else None
check("注册普通用户成功", st == 200 and bool(uid) and bool(token), (st, reg))

print("\n=== Fix C：reindex 越权（原 P0，普通用户能进索引业务逻辑） ===")
st, r = call("POST", "/admin/guides/999999/reindex", token=token)
check("普通用户 reindex -> 403（原来 400『攻略不存在』= 已进业务逻辑）",
      st == 403, (st, str(r)[:200]))
st, r = call("GET", "/admin/guides?page=1&pageSize=1", token=token)
check("对照：普通用户攻略列表 -> 403", st == 403, (st, str(r)[:200]))

print("\n=== 提权为内容审核员（CONTENT_REVIEW，用于验证 Fix A/B） ===")
st, r = assign(admin_token, uid, "CONTENT_REVIEWER", "修复验证：内容审核员")
check("分配 CONTENT_REVIEWER 成功", st == 200, (st, r))

print("\n=== Fix A：recompute 写操作挂只读权限（原来审核员能触发全量重算） ===")
st, r = call("POST", "/admin/analytics/recompute?days=1", token=token)
check("审核员 recompute -> 403（原来 200 并真的重算）", st == 403, (st, str(r)[:200]))
check("403 文案指明仅超管", "超级管理员" in json.dumps(r, ensure_ascii=False), r)
st, r = call("GET", "/admin/analytics/trend?days=7", token=token)
check("对照：审核员只读看板仍 200（没误伤只读能力）", st == 200, (st, str(r)[:200]))

print("\n=== Fix B：看板 recent_ops 绕过 AUDIT_VIEW（原来泄露审计明细） ===")
st, r = call("GET", "/admin/dashboard/summary", token=token)
data = (r or {}).get("data", {}) if isinstance(r, dict) else {}
check("审核员可进运营总览 -> 200", st == 200, (st, str(r)[:200]))
check("recent_ops_visible=false", data.get("recent_ops_visible") is False, data.get("recent_ops_visible"))
check("recent_ops 为空（不泄露任何审计条目）", data.get("recent_ops") == [], data.get("recent_ops"))
check("不是伪装成故障：errors 为空", not data.get("errors"), data.get("errors"))
st, r2 = call("GET", "/admin/audit-logs?days=7", token=token)
check("对照：审核员独立审计接口仍 403（口径一致）", st == 403, (st, str(r2)[:200]))

print("\n=== Fix C 反向验证：有 GUIDE_MANAGE 的角色不应被误伤 ===")
st, r = assign(admin_token, uid, "CITY_EDITOR", "修复验证：城市内容编辑")
check("分配 CITY_EDITOR 成功", st == 200, (st, r))
st, r = call("POST", "/admin/guides/999999/reindex", token=token)
check("编辑 reindex 不存在的攻略 -> 400『攻略不存在』（权限通过、业务执行）",
      st == 400, (st, str(r)[:200]))
st, r = call("GET", "/admin/guides?page=1&pageSize=1", token=token)
check("编辑可进攻略运营 -> 200", st == 200, (st, str(r)[:200]))

print("\n=== 超管自身不被误伤 ===")
st, r = call("GET", "/admin/dashboard/summary", token=admin_token)
adata = (r or {}).get("data", {}) if isinstance(r, dict) else {}
check("超管 recent_ops_visible=true", st == 200 and adata.get("recent_ops_visible") is True,
      (st, adata.get("recent_ops_visible")))

print("\n=== 自愈：角色还原为 USER ===")
st, r = assign(admin_token, uid, "USER", "修复验证结束，收回权限")
check("还原为 USER 成功", st == 200, (st, r))
st, lg = call("POST", "/auth/login", body={"username": uname, "password": "Test123456"})
check("还原后无任何管理端权限",
      not (set(((lg or {}).get("user") or {}).get("permissions") or [])), lg)

print()
print("=" * 46)
print("修复验证：PASS=%d  FAIL=%d" % (ok_cnt, fail_cnt))
print("=" * 46)
