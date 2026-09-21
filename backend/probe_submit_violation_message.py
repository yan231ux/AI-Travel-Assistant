# -*- coding: utf-8 -*-
"""验证「提交审核被规则拦截」时，后端 400 的业务原因能否真的送到界面。

背景（用户实测反馈）：卡片点"提交审核"弹「提交失败，请稍后重试」，看不到原因。
根因：规则拦截走 HTTP 400，axios 抛异常 → 前端 `if (!resp.success)` 分支永远走不到，
后端写的 violations/message 被丢掉。修复后 submitPost 把 400 的响应体透传。

本探针做真实复现：
  1) 演示号登录 → 建一条草稿（摘要故意放手机号）
  2) 提交审核 → 断言 400 且响应体带非空 violations
  3) 按修复后的映射逻辑算出"界面会显示什么"
  4) 清理：删掉这条草稿并确认删除

用法：python probe_submit_violation_message.py   （后端需已启动）
"""
import json
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"
OUT = r"C:\Users\曾\Desktop\agent\AI-Travel-Assistant\backend\_probe_submit_violation.txt"

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
        with urllib.request.urlopen(req, data=data, timeout=20) as r:
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
    st, r = call("POST", "/auth/login",
                 body={"username": "demo_ops_viewer", "password": "test1234"})
    token = (r or {}).get("token") or ((r or {}).get("data") or {}).get("token")
    check("演示号登录", st == 200 and bool(token), st)
    if not token:
        return

    # 与用户实测同构：摘要是 11 位手机号（命中 1[3-9]\d{9}）
    st, r = call("POST", "/community/posts", token, body={
        "title": "提交拦截提示验证",
        "summary": "15816472242",
        "content": "这是一条用于验证提交拦截提示的测试正文内容，长度足够通过最小长度校验。",
        "city": "三亚",
        "post_type": "NOTE",
    })
    post_id = (r or {}).get("postId") or ((r or {}).get("data") or {}).get("postId")
    check("建草稿（摘要含手机号）", st == 200 and bool(post_id), (st, r))
    if not post_id:
        return

    try:
        st, r = call("POST", "/community/posts/%s/submit" % post_id, token)
        rec("提交审核 HTTP 状态 = %s" % st)
        rec("响应体 = %s" % json.dumps(r, ensure_ascii=False))
        violations = (r or {}).get("violations")
        check("返回 400（规则拦截）", st == 400, st)
        check("响应体带非空 violations 数组", isinstance(violations, list) and len(violations) > 0,
              violations)
        check("原因里明确指出手机号", bool(violations) and "手机号" in violations[0], violations)

        # 修复后的前端映射：400 + violations 数组 → 返回 {success:false, message}
        if st == 400 and isinstance(violations, list):
            shown = {"success": False, "violations": violations,
                     "message": (r or {}).get("message")}
        else:
            shown = "THROW（走 catch 兜底文案）"
        rec("修复后界面会显示 = %s" % json.dumps(shown, ensure_ascii=False))
        check("界面拿到可读原因（而非兜底文案）",
              isinstance(shown, dict) and bool(shown.get("message")) and "手机号" in shown["message"],
              shown)
    finally:
        st, r = call("DELETE", "/community/posts/%s" % post_id, token)
        rec("清理草稿 HTTP 状态 = %s" % st)
        check("测试草稿已删除", st == 200, (st, r))

    rec("")
    rec("RESULT: " + ("ALL PASS" if ok_all else "HAS FAILURE"))


if __name__ == "__main__":
    main()
    with open(OUT, "w", encoding="utf-8") as fp:
        fp.write("\n".join(lines) + "\n")
