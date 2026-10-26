"""极简 HTTP 客户端：只用标准库，统一 User-Agent、超时和 gzip 处理。"""
from __future__ import annotations

import gzip
import json
import urllib.parse
import urllib.request

USER_AGENT = "Mozilla/5.0 (compatible; catchup/0.1)"
TIMEOUT = 30


def get(url: str, headers: dict | None = None) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, **(headers or {})})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        data = resp.read()
    # 有的 CDN（如 deepmind.google）即使没声明 Accept-Encoding 也会返回 gzip
    return gzip.decompress(data) if data[:2] == b"\x1f\x8b" else data


def get_json(url: str, headers: dict | None = None):
    return json.loads(get(url, headers))


def post_stream(url: str, payload, headers: dict | None = None, timeout: int = TIMEOUT):
    """POST 后逐条产出服务端 SSE 里 data: 行的 JSON，遇到 [DONE] 结束。"""
    req = urllib.request.Request(url, data=json.dumps(payload, ensure_ascii=False).encode(),
                                 headers={"User-Agent": USER_AGENT, "Content-Type": "application/json",
                                          "Accept": "text/event-stream", **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        for raw in resp:
            line = raw.decode("utf-8", "replace").strip()
            if not line.startswith("data:"):
                continue
            data = line[5:].strip()
            if data == "[DONE]":
                return
            yield json.loads(data)


def post_form(url: str, fields: dict) -> bytes:
    req = urllib.request.Request(url, data=urllib.parse.urlencode(fields).encode(),
                                 headers={"User-Agent": USER_AGENT,
                                          "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        return resp.read()


def post_json(url: str, payload, headers: dict | None = None, timeout: int = TIMEOUT):
    req = urllib.request.Request(url, data=json.dumps(payload, ensure_ascii=False).encode(),
                                 headers={"User-Agent": USER_AGENT, "Content-Type": "application/json",
                                          **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read())
