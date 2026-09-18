#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
安全探针：/admin/guides/{id}/reindex 的越权可达性

静态检查发现：该接口在 Controller 层无 requirePermission，所调用的 GuideIndexingService
也完全没有权限校验；而全局拦截器只校验"已登录"、不校验角色。
=> 预期：普通用户（USER 角色）也能命中该接口的业务逻辑。

实测用不存在的 id（999999）：命中业务逻辑会返回"攻略不存在"，而权限拦截应返回 403。
这样既证明越权，又完全不会触发 embedding 调用（零成本、零副作用）。
"""
import json
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"


def call(method, path, token=None, body=None):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, raw
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return 0, str(e)


# 1) 普通用户注册（默认角色 USER，无任何管理端权限）
uname = "gv_sec_%d" % int(time.time())
st, raw = call("POST", "/auth/register",
               body={"username": uname, "password": "Test123456", "nickname": "越权探针"})
reg = json.loads(raw)
user_token = reg.get("token")
print("普通用户注册:", st, "| role =", reg.get("user", {}).get("role"),
      "| permissions =", reg.get("user", {}).get("permissions"))
print()

# 2) 对照组：受权限保护的攻略接口，普通用户应被拒
st, raw = call("GET", "/admin/guides?page=1&pageSize=1", token=user_token)
print("GET  /admin/guides                (需 GUIDE_MANAGE) ->", st, "|", raw[:110])
st, raw = call("GET", "/admin/users?page=1&pageSize=1", token=user_token)
print("GET  /admin/users                 (需 USER_GOVERN)  ->", st, "|", raw[:110])
print()

# 3) 疑点：reindex 接口（不存在的 id，零成本）
st, raw = call("POST", "/admin/guides/999999/reindex", token=user_token)
print("POST /admin/guides/999999/reindex (需 GUIDE_MANAGE?) ->", st, "|", raw[:200])
print()
if st == 403:
    print(">>> 该接口有权限拦截（未见越权）")
else:
    print(">>> 越权成立：普通用户未被 403 拦截，请求已进入索引服务业务逻辑")

# 4) 顺带确认：同一个普通用户调用其它管理写接口是否被拦
st, raw = call("POST", "/admin/guides/999999/publish", token=user_token)
print("POST /admin/guides/999999/publish (对照，需 GUIDE_MANAGE) ->", st, "|", raw[:110])
st, raw = call("POST", "/admin/users/1/govern", token=user_token,
               body={"action": "SUSPEND", "reason": "x"})
print("POST /admin/users/1/govern        (对照，需 USER_GOVERN)  ->", st, "|", raw[:110])
