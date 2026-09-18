# -*- coding: utf-8 -*-
"""以 UTF-8 安全方式执行 git add/commit/tag/push（避免 PowerShell 代码页把中文提交信息弄坏）。"""
import os
import subprocess

REPO = r"C:\Users\曾\Desktop\agent\AI-Travel-Assistant"
ENV = dict(os.environ)
ENV["GIT_TERMINAL_PROMPT"] = "0"
ENV["GCM_INTERACTIVE"] = "never"


def run(args):
    r = subprocess.run(["git"] + args, cwd=REPO, env=ENV,
                       capture_output=True, encoding="utf-8", errors="replace")
    print("$ git " + " ".join(args))
    if r.stdout.strip():
        print(r.stdout.strip())
    if r.stderr.strip():
        print("[stderr] " + r.stderr.strip())
    print("-> exit %s" % r.returncode)
    print("-" * 60)
    return r.returncode


run(["add", "-A"])
run(["status", "--short"])
run(["commit", "-F", "backend/_commitmsg.txt"])
run(["tag", "-a", "v1.9", "-m", "v1.9: 行程 JSON 健壮性修复 + agent-mode 默认 autonomous"])
run(["push", "origin", "main"])
run(["push", "origin", "v1.9"])
run(["log", "--oneline", "-3"])
run(["tag", "--list", "v1.*"])
