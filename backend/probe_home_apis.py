#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""首页三块（为你推荐 / 大家最近在规划 / 社区攻略）接口探针：看真实 HTTP 状态与响应体。"""
import json
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
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode("utf-8", "replace")
            return r.status, raw
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return 0, str(e)


st, raw = call("POST", "/auth/login", body={"username": "admin", "password": "admin123"})
tok = None
try:
    tok = json.loads(raw).get("token")
except Exception:
    pass
print("登录: %s | token=%s" % (st, "有" if tok else "无"))
if not tok:
    print("登录失败响应:", raw[:300])
    raise SystemExit(1)

TARGETS = [
    ("为你推荐", "GET", "/recommendations/spots?city=%E5%8C%97%E4%BA%AC&page=1&pageSize=6&trace=probe1"),
    ("大家最近在规划", "GET", "/home/trending-spots?days=7&limit=6"),
    ("社区攻略", "GET", "/community/posts?page=1&pageSize=6&sort=latest"),
    ("画像摘要", "GET", "/user/profile/summary"),
]
for name, method, path in TARGETS:
    st, raw = call(method, path, token=tok)
    print("\n--- %s  %s %s ---" % (name, method, path))
    print("HTTP %s" % st)
    print(raw[:600])
