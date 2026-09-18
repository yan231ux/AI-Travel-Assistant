#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
第 4 批验证（2026-09-13）：真实 HTTP 端到端复测。

覆盖：
  A. 自己的帖子不能「不感兴趣」：作者对本人帖子点 dislike → 403；他人可正常点
  B. 管理员独立登录入口：
     - POST /auth/admin-login 用管理员账号 → 200（token + role 属管理端）
     - POST /auth/admin-login 用普通用户账号 → 403「该账号不是管理员」
     - POST /auth/admin-login 用错误密码 → 401
  C. 管理员只进后台（前端路由守卫已做，此处只验证后端契约：管理端 role 正常下发）

自愈：测试用户保留（不删，保审计完整）；A 段用的帖子由管理员在末尾隐藏。
"""
import json
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8080"
ok_cnt = 0
fail_cnt = 0

_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def enc(s):
    return urllib.parse.quote(str(s), safe="")


def call(method, path, token=None, body=None, query=None):
    url = BASE + path
    if query:
        url += "?" + urllib.parse.urlencode(query, encoding="utf-8")
    data = None
    headers = {"Content-Type": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with _OPENER.open(req, timeout=90) as resp:
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
        print("  [FAIL] " + label + ("  -> " + str(detail)[:400] if detail else ""))


def register(tag):
    uname = "gv_b4_%s_%d" % (tag, int(time.time() * 1000) % 100000000)
    st, r = call("POST", "/auth/register",
                 body={"username": uname, "password": "Test123456", "nickname": "批次四" + tag})
    return uname, (r or {}).get("token") if isinstance(r, dict) else None, st, r


# ============================================================
print("=== 准备：管理员 + 两个测试用户 ===")
st, r = call("POST", "/auth/login", body={"username": "admin", "password": "admin123"})
admin_token = (r or {}).get("token") if isinstance(r, dict) else None
admin_role = ((r or {}).get("user") or {}).get("role") if isinstance(r, dict) else None
check("管理员（普通登录口）登录 200，role 属管理端",
      st == 200 and bool(admin_token) and admin_role in
      ("SUPER_ADMIN", "ADMIN", "CONTENT_REVIEWER", "CITY_EDITOR", "RECOMMENDATION_OPERATOR"),
      (st, admin_role))

_, tok_author, sa, ra = register("author")   # 发帖作者
_, tok_other, sb, rb = register("other")     # 他人
check("两个测试用户注册成功", all(t for t in (tok_author, tok_other)), (sa, sb))

# ============================================================
print("\n=== A. 自己的帖子不能「不感兴趣」 ===")
st, r = call("POST", "/community/posts", token=tok_author, body={
    "title": "批次四验证-自己帖子不感兴趣（验证后隐藏）",
    "summary": "验证作者不能对自己帖子点不感兴趣",
    "content": "这是一篇用于验证互动门禁的测试内容，验证结束会被管理员隐藏。",
    "city": "北京",
    "post_type": "NOTE",
})
pid = (r or {}).get("postId") if isinstance(r, dict) else None
check("作者建帖成功", st == 200 and pid is not None, (st, r))

st, r = call("POST", "/community/posts/%d/submit" % pid, token=tok_author)
check("提交审核", st == 200, (st, r))
st, r = call("POST", "/community/moderation/posts/%d/approve" % pid, token=admin_token)
check("管理员通过 → PUBLISHED", st == 200, (st, r))

# 作者对自己帖子点不感兴趣 → 403
st, r = call("POST", "/community/posts/%d/dislike" % pid, token=tok_author)
check("作者对自己帖子点「不感兴趣」→ 403", st == 403, (st, str(r)[:200]))

# 他人可正常点不感兴趣
st, r = call("POST", "/community/posts/%d/dislike" % pid, token=tok_other)
check("他人对帖子点「不感兴趣」→ 200（正常放行）", st == 200, (st, str(r)[:200]))

# ============================================================
print("\n=== B. 管理员独立登录入口 ===")
st, r = call("POST", "/auth/admin-login", body={"username": "admin", "password": "admin123"})
at = (r or {}).get("token") if isinstance(r, dict) else None
ar = ((r or {}).get("user") or {}).get("role") if isinstance(r, dict) else None
check("管理员入口登录管理员账号 → 200 + token",
      st == 200 and bool(at), (st, str(r)[:200]))
check("返回 role 属管理端", ar in
      ("SUPER_ADMIN", "ADMIN", "CONTENT_REVIEWER", "CITY_EDITOR", "RECOMMENDATION_OPERATOR"), ar)

st, r = call("POST", "/auth/admin-login", body={"username": "admin", "password": "wrongpass"})
check("管理员入口错误密码 → 401", st == 401, (st, str(r)[:200]))

# 普通用户走管理员入口 → 403
fixed_u = "gv_b4_plain_%d" % (int(time.time() * 1000) % 100000000)
st, r = call("POST", "/auth/register",
             body={"username": fixed_u, "password": "Test123456", "nickname": "普通用户"})
st2, r2 = call("POST", "/auth/admin-login",
               body={"username": fixed_u, "password": "Test123456"})
check("普通用户走管理员入口 → 403「该账号不是管理员」",
      st2 == 403 and "不是管理员" in str(r2), (st2, str(r2)[:200]))

# ============================================================
print("\n=== C. 管理员只进后台（后端契约：role 正常下发，前端路由据此分流） ===")
st, r = call("GET", "/auth/me", token=admin_token)
me_role = ((r or {}).get("user") or {}).get("role") if isinstance(r, dict) else None
check("管理员 /auth/me 返回 role 属管理端", st == 200 and me_role in
      ("SUPER_ADMIN", "ADMIN", "CONTENT_REVIEWER", "CITY_EDITOR", "RECOMMENDATION_OPERATOR"), (st, me_role))

# ============================================================
print("\n=== 收尾：隐藏验证帖 ===")
st, r = call("POST", "/community/moderation/posts/%d/hide" % pid, token=admin_token)
check("管理员隐藏验证帖 → 200", st == 200, (st, str(r)[:200]))

print("\n" + "=" * 60)
print("结果：%d PASS / %d FAIL" % (ok_cnt, fail_cnt))
print("=" * 60)
raise SystemExit(1 if fail_cnt else 0)
