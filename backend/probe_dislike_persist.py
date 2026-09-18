# -*- coding: utf-8 -*-
"""
② 「不感兴趣」落库端到端探针（2026-09-18）。

验证 DISLIKE（不感兴趣）表态的状态**不依赖前端内存**：互动接口返回后，
重新拉取列表与详情都仍带 disliked=true —— 即"刷新不丢"。
反向验证：取消不感兴趣后同样落库为 false。

用法：python probe_dislike_persist.py
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
    req = urllib.request.Request(BASE + path,
                                 data=json.dumps(body).encode("utf-8") if body is not None else None,
                                 method=method)
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


def reg_or_login(uname, pwd="test1234", nickname="不感兴趣探针"):
    st, b = call("POST", "/auth/register", body={"username": uname, "password": pwd, "nickname": nickname})
    tok = b.get("token") or (b.get("data") or {}).get("token")
    if not tok:
        st, b = call("POST", "/auth/login", body={"username": uname, "password": pwd})
        tok = b.get("token") or (b.get("data") or {}).get("token")
    return tok


def main():
    ts = str(int(time.time()))
    author = reg_or_login("dis_author_" + ts)
    viewer = reg_or_login("dis_viewer_" + ts, nickname="旁观者")
    check("作者/旁观者双双登录", bool(author) and bool(viewer))
    if not author or not viewer:
        return

    st, b = call("POST", "/community/posts", author, {
        "title": "厦门两日游（不感兴趣探针）", "summary": "摘要", "city": "厦门",
        "content": "第一天鼓浪屿，第二天环岛路骑行与曾厝垵小吃。", "post_type": "GUIDE",
    })
    pid = b.get("postId") or b.get("id") or (b.get("data") or {}).get("postId")
    check("作者建帖", bool(pid), "postId=%s" % pid)
    if not pid:
        return
    call("POST", "/community/posts/%s/submit" % pid, author)

    atoken = None
    st, b = call("POST", "/auth/login", body={"username": "admin", "password": "admin123"})
    atoken = b.get("token") or (b.get("data") or {}).get("token")
    for _ in range(20):
        st, d = call("GET", "/community/posts/%s" % pid, author)
        if d.get("data", d).get("status") == "PUBLISHED":
            break
        time.sleep(2)
    if d.get("data", d).get("status") != "PUBLISHED":
        call("POST", "/community/moderation/posts/%s/approve" % pid, atoken)
        time.sleep(1)
    st, d = call("GET", "/community/posts/%s" % pid, author)
    check("帖子已发布", d.get("data", d).get("status") == "PUBLISHED")
    if d.get("data", d).get("status") != "PUBLISHED":
        return

    # 旁观者表态「不感兴趣」
    st, b = call("POST", "/community/posts/%s/dislike" % pid, viewer)
    check("旁观者点不感兴趣", st in (200, 201), "status=%s %s" % (st, json.dumps(b, ensure_ascii=False)[:120]))

    st, d = call("GET", "/community/posts/%s" % pid, viewer)
    check("详情返回 disliked=true（刷新不丢）", d.get("data", d).get("disliked") is True,
          "disliked=%s" % d.get("data", d).get("disliked"))

    st, d = call("GET", "/community/posts?page=1&pageSize=50", viewer)
    items = d.get("items", (d.get("data") or {}).get("items", []))
    row = next((x for x in items if int(x.get("id")) == int(pid)), None)
    check("列表也带 disliked=true（列表/详情同源）", bool(row) and row.get("disliked") is True,
          "disliked=%s" % (row or {}).get("disliked"))

    st, d = call("GET", "/community/posts/%s" % pid, author)
    check("作者视角 disliked 恒为 false（自己不能对自己表态）",
          d.get("data", d).get("disliked") is False,
          "disliked=%s" % d.get("data", d).get("disliked"))

    # 取消表态
    st, _ = call("DELETE", "/community/posts/%s/dislike" % pid, viewer)
    st, d = call("GET", "/community/posts/%s" % pid, viewer)
    check("取消后 disliked=false 同样落库", d.get("data", d).get("disliked") is False,
          "disliked=%s" % d.get("data", d).get("disliked"))

    passed = sum(1 for _, ok, _ in results if ok)
    print("\n==== 不感兴趣落库探针: %d/%d 通过 ====" % (passed, len(results)))
    for name, ok, detail in results:
        if not ok:
            print("  FAIL: %s  %s" % (name, detail))
    raise SystemExit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
