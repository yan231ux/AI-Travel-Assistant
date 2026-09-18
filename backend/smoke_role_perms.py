#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
C 线冒烟：角色细化 + 权限点控制（真实 HTTP 端到端）
覆盖：登录/me 下发权限点、按域 403、角色分配护栏（未确认/无原因/非法码/改自己）、
      域隔离矩阵、冒烟自愈（测试用户角色还原为 USER）
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
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, (json.loads(raw) if raw.strip().startswith(("{", "[")) else raw)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw
    except Exception as e:  # 连接层错误
        return 0, str(e)


def check(label, cond, detail=""):
    global ok_cnt, fail_cnt
    if cond:
        ok_cnt += 1
        print("  [PASS] " + label)
    else:
        fail_cnt += 1
        print("  [FAIL] " + label + ("  -> " + str(detail)[:300] if detail else ""))


print("=== 1. 管理员登录：角色/权限点下发 ===")
st, resp = call("POST", "/auth/login", body={"username": "admin", "password": "admin123"})
check("admin 登录 200", st == 200, (st, resp))
admin_token = resp.get("token") if isinstance(resp, dict) else None
admin_user = (resp or {}).get("user", {}) if isinstance(resp, dict) else {}
check("登录响应含 role_label", bool(admin_user.get("role_label")), admin_user)
check("登录响应含 permissions 且非空", len(admin_user.get("permissions") or []) > 0, admin_user)
check("admin 已迁移为 SUPER_ADMIN（原 ADMIN）",
      admin_user.get("role") == "SUPER_ADMIN", admin_user.get("role"))

st, me = call("GET", "/auth/me", token=admin_token)
me_user = (me or {}).get("user", {}) if isinstance(me, dict) else {}
check("/auth/me 200 且含 permissions", st == 200 and len(me_user.get("permissions") or []) > 0, (st, me_user))

print("=== 2. 注册冒烟用户并分配 CONTENT_REVIEWER（带二次确认） ===")
uname = "gv_role_%d" % int(time.time())
st, reg = call("POST", "/auth/register",
               body={"username": uname, "password": "Test123456", "nickname": "冒烟角色用户"})
check("注册成功", st == 200 and isinstance(reg, dict), (st, reg))
target_id = (reg or {}).get("user", {}).get("id")
target_token = (reg or {}).get("token")
check("拿到目标用户 id", bool(target_id), reg)

# 关键：权限每次请求从库解析，因此"普通用户被拒"必须在提权之前验证
st, r = call("GET", "/admin/analytics/trend?days=7", token=target_token)
check("普通用户进不了后台 -> 403", st == 403, (st, str(r)[:200]))
st, r = call("GET", "/admin/users?page=1&pageSize=1", token=target_token)
check("普通用户进不了用户治理 -> 403", st == 403, (st, str(r)[:200]))

st, r = call("POST", "/admin/users/%s/role" % target_id, token=admin_token,
             body={"role": "CONTENT_REVIEWER", "reason": "冒烟测试：分配内容审核员", "confirm": True})
check("分配 CONTENT_REVIEWER 成功", st == 200 and isinstance(r, dict) and r.get("success") is True, (st, r))
check("返回 role_label=内容审核员", (r or {}).get("data", {}).get("role_label") == "内容审核员", r)

# 旧 token 无需重登：权限即时生效（角色从库实时解析），这也是"限制在真实链路即时生效"的同一机制
st, r = call("GET", "/admin/analytics/trend?days=7", token=target_token)
check("提权后旧 token 立即获得 ANALYTICS_VIEW -> 200", st == 200, (st, str(r)[:200]))

print("=== 3. 角色分配护栏（高风险操作） ===")
st, r = call("POST", "/admin/users/%s/role" % target_id, token=admin_token,
             body={"role": "CITY_EDITOR", "reason": "缺少确认", "confirm": False})
check("confirm=false 被拒", st >= 400, (st, r))

st, r = call("POST", "/admin/users/%s/role" % target_id, token=admin_token,
             body={"role": "CITY_EDITOR", "reason": "   ", "confirm": True})
check("原因空白被拒", st >= 400, (st, r))

st, r = call("POST", "/admin/users/%s/role" % target_id, token=admin_token,
             body={"role": "ADMIN", "reason": "历史角色码", "confirm": True})
check("历史值 ADMIN 不可分配", st >= 400, (st, r))

st, r = call("POST", "/admin/users/%s/role" % target_id, token=admin_token,
             body={"role": "SUPERUSER", "reason": "拼错角色码", "confirm": True})
check("未知角色码被拒", st >= 400, (st, r))

st, own = call("GET", "/auth/me", token=admin_token)
own_id = ((own or {}).get("user") or {}).get("id")
st, r = call("POST", "/admin/users/%s/role" % own_id, token=admin_token,
             body={"role": "USER", "reason": "自降级", "confirm": True})
check("不能修改自己的角色", st >= 400, (st, r))

print("=== 4. 审计留痕：user_role_assigned ===")
st, r = call("GET", "/admin/audit-logs?days=7&page=1&pageSize=50", token=admin_token)
check("审计查询 200", st == 200, (st, str(r)[:200]))
text = json.dumps(r, ensure_ascii=False) if isinstance(r, dict) else str(r)
check("审计含 user_role_assigned（说明角色分配被留痕）", "user_role_assigned" in text, text[:200])

print("=== 5. 以内容审核员登录：权限点 + 域隔离 ===")
st, lg = call("POST", "/auth/login", body={"username": uname, "password": "Test123456"})
rev_user = (lg or {}).get("user", {}) if isinstance(lg, dict) else {}
rev_token = (lg or {}).get("token") if isinstance(lg, dict) else None
perms = set(rev_user.get("permissions") or [])
check("审核员登录成功并带 token", st == 200 and bool(rev_token), (st, lg))
check("审核员权限=CONTENT_REVIEW+ANALYTICS_VIEW",
      perms == {"CONTENT_REVIEW", "ANALYTICS_VIEW"}, perms)

st, r = call("GET", "/admin/content/moderation?page=1&pageSize=1", token=rev_token)
check("审核员可进内容审核队列 (CONTENT_REVIEW) -> 200", st == 200, (st, str(r)[:200]))
st, r = call("GET", "/admin/analytics/trend?days=7", token=rev_token)
check("审核员可看看板 (ANALYTICS_VIEW) -> 200", st == 200, (st, str(r)[:200]))

st, r = call("GET", "/admin/guides?page=1&pageSize=1", token=rev_token)
check("审核员被拒攻略运营 (GUIDE_MANAGE) -> 403", st == 403, (st, str(r)[:200]))
st, r = call("GET", "/admin/users?page=1&pageSize=1", token=rev_token)
check("审核员被拒用户治理 (USER_GOVERN) -> 403", st == 403, (st, str(r)[:200]))
st, r = call("GET", "/admin/audit-logs?days=7", token=rev_token)
check("审核员被拒审计日志 (AUDIT_VIEW) -> 403", st == 403, (st, str(r)[:200]))
st, r = call("GET", "/admin/experiments", token=rev_token)
check("审核员被拒推荐运营 (RECOMMEND_OPS) -> 403", st == 403, (st, str(r)[:200]))

# 服务层同样按域收口：帖子审核队列 / 举报历史属于内容审核域
st, r = call("GET", "/admin/review/posts?page=1&pageSize=1", token=rev_token)
check("审核员可进帖子审核队列（服务层 CONTENT_REVIEW）-> 200", st == 200, (st, str(r)[:200]))

print("=== 6. 城市内容编辑：只剩攻略域 + 运营总览 ===")
st, r = call("POST", "/admin/users/%s/role" % target_id, token=admin_token,
             body={"role": "CITY_EDITOR", "reason": "冒烟：改为城市内容编辑", "confirm": True})
check("分配 CITY_EDITOR 成功", st == 200, (st, r))
st, r = call("GET", "/admin/guides?page=1&pageSize=1", token=rev_token)
check("编辑可进攻略运营 (GUIDE_MANAGE) -> 200", st == 200, (st, str(r)[:200]))
st, r = call("GET", "/admin/review/posts?page=1&pageSize=1", token=rev_token)
check("编辑被拒帖子审核队列 (CONTENT_REVIEW) -> 403", st == 403, (st, str(r)[:200]))
st, r = call("GET", "/admin/content/moderation?page=1&pageSize=1", token=rev_token)
check("编辑被拒 AI 审核队列 (CONTENT_REVIEW) -> 403", st == 403, (st, str(r)[:200]))
st, r = call("GET", "/admin/dashboard/summary", token=rev_token)
check("运营总览对任何管理端角色开放 -> 200", st == 200, (st, str(r)[:200]))

print("=== 7. 推荐运营：只剩推荐域 + 看板 ===")
st, r = call("POST", "/admin/users/%s/role" % target_id, token=admin_token,
             body={"role": "RECOMMENDATION_OPERATOR", "reason": "冒烟：改为推荐运营", "confirm": True})
check("分配 RECOMMENDATION_OPERATOR 成功", st == 200, (st, r))
st, r = call("GET", "/admin/experiments", token=rev_token)
check("运营可进推荐实验 (RECOMMEND_OPS) -> 200", st == 200, (st, str(r)[:200]))
st, r = call("GET", "/admin/guides?page=1&pageSize=1", token=rev_token)
check("运营被拒攻略域 (GUIDE_MANAGE) -> 403", st == 403, (st, str(r)[:200]))
st, r = call("GET", "/admin/audit-logs?days=7", token=rev_token)
check("运营被拒审计 (AUDIT_VIEW) -> 403", st == 403, (st, str(r)[:200]))

print("=== 8. 冒烟自愈：角色还原为 USER ===")
st, r = call("POST", "/admin/users/%s/role" % target_id, token=admin_token,
             body={"role": "USER", "reason": "冒烟测试结束，收回权限", "confirm": True})
check("还原为 USER 成功", st == 200, (st, r))
st, lg = call("POST", "/auth/login", body={"username": uname, "password": "Test123456"})
check("还原后无任何管理端权限",
      not (set(((lg or {}).get("user") or {}).get("permissions") or [])), lg)

print()
print("=" * 46)
print("冒烟结果：PASS=%d  FAIL=%d" % (ok_cnt, fail_cnt))
print("=" * 46)
