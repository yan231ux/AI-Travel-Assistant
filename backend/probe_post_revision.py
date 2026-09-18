# -*- coding: utf-8 -*-
"""
P1-1 帖子版本化端到端探针（2026-09-18）。

验证「公开版本 / 编辑版本分离」在真实服务上成立：
  1) 已发布帖被编辑 -> 主表（线上版本）一个字都不动，修改稿落 travel_post_revision 待审；
  2) published_at 不变（"修改"不是"重新发布"，老帖不被顶到时间线最前）；
  3) 作者侧/管理员侧都能看到"有修改待审"标记与待审内容快照；
  4) 审核通过 -> 内容原子切换、指针解除；
  5) 审核拒绝 -> 线上继续服务原版本。

用法：python probe_post_revision.py
"""
import json
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
OK = "\u2705"
NG = "\u274c"

results = []


def call(method, path, token=None, body=None):
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    print(("%s %s" % (OK if cond else NG, name)) + ("  -> " + detail if detail else ""))


def login(username, password):
    st, body = call("POST", "/auth/login", body={"username": username, "password": password})
    if st == 200:
        return body.get("token") or body.get("data", {}).get("token")
    return None


def main():
    ts = str(int(time.time()))
    uname = "rev_probe_" + ts
    st, body = call("POST", "/auth/register",
                    body={"username": uname, "password": "test1234", "nickname": "版本化探针"})
    utoken = body.get("token") or (body.get("data") or {}).get("token")
    if not utoken:
        utoken = login(uname, "test1234")
    check("注册/登录取用户 token", bool(utoken), "status=%s" % st)
    if not utoken:
        return

    atoken = login("admin", "admin123")
    check("管理员登录（admin/admin123）", bool(atoken))
    if not atoken:
        return

    # ---- 1. 建帖 -> 提交 -> 发布 ----
    st, body = call("POST", "/community/posts", utoken, {
        "title": "西安三日游（版本化探针）",
        "summary": "原始摘要：城墙 + 大雁塔",
        "content": "原始正文：第一天城墙骑行，第二天大雁塔与大唐不夜城，第三天回民街闲逛。",
        "city": "西安",
        "travel_days": 3,
        "post_type": "GUIDE",
    })
    post_id = body.get("postId") or body.get("id") or (body.get("data") or {}).get("postId")
    check("创建草稿", bool(post_id), "postId=%s status=%s" % (post_id, st))
    if not post_id:
        print(json.dumps(body, ensure_ascii=False)[:400])
        return

    st, body = call("POST", "/community/posts/%s/submit" % post_id, utoken)
    check("提交审核", st in (200, 201), "status=%s %s" % (st, json.dumps(body, ensure_ascii=False)[:160]))

    # AI 审核可能自动放行；轮询直到 PUBLISHED，否则管理员通过
    published = False
    for _ in range(20):
        st, d = call("GET", "/community/posts/%s" % post_id, utoken)
        data = d.get("data", d)
        if data.get("status") == "PUBLISHED":
            published = True
            break
        time.sleep(2)
    if not published:
        st, _ = call("POST", "/community/moderation/posts/%s/approve" % post_id, atoken)
        time.sleep(1)
        st, d = call("GET", "/community/posts/%s" % post_id, utoken)
        published = d.get("data", d).get("status") == "PUBLISHED"
    check("帖子进入 PUBLISHED", published)

    st, d = call("GET", "/community/posts/%s" % post_id, utoken)
    before = d.get("data", d)
    before_content = before.get("content")
    before_published_at = before.get("published_at")

    # ---- 2. 编辑已发布帖：修改稿应独立待审，线上不动 ----
    new_content = "修改后正文：第一天城墙 + 永兴坊，第二天大雁塔、大唐不夜城，第三天华山一日游。"
    st, body = call("PUT", "/community/posts/%s" % post_id, utoken, {
        "title": "西安三日游（版本化探针·改）",
        "summary": "修改后摘要：加了华山",
        "content": new_content,
        "city": "西安",
        "travel_days": 3,
        "post_type": "GUIDE",
    })
    check("已发布帖提交修改", st in (200, 201), "status=%s %s" % (st, json.dumps(body, ensure_ascii=False)[:160]))

    st, d = call("GET", "/community/posts/%s" % post_id, utoken)
    after = d.get("data", d)
    check("线上版本状态仍为 PUBLISHED（未被撤下）", after.get("status") == "PUBLISHED",
          "status=%s" % after.get("status"))
    check("线上正文仍是原版本（读者无感）", after.get("content") == before_content,
          "content=%s" % str(after.get("content"))[:40])
    check("published_at 未被重置（修改≠重新发布）",
          after.get("published_at") == before_published_at,
          "%s -> %s" % (before_published_at, after.get("published_at")))
    check("详情透出 has_pending_revision", after.get("has_pending_revision") is True)
    check("详情透出待审版本号", after.get("pending_revision_no") == 1,
          "no=%s" % after.get("pending_revision_no"))
    pr = after.get("pending_revision") or {}
    check("待审版本快照含修改后正文", pr.get("content") == new_content)
    check("待审版本状态为 PENDING_REVIEW", pr.get("status") == "PENDING_REVIEW", "status=%s" % pr.get("status"))

    # ---- 3. 列表/审核队列透出 ----
    st, d = call("GET", "/community/posts/mine?page=1&pageSize=50", utoken)
    items = d.get("items", (d.get("data") or {}).get("items", []))
    mine = next((x for x in items if int(x.get("id")) == int(post_id)), None)
    check("我的帖子列表带 has_pending_revision", bool(mine) and mine.get("has_pending_revision") is True)

    st, d = call("GET", "/community/moderation/posts/pending?page=1&pageSize=50", atoken)
    qitems = d.get("items", (d.get("data") or {}).get("items", []))
    in_queue = next((x for x in qitems if int(x.get("id")) == int(post_id)), None)
    check("审核队列包含「已发布但有待审修改版本」的帖子", in_queue is not None)

    st, d = call("GET", "/admin/review/posts/%s" % post_id, atoken)
    ev = d.get("data", d)
    check("审核详情透出待审版本（避免盲审）", ev.get("has_pending_revision") is True
          and (ev.get("pending_revision") or {}).get("content") == new_content)

    # ---- 4. 审核通过：内容原子切换 ----
    st, body = call("POST", "/community/moderation/posts/%s/approve" % post_id, atoken)
    check("审核通过修改版本", st in (200, 201), "status=%s %s" % (st, json.dumps(body, ensure_ascii=False)[:160]))

    st, d = call("GET", "/community/posts/%s" % post_id, utoken)
    applied = d.get("data", d)
    check("线上正文已切换为新版本", applied.get("content") == new_content,
          "content=%s" % str(applied.get("content"))[:40])
    check("状态仍为 PUBLISHED", applied.get("status") == "PUBLISHED")
    check("published_at 保持原值", applied.get("published_at") == before_published_at,
          "%s" % applied.get("published_at"))
    check("待审指针已解除", applied.get("has_pending_revision") in (False, None))

    # ---- 5. 再改一次 -> 拒绝：线上继续服务已通过的版本 ----
    st, _ = call("PUT", "/community/posts/%s" % post_id, utoken, {
        "title": "西安三日游（版本化探针·再改）",
        "content": "又被改了一次的正文（预期被拒绝，不应上线）。",
        "city": "西安",
        "post_type": "GUIDE",
    })
    st, body = call("POST", "/community/moderation/posts/%s/reject" % post_id, atoken,
                    {"reason": "探针：拒绝该修改版本"})
    check("拒绝修改版本", st in (200, 201), "status=%s" % st)

    st, d = call("GET", "/community/posts/%s" % post_id, utoken)
    rej = d.get("data", d)
    check("拒绝后线上仍是上一次通过的版本", rej.get("content") == new_content,
          "content=%s" % str(rej.get("content"))[:40])
    check("拒绝后状态仍是 PUBLISHED", rej.get("status") == "PUBLISHED")
    check("拒绝后待审指针解除", rej.get("has_pending_revision") in (False, None))

    passed = sum(1 for _, ok, _ in results if ok)
    print("\n==== 版本化探针结果: %d/%d 通过 ====" % (passed, len(results)))
    for name, ok, detail in results:
        if not ok:
            print("  FAIL: %s  %s" % (name, detail))
    raise SystemExit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
