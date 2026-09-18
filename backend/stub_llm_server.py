#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
离线 LLM 桩服务（仅用于验收/答辩演示，不参与业务代码）。

用途：本机 DashScope 免费额度耗尽后（HTTP 403 AllocationQuota.FreeTierOnly），
"AI 判定 → 自动发布" 这条链路无法用真实模型端到端验证。本桩在本地起一个
OpenAI 兼容端点，按 **提示词里的标记** 返回确定性的审核结论，从而把
`ContentModerationService` 的分级闭环完整跑穿，且不花一分钱额度、结果可复现。

用法：
    python stub_llm_server.py 18081
    java -jar target/trip-planner-1.0.0.jar --server.port=8080 \
         --llm.base-url=http://127.0.0.1:18081/v1 --llm.api-key=stub

三种应答（对应三条分级分支，验证"自动放行必须三条件同时成立"）：
    正文含 STUB_PRIVACY → decision=PASS 但 categories 命中 PRIVACY → 必须人工（强制人工类别）
    正文含 STUB_REVIEW  → decision=REVIEW（分数很低 0.1）→ 必须人工（模型的明确表态优先于分数）
    其余                → decision=PASS、risk_score=0.05 → 允许自动放行上线
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def verdict_for(prompt: str) -> str:
    if "STUB_PRIVACY" in prompt:
        return json.dumps({
            "decision": "PASS", "risk_level": "LOW", "risk_score": 0.05,
            "categories": [{"code": "PRIVACY", "confidence": 0.6, "evidence": "身份证号"}],
            "suggestion": "含个人敏感信息，建议移交人工确认",
        }, ensure_ascii=False)
    if "STUB_REVIEW" in prompt:
        return json.dumps({
            "decision": "REVIEW", "risk_level": "LOW", "risk_score": 0.1,
            "categories": [], "suggestion": "无法确定，需人工复核",
        }, ensure_ascii=False)
    return json.dumps({
        "decision": "PASS", "risk_level": "LOW", "risk_score": 0.05,
        "categories": [], "suggestion": "未发现风险，可公开展示",
    }, ensure_ascii=False)


class Handler(BaseHTTPRequestHandler):
    def _read_body(self) -> bytes:
        """读请求体：Spring 的 RestTemplate 发 JSON 时走 chunked（无 Content-Length），
        只按 Content-Length 读会拿到空串、于是所有请求都落到默认分支——
        实测踩过：三种分级分支变成一种，还以为业务逻辑坏了。"""
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            data = b""
            while True:
                line = self.rfile.readline().strip()
                try:
                    size = int(line.split(b";")[0], 16)
                except ValueError:
                    break
                if size == 0:
                    self.rfile.readline()
                    break
                data += self.rfile.read(size)
                self.rfile.readline()
            return data
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length > 0 else b""

    def do_POST(self):  # noqa: N802
        raw = self._read_body().decode("utf-8", "replace")
        try:
            prompt = (json.loads(raw).get("messages") or [{}])[0].get("content") or ""
        except Exception:
            prompt = raw
        try:
            with open("_stub_llm_last_prompt.log", "a", encoding="utf-8") as fh:
                fh.write("---- len(raw)=%d len(prompt)=%d has_review=%s has_privacy=%s\n"
                         % (len(raw), len(prompt), "STUB_REVIEW" in prompt, "STUB_PRIVACY" in prompt))
                fh.write(prompt[-600:] + "\n")
        except Exception:
            pass
        content = verdict_for(prompt)
        body = json.dumps({
            "id": "stub-cmpl", "object": "chat.completion", "model": "stub",
            "choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": content}}],
            "usage": {"prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30},
        }, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        sys.stderr.write("[stub-llm] " + (fmt % args) + "\n")


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18081
    print("stub LLM listening on http://127.0.0.1:%d/v1" % port, flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
