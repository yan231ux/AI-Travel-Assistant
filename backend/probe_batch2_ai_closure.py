#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
第 2 批 AI 分级闭环补验（2026-09-13）：用本地 LLM 桩把"AI 判定 → 自动发布"整条链路跑穿。

背景：真实 DashScope 免费额度耗尽（403 AllocationQuota.FreeTierOnly），`probe_batch2_verify.py`
的 D 步只能 SKIP。本探针配合 `stub_llm_server.py` 启动后端，把三种分级分支一次验完：

  1) 模型 PASS + 低分            → 自动放行 → 帖子真的 PUBLISHED，任务 decision=system:ai
  2) 模型 REVIEW（分数只有 0.1） → 必须人工（低分不可信，模型的明确表态优先）
  3) 模型 PASS 但命中 PRIVACY    → 必须人工（强制人工类别压过低分）
  4) 对自动放行的帖子改判拒绝     → 帖子真的 HIDDEN（AI 放行 ≠ 不可撤销）

前置：stub 已在 18081；后端以 `--llm.base-url=http://127.0.0.1:18081/v1 --llm.api-key=stub` 启动。
"""
import json
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8080"
ok_cnt = 0
fail_cnt = 0


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
        with urllib.request.urlopen(req, timeout=120) as resp:
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


def create_post(token, title, content):
    st, r = call("POST", "/community/posts", token=token, body={
        "title": title, "summary": title, "content": content,
        "city": "北京", "travel_days": 3, "budget": 2500, "pace": "轻松", "post_type": "NOTE"})
    return st, ((r or {}).get("postId") if isinstance(r, dict) else None), r


def find_task(admin, pid, timeout=90):
    tid = str(pid)
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        st, r = call("GET", "/admin/content/moderation", token=admin,
                     query={"targetType": "POST", "page": 1, "pageSize": 50})
        items = (r or {}).get("items") if isinstance(r, dict) else None
        if isinstance(items, list):
            for it in items:
                if str(it.get("target_id")) == tid:
                    last = it
                    if it.get("status") in ("PASSED", "REVIEW", "FAILED"):
                        return it
        time.sleep(1)
    return last


def post_status(token, pid):
    st, r = call("GET", "/community/posts/%d" % pid, token=token)
    d = (r or {}).get("data") if isinstance(r, dict) else None
    return (d or {}).get("status") if isinstance(d, dict) else None


def snap(t):
    if not t:
        return "<未找到任务>"
    return "status=%s risk=%s score=%s decision=%s by=%s err=%s" % (
        t.get("status"), t.get("risk_level"), t.get("risk_score"),
        t.get("decision"), t.get("decision_by"), (t.get("error_message") or "")[:60])


TS = int(time.time())

st, r = call("POST", "/auth/login", body={"username": "admin", "password": "admin123"})
admin = (r or {}).get("token") if isinstance(r, dict) else None
check("管理员登录成功", st == 200 and bool(admin), (st, r))

st, reg = call("POST", "/auth/register",
               body={"username": "gv_b2ai_%d" % TS, "password": "Test123456", "nickname": "闭环验证"})
token = (reg or {}).get("token") if isinstance(reg, dict) else None
check("注册作者用户成功", st == 200 and bool(token), (st, reg))
if not (admin and token):
    print("\n===== PASS=%d FAIL=%d (账号未就绪) =====" % (ok_cnt, fail_cnt))
    raise SystemExit(1)

# ---------- 1) 低风险 → 自动放行上线 ----------
print("\n=== 1) 模型 PASS + 低分 → 自动放行，帖子真的上线 ===")
st, pid_ok, r = create_post(token, "杭州三日慢游记录-%d" % TS,
                            "这次在杭州住了三天，清晨去断桥看雾，人少景美；白天逛灵隐寺和龙井村，"
                            "傍晚沿西湖走一圈，整体节奏很轻松，适合不赶时间的慢旅行。")
check("创建草稿帖成功", st == 200 and pid_ok is not None, (st, r))
task_ok = None
if pid_ok:
    st, r = call("POST", "/community/posts/%d/submit" % pid_ok, token=token)
    check("提交审核成功", st == 200 and not (r or {}).get("data"), (st, r))
    task_ok = find_task(admin, pid_ok)
    print("      任务: " + snap(task_ok))
    check("任务终态 = PASSED", (task_ok or {}).get("status") == "PASSED", (task_ok or {}).get("status"))
    check("decision = APPROVE", (task_ok or {}).get("decision") == "APPROVE", (task_ok or {}).get("decision"))
    check("decision_by = system:ai（系统主体，非管理员账号）",
          (task_ok or {}).get("decision_by") == "system:ai", (task_ok or {}).get("decision_by"))
    got = post_status(token, pid_ok)
    check("帖子真的变成 PUBLISHED（AI 放行不再只是句文案）", got == "PUBLISHED", got)

# ---------- 2) 模型说 REVIEW（低分）→ 必须人工 ----------
print("\n=== 2) 模型 REVIEW（分数仅 0.1）→ 低分不可信，必须人工 ===")
st, pid_rev, r = create_post(token, "随手记一下-%d" % TS,
                             "STUB_REVIEW 这篇只是想随便记录一下路上的见闻，内容不多，"
                             "还没想好怎么写，先占个位置后续再补充完整。")
check("创建草稿帖成功", st == 200 and pid_rev is not None, (st, r))
if pid_rev:
    st, r = call("POST", "/community/posts/%d/submit" % pid_rev, token=token)
    check("提交审核成功", st == 200, (st, r))
    t = find_task(admin, pid_rev)
    print("      任务: " + snap(t))
    check("任务终态 = REVIEW", (t or {}).get("status") == "REVIEW", (t or {}).get("status"))
    check("未被自动放行（decision_by 不是 system:ai）",
          (t or {}).get("decision_by") != "system:ai", (t or {}).get("decision_by"))
    got = post_status(token, pid_rev)
    check("帖子未上线（仍待审）", got == "PENDING_REVIEW", got)

# ---------- 3) 模型 PASS 但命中 PRIVACY → 必须人工 ----------
print("\n=== 3) 模型 PASS 且低分，但命中 PRIVACY → 强制人工 ===")
st, pid_priv, r = create_post(token, "住宿登记提醒-%d" % TS,
                              "STUB_PRIVACY 出发前记得带好证件，把身份证号抄一份放在行李里备份，"
                              "免得路上遇到需要登记时手忙脚乱，也算是个小经验。")
check("创建草稿帖成功", st == 200 and pid_priv is not None, (st, r))
if pid_priv:
    st, r = call("POST", "/community/posts/%d/submit" % pid_priv, token=token)
    check("提交审核成功", st == 200, (st, r))
    t = find_task(admin, pid_priv)
    print("      任务: " + snap(t))
    check("任务终态 = REVIEW（敏感信息不许自动上线）", (t or {}).get("status") == "REVIEW",
          (t or {}).get("status"))
    got = post_status(token, pid_priv)
    check("帖子未上线（仍待审）", got == "PENDING_REVIEW", got)

# ---------- 4) 对自动放行的帖子改判拒绝 → 必须下架 ----------
print("\n=== 4) 对 AI 自动放行的帖子改判拒绝 → 必须真的下架（AI 放行可撤销） ===")
if task_ok and (task_ok.get("status") == "PASSED") and post_status(token, pid_ok) == "PUBLISHED":
    st, r = call("POST", "/admin/content/moderation/%d/decide" % task_ok.get("id"), token=admin,
                 body={"action": "REJECT", "reason": "闭环验证：人工复核后下架"})
    check("改判拒绝成功", st == 200, (st, r))
    got = post_status(token, pid_ok)
    check("帖子真的变成 HIDDEN", got == "HIDDEN", got)
else:
    check("前置：存在 AI 自动放行的已发布帖", False,
          "status=%s post=%s" % ((task_ok or {}).get("status"), post_status(token, pid_ok) if pid_ok else None))

print("\n===== 汇总: PASS=%d FAIL=%d =====" % (ok_cnt, fail_cnt))
print("（探针帖 id=%s(已下架) / %s / %s，最终均不在公开流）" % (pid_ok, pid_rev, pid_priv))
raise SystemExit(1 if fail_cnt else 0)
