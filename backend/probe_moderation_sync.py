# -*- coding: utf-8 -*-
"""A1 复核探针：证明"AI 筛选队列（未决策）"与"内容审核页（待审帖）"现在完全对齐。

判据：
  A = /admin/content/moderation?status=REVIEW&decisionState=PENDING 的 POST 目标帖集合
  B = /admin/review/posts?status=PENDING_REVIEW 的帖集合
要求 A == B（修复前 A 会比 B 多出"已决策完却仍挂在 REVIEW"的任务）。

用法：python probe_moderation_sync.py   （后端需已启动）
"""
import json
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"
OUT = r"C:\Users\曾\Desktop\agent\AI-Travel-Assistant\backend\_probe_moderation_sync.txt"

lines = []
ok_all = True


def rec(s):
    lines.append(s)
    print(s.encode("ascii", "replace").decode("ascii"))


def call(method, path, token=None, body=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode("utf-8") if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=15) as r:
            return r.status, json.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def check(name, cond, detail=""):
    global ok_all
    ok_all = ok_all and bool(cond)
    rec(("PASS " if cond else "FAIL ") + name + ("  | " + str(detail) if detail else ""))


def main():
    st, r = call("POST", "/auth/login", body={"username": "admin", "password": "admin123"})
    token = (r or {}).get("token") or ((r or {}).get("data") or {}).get("token")
    check("管理员登录", st == 200 and bool(token), st)
    if not token:
        return

    st, r = call("GET", "/admin/content/moderation?status=REVIEW&page=1&pageSize=200", token)
    all_review = (r or {}).get("items") or []
    rec("REVIEW 任务总数 = %d" % len(all_review))

    st, r = call("GET",
                 "/admin/content/moderation?status=REVIEW&decisionState=PENDING&page=1&pageSize=200",
                 token)
    pend = (r or {}).get("items") or []
    pend_posts = sorted({int(t["target_id"]) for t in pend if t.get("target_type") == "POST"
                         and (t.get("decision") in (None, "", "null"))})
    rec("严格待办（REVIEW + 未决策）POST 帖 = %s" % pend_posts)

    st, r = call("GET", "/admin/review/posts?status=PENDING_REVIEW&page=1&pageSize=200", token)
    review_posts = sorted({int(p["id"]) for p in ((r or {}).get("items") or [])})
    rec("内容审核页 待审帖 = %s" % review_posts)

    check("两组集合完全一致（队列与内容审核页同步）", pend_posts == review_posts,
          "queue=%s content=%s" % (pend_posts, review_posts))

    stale = [t["id"] for t in all_review if t.get("decision") not in (None, "", "null")]
    rec("仍挂 REVIEW 但已有决策的历史任务（应被 PENDING 过滤掉）= %s" % stale)
    check("已决策任务不再进入待办", all(t["id"] not in {x["id"] for x in pend} for t in all_review
                                        if t.get("decision") not in (None, "", "null")))

    rec("")
    rec("RESULT: " + ("ALL PASS" if ok_all else "HAS FAILURE"))


if __name__ == "__main__":
    main()
    with open(OUT, "w", encoding="utf-8") as fp:
        fp.write("\n".join(lines) + "\n")
