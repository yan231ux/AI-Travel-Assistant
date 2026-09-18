#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
第 2 批修复验证（2026-09-13）：真实 HTTP 端到端复测。

覆盖：
  D. AI 审核分级闭环      低风险帖 → AI 放行 → 帖子**真的**变 PUBLISHED，审核任务 decision=system:ai
                          （依赖外部 LLM；配额耗尽/未配 Key 时记为 SKIP 并说明原因，不算失败）
  E. 高危规则提交即拦     手机号（HIGH）在**提交阶段**就被规则拦下 400，根本不进审核队列、不烧 token
  E2. 中危规则阻断放行    二维码（MEDIUM）放行提交但阻断自动放行 → REVIEW，帖子不上线
  P. 审核决策落到内容     管理端对 REVIEW 任务点通过 → 帖子真的变 PUBLISHED（决策不是只写了个标记）
  F. 举报门禁             举报自己 → 400；举报未公开内容 → 400；举报他人已公开 → 200；未登录 → 401
  G. 改判下架（回归点）   对已发布内容的任务改判拒绝 → 帖子必须真的变 HIDDEN
                          （修正前无条件调 reject()，PUBLISHED 帖会撞状态机 → 异常被吞 → 帖子还在线）

自愈：探针用户保留（不删，保审计完整）；探针帖最终停在待审/已下架，不进公开流。
"""
import json
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8080"
ok_cnt = 0
fail_cnt = 0
skip_cnt = 0

# LLM 不可用的两种表现：调用抛异常 / 客户端返回 null（配额耗尽会被归一成"未返回内容"）
LLM_DOWN_MARKS = ("AI 调用失败", "未返回内容")


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


def skip(label, why=""):
    global skip_cnt
    skip_cnt += 1
    print("  [SKIP] " + label + ("  -> " + why if why else ""))


def msg_of(r):
    return (r or {}).get("message") if isinstance(r, dict) else ""


def llm_down(task):
    err = (task or {}).get("error_message") or ""
    return any(m in err for m in LLM_DOWN_MARKS)


def create_post(token, title, content, city="北京"):
    st, r = call("POST", "/community/posts", token=token, body={
        "title": title, "summary": title, "content": content,
        "city": city, "travel_days": 3, "budget": 2500, "pace": "轻松",
        "post_type": "NOTE",
    })
    pid = (r or {}).get("postId") if isinstance(r, dict) else None
    return st, pid, r


def submit_post(token, pid):
    return call("POST", "/community/posts/%d/submit" % pid, token=token)


def find_task(admin_token, pid, timeout=120):
    """轮询审核队列，返回该帖最新任务（到终态即返回）。"""
    tid = str(pid)
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        st, r = call("GET", "/admin/content/moderation", token=admin_token,
                     query={"targetType": "POST", "page": 1, "pageSize": 50})
        items = (r or {}).get("items") if isinstance(r, dict) else None
        if isinstance(items, list):
            for it in items:
                if str(it.get("target_id")) == tid:
                    last = it
                    if it.get("status") in ("PASSED", "REVIEW", "FAILED"):
                        return it
        time.sleep(2)
    return last


def snapshot(tag, t):
    if not t:
        print("      %s: <未找到任务>" % tag)
        return
    print("      %s: status=%s risk=%s score=%s hits=%s decision=%s by=%s err=%s" % (
        tag, t.get("status"), t.get("risk_level"), t.get("risk_score"), t.get("rule_hit_count"),
        t.get("decision"), t.get("decision_by"), (t.get("error_message") or "")[:70]))


def post_status(token, pid):
    st, r = call("GET", "/community/posts/%d" % pid, token=token)
    d = (r or {}).get("data") if isinstance(r, dict) else None
    return (d or {}).get("status") if isinstance(d, dict) else None


TS = int(time.time())

# ---------- 0. 账号准备 ----------
st, r = call("POST", "/auth/login", body={"username": "admin", "password": "admin123"})
admin = (r or {}).get("token") if isinstance(r, dict) else None
check("管理员 admin 登录成功", st == 200 and bool(admin), (st, r))

uname = "gv_b2_%d" % TS
st, reg = call("POST", "/auth/register",
               body={"username": uname, "password": "Test123456", "nickname": "批次二作者"})
token = (reg or {}).get("token") if isinstance(reg, dict) else None
check("注册作者用户成功", st == 200 and bool(token), (st, reg))

uname2 = "gv_b2r_%d" % TS
st, reg2 = call("POST", "/auth/register",
                body={"username": uname2, "password": "Test123456", "nickname": "批次二路人"})
token2 = (reg2 or {}).get("token") if isinstance(reg2, dict) else None
check("注册路人用户成功", st == 200 and bool(token2), (st, reg2))

if not (admin and token and token2):
    print("\n===== 汇总: PASS=%d FAIL=%d SKIP=%d (账号未就绪，提前终止) =====" % (ok_cnt, fail_cnt, skip_cnt))
    raise SystemExit(1)

# ---------- D. AI 审核分级闭环 ----------
print("\n=== D. AI 审核分级闭环：低风险帖应被 AI 放行并自动上线 ===")
st, pid_ok, r = create_post(
    token, "杭州三日慢游记录-%d" % TS,
    "这次在杭州住了三天，清晨去断桥看雾，人少景美；白天逛灵隐寺和龙井村，"
    "傍晚沿西湖走一圈，整体节奏很轻松，适合不赶时间的慢旅行。")
check("D1 创建草稿帖成功", st == 200 and pid_ok is not None, (st, r))

task_ok = None
auto_publish_proven = False
if pid_ok:
    st, r = submit_post(token, pid_ok)
    check("D2 提交审核成功（无规则违规）", st == 200 and not (r or {}).get("data"), (st, r))
    task_ok = find_task(admin, pid_ok)
    snapshot("任务", task_ok)
    check("D3 审核任务已生成", task_ok is not None, "队列中未找到该帖任务")

    if task_ok:
        if task_ok.get("status") == "PASSED":
            check("D4 PASSED → decision=APPROVE", task_ok.get("decision") == "APPROVE", task_ok.get("decision"))
            check("D4 PASSED → decision_by=system:ai", task_ok.get("decision_by") == "system:ai",
                  task_ok.get("decision_by"))
            got = post_status(token, pid_ok)
            check("D5 帖子真的变成 PUBLISHED（不再停在待审）", got == "PUBLISHED", got)
            auto_publish_proven = (got == "PUBLISHED")
        elif task_ok.get("status") == "REVIEW" and llm_down(task_ok):
            skip("D4/D5 AI 自动发布闭环",
                 "本机 LLM 不可用（%s）——外部环境问题；该闭环已由单测覆盖" % (task_ok.get("error_message") or "")[:70])
        else:
            check("D4 低风险帖不应被转人工",
                  False, "risk=%s score=%s decision=%s err=%s" % (
                      task_ok.get("risk_level"), task_ok.get("risk_score"),
                      task_ok.get("decision"), (task_ok.get("error_message") or "")[:60]))

# ---------- E. 高危规则：提交阶段即拦 ----------
print("\n=== E. 手机号（HIGH 规则）→ 提交阶段即被拦，不进审核队列、不烧 token ===")
st, pid_bad, r = create_post(
    token, "拼车联系我-%d" % TS,
    "周末想去郊区看红叶，有一起拼车的吗，我电话13800138000，方便的话加我微信细聊行程安排。")
check("E1 创建含手机号草稿成功（建帖不拦，提交才拦）", st == 200 and pid_bad is not None, (st, r))

if pid_bad:
    st, r = submit_post(token, pid_bad)
    check("E2 提交被规则拦截 → 400 且文案可读",
          st == 400 and "手机号" in msg_of(r), (st, r))
    check("E2 帖子未被置为待审（仍在草稿）", post_status(token, pid_bad) != "PENDING_REVIEW",
          post_status(token, pid_bad))
    t = find_task(admin, pid_bad, timeout=12)
    check("E2 未产生审核任务（高危内容不进 AI 队列）", t is None, t)

# ---------- E2. 中危规则：阻断自动放行 ----------
print("\n=== E2. 二维码（MEDIUM 规则）→ 放行提交但阻断自动放行，帖子不上线 ===")
st, pid_mid, r = create_post(
    token, "古镇购票小技巧-%d" % TS,
    "这个古镇的入口可以直接扫码买联票，省得排队；里面小巷很多，慢慢逛能逛一下午，"
    "傍晚灯笼亮起来特别好看。")
check("E2-1 创建含二维码词草稿成功", st == 200 and pid_mid is not None, (st, r))

task_mid = None
if pid_mid:
    st, r = submit_post(token, pid_mid)
    check("E2-2 提交成功（MEDIUM 不拦提交）", st == 200, (st, r))
    task_mid = find_task(admin, pid_mid)
    snapshot("任务", task_mid)
    if task_mid is None:
        check("E2-3 审核任务已生成", False, "队列中未找到该帖任务")
    else:
        check("E2-3 规则命中已记录（hits>=1）", (task_mid.get("rule_hit_count") or 0) >= 1,
              task_mid.get("rule_hit_count"))
        check("E2-3 MEDIUM 命中 → status=REVIEW（阻断自动放行）",
              task_mid.get("status") == "REVIEW", task_mid.get("status"))
        if task_mid.get("risk_level") is None and llm_down(task_mid):
            skip("E2-3 risk_level=MEDIUM",
                 "LLM 不可用，任务停在 REVIEW 未走到分级；不影响「不自动放行」这一结论")
        else:
            check("E2-3 MEDIUM 命中 → risk_level=MEDIUM",
                  task_mid.get("risk_level") == "MEDIUM", task_mid.get("risk_level"))
        check("E2-4 帖子未上线", post_status(token, pid_mid) == "PENDING_REVIEW", post_status(token, pid_mid))

# ---------- P. 审核决策落到内容（不依赖 LLM 的确定性链路） ----------
print("\n=== P. 管理端对 REVIEW 任务点通过 → 帖子真的上线（决策不是只写标记） ===")
published_target = None
if task_mid and pid_mid:
    st, r = call("POST", "/admin/content/moderation/%d/decide" % task_mid.get("id"), token=admin,
                 body={"action": "APPROVE", "reason": "探针：人工复核通过"})
    check("P1 人工通过成功", st == 200, (st, r))
    got = post_status(token, pid_mid)
    check("P2 帖子真的变成 PUBLISHED", got == "PUBLISHED", got)
    published_target = pid_mid
elif pid_ok and post_status(token, pid_ok) == "PUBLISHED":
    published_target = pid_ok
    skip("P1/P2 人工通过链路", "D 步已直接产出 PUBLISHED，跳过")

# ---------- F. 举报门禁 ----------
print("\n=== F. 举报门禁：作者不举报自己 / 未公开不可举报 / 他人已公开可举报 / 未登录不可举报 ===")
if pid_ok:
    st, r = call("POST", "/community/reports", token=token, body={
        "target_type": "POST", "target_id": pid_ok, "reason": "广告", "detail": "探针：作者举报自己"})
    check("F1 作者举报自己的帖子 → 400 且文案可读",
          st == 400 and "自己" in msg_of(r), (st, r))

if pid_bad:
    st, r = call("POST", "/community/reports", token=token2, body={
        "target_type": "POST", "target_id": pid_bad, "reason": "广告", "detail": "探针：举报未公开帖"})
    check("F2 举报未公开（草稿/待审）帖子 → 400",
          st == 400 and "公开" in msg_of(r), (st, r))

if published_target:
    st, r = call("POST", "/community/reports", token=token2, body={
        "target_type": "POST", "target_id": published_target, "reason": "广告",
        "detail": "探针：举报他人已公开帖"})
    check("F3 举报他人已公开帖子 → 200", st == 200, (st, r))
else:
    skip("F3 举报他人已公开帖子", "本次未产出已发布帖")

st, r = call("POST", "/community/reports", body={
    "target_type": "POST", "target_id": published_target or 1, "reason": "广告", "detail": "探针：未登录"})
check("F4 未登录举报 → 401/403", st in (401, 403), (st, r))

# ---------- G. 改判下架（回归点） ----------
print("\n=== G. 改判下架：对已发布内容的任务改判拒绝 → 帖子必须真的变 HIDDEN ===")
if published_target and task_mid:
    tid = task_mid.get("id")
    st, r = call("POST", "/admin/content/moderation/%d/decide" % tid, token=admin,
                 body={"action": "REJECT", "reason": "探针改判：内容不再适合公开展示"})
    check("G1 改判拒绝成功", st == 200, (st, r))
    got = post_status(token, published_target)
    check("G2 帖子真的变成 HIDDEN（改判生效）", got == "HIDDEN", got)
    st, r = call("GET", "/admin/content/moderation/%d" % tid, token=admin)
    d = (r or {}).get("data") if isinstance(r, dict) else None
    check("G3 决策已留痕（decision=REJECT，decision_by 换成管理员）",
          isinstance(d, dict) and d.get("decision") == "REJECT"
          and d.get("decision_by") not in (None, "", "system:ai"),
          (d or {}).get("decision"))
    st2, r2 = call("POST", "/admin/content/moderation/%d/decide" % tid, token=admin,
                   body={"action": "REJECT", "reason": ""})
    check("G4 REJECT 空原因 → 400（强制留痕）", st2 == 400, (st2, r2))
else:
    skip("G 改判下架", "本次未产出已发布帖")

# ---------- H. 对 AI 自动放行的任务再改判（若 D 走得通） ----------
if auto_publish_proven and task_ok:
    print("\n=== H. 对 AI 自动放行的帖子改判 → 必须能下架（AI 放行 ≠ 不可撤销） ===")
    st, r = call("POST", "/admin/content/moderation/%d/decide" % task_ok.get("id"), token=admin,
                 body={"action": "REJECT", "reason": "探针改判：AI 放行的内容人工复核后下架"})
    check("H1 改判拒绝成功", st == 200, (st, r))
    got = post_status(token, pid_ok)
    check("H2 AI 放行的帖子真的被下架", got == "HIDDEN", got)
else:
    skip("H 对 AI 自动放行任务改判", "D 步未验证到自动放行（LLM 不可用）")

# ---------- 收尾 ----------
print("\n===== 汇总: PASS=%d FAIL=%d SKIP=%d =====" % (ok_cnt, fail_cnt, skip_cnt))
print("（探针用户 %s / %s；帖子 id=%s / %s / %s，最终均不在公开流）" % (uname, uname2, pid_ok, pid_bad, pid_mid))
raise SystemExit(1 if fail_cnt else 0)
