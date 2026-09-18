#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
第 3 批验证（2026-09-11）：真实 HTTP 端到端复测。

覆盖三项已批准改动：
  A. 评论删除权限三方化  评论作者 / 帖子作者（楼主治理自己帖子下的评论）/ 管理员
                        —— 列表项下发 can_delete，删除写审计（以什么身份删的）
  B. 画像可撤销（收藏）  取消收藏回退 UNSAVE -0.10，|回退| < |收藏 +0.15|；
                        无对应权重行不新建、不发散；幂等空删不扣分
  C. 画像可撤销（问卷）  列表传空数组 / 单值传空串 = 主动清空该域；未提交的域保持现状
  D. 省级脏数据已清      spot.city='新疆' 16 行 + post_spot 悬空引用 已删除 → 景点 404、城市闸门 400

自愈：测试用户保留（不删，保审计完整）；A 段验证用的已发布帖子在末尾由管理员隐藏，
      不污染公开流；不新建任何公开内容。
"""
import json
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8080"
ok_cnt = 0
fail_cnt = 0

# ⚠️ 沙箱 shell 里存在 http_proxy/https_proxy 环境变量（指向本机代理端口）。
# urllib 默认会读它 → 请求被转发到代理 → 连本地后端报 502「upstream connect failed」。
# 显式装一个不使用任何代理的 opener，保证探针在任何环境都能直连 127.0.0.1。
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
    uname = "gv_b3_%s_%d" % (tag, int(time.time() * 1000) % 100000000)
    st, r = call("POST", "/auth/register",
                 body={"username": uname, "password": "Test123456", "nickname": "批次三" + tag})
    return uname, (r or {}).get("token") if isinstance(r, dict) else None, st, r


# ============================================================
print("=== 准备：管理员 + 三个测试用户 ===")
st, resp = call("POST", "/auth/login", body={"username": "admin", "password": "admin123"})
admin_token = (resp or {}).get("token") if isinstance(resp, dict) else None
check("管理员登录 200", st == 200 and bool(admin_token), (st, resp))

_, tok_owner, st_a, ra = register("owner")   # 楼主 A
_, tok_cmt, st_b, rb = register("cmt")       # 评论者 B
_, tok_stranger, st_c, rc = register("str")  # 路人 C
check("三个测试用户注册成功", all(t for t in (tok_owner, tok_cmt, tok_stranger)), (st_a, st_b, st_c))

# ============================================================
print("\n=== A. 评论删除权限：评论作者 / 楼主 / 管理员 ===")
st, r = call("POST", "/community/posts", token=tok_owner, body={
    "title": "批次三验证-评论权限（验证后自动隐藏）",
    "summary": "验证楼主可删自己帖子下的评论",
    "content": "这是一篇用于验证评论删除权限的测试内容，验证结束后会被管理员隐藏，不会留在公开流。",
    "city": "北京",
    "post_type": "NOTE",
})
pid = (r or {}).get("postId") if isinstance(r, dict) else None
check("楼主建帖成功", st == 200 and pid is not None, (st, r))

st, r = call("POST", "/community/posts/%d/submit" % pid, token=tok_owner)
check("提交审核", st == 200, (st, r))
st, r = call("POST", "/community/moderation/posts/%d/approve" % pid, token=admin_token)
check("管理员通过 → 帖子 PUBLISHED（评论才可发）", st == 200, (st, r))

st, r = call("POST", "/community/posts/%d/comments" % pid, token=tok_cmt,
             body={"content": "楼主的攻略很有用，谢谢分享！"})
cid = ((r or {}).get("data") or {}).get("id") if isinstance(r, dict) else None
check("评论者 B 发表评论成功", st == 200 and cid is not None, (st, r))

# can_delete 按访问者身份下发
st, r = call("GET", "/community/posts/%d/comments" % pid, token=tok_stranger)
items = (r or {}).get("items") or []
it = next((x for x in items if x.get("id") == cid), None)
check("路人 C 看到评论且 can_delete=false", st == 200 and it is not None
      and it.get("can_delete") is False and it.get("mine") is False, (st, it))

st, r = call("GET", "/community/posts/%d/comments" % pid, token=tok_cmt)
it = next((x for x in ((r or {}).get("items") or []) if x.get("id") == cid), None)
check("评论作者 B 看到 can_delete=true（mine=true）", it is not None
      and it.get("can_delete") is True and it.get("mine") is True, (st, it))

st, r = call("GET", "/community/posts/%d/comments" % pid, token=tok_owner)
it = next((x for x in ((r or {}).get("items") or []) if x.get("id") == cid), None)
check("楼主 A 看到他人评论 can_delete=true（mine=false）", it is not None
      and it.get("can_delete") is True and it.get("mine") is False, (st, it))

# 无权限者不能删
st, r = call("DELETE", "/community/comments/%d" % cid, token=tok_stranger)
check("路人 C 删除他人评论 → 403", st == 403, (st, str(r)[:200]))

# 楼主删他人评论（本轮新增权限）
st, r = call("DELETE", "/community/comments/%d" % cid, token=tok_owner)
check("楼主 A 删除 B 的评论 → 200", st == 200, (st, str(r)[:200]))
st, r = call("GET", "/community/posts/%d/comments" % pid, token=tok_owner)
it = next((x for x in ((r or {}).get("items") or []) if x.get("id") == cid), None)
check("评论已软删（deleted=true + 占位文案 + can_delete=false）",
      it is not None and it.get("deleted") is True
      and it.get("content") == "评论已删除" and it.get("can_delete") is False, (st, it))

# 审计留痕
st, r = call("GET", "/admin/audit-logs", token=admin_token,
             query={"category": "SOCIAL", "days": 1, "page": 1, "pageSize": 50})
rows = ((r or {}).get("items") or []) if isinstance(r, dict) else []
hit = next((x for x in rows if x.get("action") == "comment_deleted"
            and str(x.get("target_id")) == str(cid)), None)
check("审计留痕 comment_deleted（CAT_SOCIAL）存在", st == 200 and hit is not None,
      (st, str(rows)[:300]))
if hit:
    detail = hit.get("detail") or ""
    check("审计记录「以什么身份删的」= post_author", "post_author" in detail, detail)
    check("审计记录了评论作者与楼主", "comment_author" in detail and "post_author" in detail, detail)

# 评论作者删自己的评论仍可用（原有能力不回归）
st, r = call("POST", "/community/posts/%d/comments" % pid, token=tok_cmt,
             body={"content": "第二条评论：验证评论作者仍可删自己的评论。"})
cid2 = ((r or {}).get("data") or {}).get("id") if isinstance(r, dict) else None
st2, r2 = call("DELETE", "/community/comments/%d" % cid2, token=tok_cmt)
check("评论作者仍可删自己的评论 → 200（不回归）", cid2 is not None and st2 == 200, (st2, r2))

# ============================================================
print("\n=== B. 画像可撤销：取消收藏回退（幅度 < 增加） ===")
_, tok_rev, _, _ = register("rev")
st, r = call("GET", "/recommendations/spots", token=tok_rev,
             query={"city": "北京", "page": 1, "pageSize": 3})
spots = ((r or {}).get("data") or {}).get("items") or []
check("取到北京景点（用于收藏）", st == 200 and len(spots) > 0, (st, str(r)[:200]))
sid = spots[0].get("spot_id") if spots else None
print("      使用景点: %s (%s)" % (sid, spots[0].get("name") if spots else ""))

st, r = call("POST", "/spots/%s/favorite" % enc(sid), token=tok_rev)
fav_adj = (r or {}).get("adjustments") or []
check("收藏成功且返回画像调整", st == 200 and len(fav_adj) > 0, (st, str(r)[:250]))
save_deltas = [round(a.get("delta", 0), 4) for a in fav_adj]
check("收藏增量为 +0.15（新行 0.5+0.15=0.65）",
      all(abs(d - 0.15) < 1e-6 for d in save_deltas) and
      all(abs(a.get("weight", 0) - 0.65) < 1e-6 for a in fav_adj), fav_adj)

st, r = call("DELETE", "/spots/%s/favorite" % enc(sid), token=tok_rev)
unf_adj = (r or {}).get("adjustments") or []
check("取消收藏成功且返回画像回退", st == 200 and len(unf_adj) > 0, (st, str(r)[:250]))
unf_deltas = [round(a.get("delta", 0), 4) for a in unf_adj]
check("回退增量为 -0.10（0.65 → 0.55）",
      all(abs(d + 0.10) < 1e-6 for d in unf_deltas) and
      all(abs(a.get("weight", 0) - 0.55) < 1e-6 for a in unf_adj), unf_adj)
check("关键约束：|回退| < |增加|（0.10 < 0.15）",
      all(abs(d) < 0.15 for d in unf_deltas), unf_deltas)
check("净加成小于一次收藏（0.55-0.5=0.05 < 0.15）",
      all(abs(a.get("weight", 0) - 0.5) < 0.15 for a in unf_adj), unf_adj)

# 幂等：未收藏再取消 → 无调整、不退分
st, r = call("DELETE", "/spots/%s/favorite" % enc(sid), token=tok_rev)
check("重复取消收藏幂等（adjustments 为空，不重复扣分）",
      st == 200 and (r or {}).get("adjustments") == [], (st, str(r)[:250]))

st, r = call("GET", "/user/profile", token=tok_rev)
prefs = (r or {}).get("preferences") or []
tag0 = unf_adj[0].get("tag") if unf_adj else None
row = next((p for p in prefs if p.get("tag") == tag0), None)
check("画像读回权重落库为 0.55（不是 0.65 也不是 0.5）",
      row is not None and abs((row or {}).get("weight", 0) - 0.55) < 1e-6, (tag0, row))

# 撤销不清白"真的不喜欢"的负偏好：对同标签点不感兴趣后再收藏/取消
print("\n=== B2. 撤销的边界：不制造 / 不加深负偏好（POSITIVE_WEIGHT_MIN 地板） ===")
st, r = call("POST", "/user/behavior", token=tok_rev, body={
    "actionType": "DISLIKE", "itemType": "SPOT",
    "itemId": sid, "itemName": spots[0].get("name"),
    "poiType": spots[0].get("category"),
    "reason": "TYPE",
})
dis_adj = (r or {}).get("adjustments") or []
check("对同标签点不感兴趣（reason=TYPE）→ 权重被压低到 ≤0.30",
      st == 200 and len(dis_adj) > 0 and all(a.get("delta", 0) < 0 for a in dis_adj)
      and all(a.get("weight", 1) <= 0.30 + 1e-6 for a in dis_adj), (st, str(r)[:250]))
low = min([a.get("weight", 0) for a in dis_adj] or [None])
print("      压低后权重: %s" % low)

st, r = call("POST", "/spots/%s/favorite" % enc(sid), token=tok_rev)
check("再收藏 → 权重正常回升 +0.15",
      st == 200 and all(abs(a.get("delta", 0) - 0.15) < 1e-6 for a in (r or {}).get("adjustments") or []),
      (st, str(r)[:250]))

st, r = call("DELETE", "/spots/%s/favorite" % enc(sid), token=tok_rev)
unf2 = (r or {}).get("adjustments") or []
check("临界取消收藏：撤销被 0.45 地板挡住（停在 0.40，不深挖到 0.30）",
      st == 200 and len(unf2) > 0
      and all(abs(a.get("delta", 1)) < 1e-6 for a in unf2)
      and all(abs(a.get("weight", 0) - 0.40) < 1e-6 for a in unf2), (st, unf2))
check("撤销没有把权重压回「回避区」反而制造更深的负偏好",
      all(a.get("weight", 0) >= min(0.40, low if low is not None else 1) - 1e-6 for a in unf2),
      (low, unf2))

# ============================================================
print("\n=== C. 画像可撤销：问卷清空（空数组 / 空串 = 主动清空） ===")
_, tok_q, _, _ = register("q")
st, r = call("PUT", "/user/profile/questionnaire", token=tok_q, body={
    "travelStyles": ["自然风景", "历史文化"],
    "pace": "紧凑",
    "foodPreferences": ["火锅"],
})
prof = (r or {}).get("profile") or {}
check("首次提交问卷成功（风格/节奏/口味均写入）",
      st == 200 and prof.get("travelStyles") == "自然风景,历史文化"
      and prof.get("pacePreference") == "紧凑" and prof.get("foodPreferences") == "火锅",
      (st, prof))

st, r = call("PUT", "/user/profile/questionnaire", token=tok_q, body={
    "travelStyles": [],   # 主动清空
    "pace": "",           # 主动清空
    # foodPreferences 不提交 → 应保持现状
})
prof2 = (r or {}).get("profile") or {}
check("清空：旅行风格被撤销（空数组 → null）", st == 200 and not prof2.get("travelStyles"),
      (st, prof2))
check("清空：节奏被撤销（空串 → null）", not prof2.get("pacePreference"), prof2)
check("未提交的域保持现状（口味仍是火锅）", prof2.get("foodPreferences") == "火锅", prof2)

st, r = call("GET", "/user/profile", token=tok_q)
prefs = (r or {}).get("preferences") or []
check("明细同步：问卷来源行只剩「口味」域（风格/节奏已清除）",
      st == 200 and all(p.get("category") != "travel_style" for p in prefs)
      and all(p.get("category") != "pace" for p in prefs), prefs)

# ============================================================
print("\n=== D. 省级脏数据已清（spot.city='新疆' 16 行 + 悬空引用） ===")
st, r = call("GET", "/spots/%s" % enc("spot_新疆_B03DF0262E"), token=tok_rev)
check("已删景点 spot_新疆_B03DF0262E → 404", st == 404, (st, str(r)[:200]))
st, r = call("GET", "/recommendations/spots", token=tok_rev,
             query={"city": "新疆", "page": 1, "pageSize": 5})
check("省级城市「新疆」→ 400（闸门拦截，不查高德不写库）", st == 400, (st, str(r)[:200]))
st, r = call("GET", "/recommendations/spots", token=tok_rev,
             query={"city": "北京", "page": 1, "pageSize": 3})
check("对照：合法城市「北京」仍 200", st == 200, (st, str(r)[:200]))

# ============================================================
print("\n=== 收尾：隐藏 A 段验证帖（不污染公开流） ===")
st, r = call("POST", "/community/moderation/posts/%d/hide" % pid, token=admin_token)
check("管理员隐藏验证帖 → 200", st == 200, (st, str(r)[:200]))
st, r = call("GET", "/community/posts/%d" % pid, token=tok_stranger)
check("隐藏后非作者不可见（404/403）", st in (403, 404), (st, str(r)[:200]))

print("\n" + "=" * 60)
print("结果：%d PASS / %d FAIL" % (ok_cnt, fail_cnt))
print("=" * 60)
raise SystemExit(1 if fail_cnt else 0)
