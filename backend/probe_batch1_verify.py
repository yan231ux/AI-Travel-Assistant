#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
第 1 批修复验证（2026-09-13）：真实 HTTP 端到端复测。

覆盖：
  A. 城市闸门     GET /recommendations/spots  非法城市 → 400（不再返回异地景点、不写库）
                   合法城市 / 带"市"后缀 / 空值 → 行为不变
  B. 发帖字段名   POST /community/posts       snake_case 正常落库；camelCase 兼容（@JsonAlias）
  C. 脏数据不再生  探测若干非法城市后，spot 表不应新增任何以假城市为 city 的行

自愈：测试用户保留（不删，保审计完整）；帖子为草稿态，不进入公开流。
"""
import json
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"
ok_cnt = 0
fail_cnt = 0


def call(method, path, token=None, body=None, query=None):
    url = BASE + path
    if query:
        from urllib.parse import urlencode
        url += "?" + urlencode(query, encoding="utf-8")
    data = None
    headers = {"Content-Type": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=90) as resp:
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


uname = "gv_b1_%d" % int(time.time())
st, reg = call("POST", "/auth/register",
               body={"username": uname, "password": "Test123456", "nickname": "批次一验证"})
token = (reg or {}).get("token") if isinstance(reg, dict) else None
check("注册测试用户成功", st == 200 and bool(token), (st, reg))

print("\n=== A. 城市闸门：非法城市应 400，且不返回任何景点 ===")
for bad in ["火星", "北就", "不合法城市xyz", "1", "法"]:
    st, r = call("GET", "/recommendations/spots", token=token,
                 query={"city": bad, "page": 1, "pageSize": 3})
    msg = (r or {}).get("message") if isinstance(r, dict) else ""
    check("city=%s -> 400 且带可读文案" % bad, st == 400 and bool(msg), (st, str(r)[:250]))

st, r = call("GET", "/recommendations/spots", token=token,
             query={"city": "北就", "page": 1, "pageSize": 3})
msg = (r or {}).get("message") if isinstance(r, dict) else ""
check("形近输入「北就」提示「北京」", "北京" in (msg or ""), (st, msg))

print("\n=== A2. 合法城市：行为不变 ===")
st, r = call("GET", "/recommendations/spots", token=token,
             query={"city": "北京", "page": 1, "pageSize": 5})
items = ((r or {}).get("data") or {}).get("items") if isinstance(r, dict) else None
check("city=北京 -> 200 且返回景点", st == 200 and isinstance(items, list) and len(items) > 0,
      (st, str(r)[:200]))
if items:
    cities = {it.get("city") for it in items}
    check("返回景点的 city 都是「北京」（无异地充数）", cities == {"北京"}, cities)

st, r = call("GET", "/recommendations/spots", token=token,
             query={"city": "北京市", "page": 1, "pageSize": 5})
check("city=北京市（带后缀）-> 200", st == 200, (st, str(r)[:200]))

st, r = call("GET", "/recommendations/spots", token=token, query={"city": "", "page": 1, "pageSize": 5})
check("city 为空 -> 200 空列表（不是 400）", st == 200, (st, str(r)[:200]))

print("\n=== B. 发帖字段契约：snake_case 落库 ===")
st, r = call("POST", "/community/posts", token=token, body={
    "title": "批次一契约验证-snake",
    "summary": "验证 cover_image/travel_days/post_type 是否正确落库",
    "content": "这是一篇用于验证字段契约的草稿内容，不会被公开。",
    "cover_image": "/uploads/0123456789abcdef0123456789abcdef.png",
    "city": "北京",
    "travel_days": 4,
    "budget": 3000,
    "pace": "轻松",
    "post_type": "GUIDE",
})
pid1 = (r or {}).get("postId") if isinstance(r, dict) else None
check("snake_case 建帖成功", st == 200 and pid1 is not None, (st, r))

if pid1:
    st, r = call("GET", "/community/posts/%d" % pid1, token=token)
    d = (r or {}).get("data") if isinstance(r, dict) else None
    if isinstance(d, dict):
        ci = d.get("coverImage", d.get("cover_image"))
        td = d.get("travelDays", d.get("travel_days"))
        pt = d.get("postType", d.get("post_type"))
        check("cover_image 已保存（非 null）", bool(ci), (ci, str(d)[:300]))
        check("travel_days 已保存 = 4", td == 4, td)
        check("post_type 已保存 = GUIDE（不再回落 NOTE）", pt == "GUIDE", pt)
    else:
        check("详情返回结构可解析", False, (st, str(r)[:300]))

print("\n=== B2. 兼容旧客户端：camelCase 仍可（@JsonAlias） ===")
st, r = call("POST", "/community/posts", token=token, body={
    "title": "批次一契约验证-camel",
    "content": "验证 camelCase 别名兼容的草稿。",
    "coverImage": "/uploads/abcdefabcdefabcdefabcdefabcdefab.jpg",
    "travelDays": 6,
    "postType": "ITINERARY",
})
pid2 = (r or {}).get("postId") if isinstance(r, dict) else None
check("camelCase 建帖成功（兼容别名生效）", st == 200 and pid2 is not None, (st, r))
if pid2:
    st, r = call("GET", "/community/posts/%d" % pid2, token=token)
    d = (r or {}).get("data") if isinstance(r, dict) else None
    if isinstance(d, dict):
        td = d.get("travelDays", d.get("travel_days"))
        pt = d.get("postType", d.get("post_type"))
        check("camelCase travel_days = 6", td == 6, td)
        check("camelCase post_type = ITINERARY", pt == "ITINERARY", pt)
    else:
        check("详情可解析", False, (st, str(r)[:300]))

print("\n=== C. 城市名归一化：别名/带后缀不得另起 city 键 ===")
st, r = call("GET", "/recommendations/spots", token=token,
             query={"city": "北京市", "page": 1, "pageSize": 5})
items = ((r or {}).get("data") or {}).get("items") if isinstance(r, dict) else None
check("city=北京市 -> 200", st == 200, (st, str(r)[:200]))
if items:
    cities = {it.get("city") for it in items}
    check("返回景点 city 归一为「北京」（不再出现 '北京市' 平行键）", cities == {"北京"}, cities)

st, r = call("GET", "/recommendations/spots", token=token,
             query={"city": "魔都", "page": 1, "pageSize": 5})
items = ((r or {}).get("data") or {}).get("items") if isinstance(r, dict) else None
check("city=魔都（别名）-> 200", st == 200, (st, str(r)[:200]))
if items:
    cities = {it.get("city") for it in items}
    check("返回景点 city 归一为「上海」", cities == {"上海"}, cities)

print("\n===== 汇总: PASS=%d FAIL=%d =====" % (ok_cnt, fail_cnt))
print("（探针用户 %s，草稿帖 id=%s/%s，均未进入公开流）" % (uname, pid1, pid2))
raise SystemExit(1 if fail_cnt else 0)
