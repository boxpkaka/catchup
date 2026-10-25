"""预览服务和 App 接口：GET / 返回预览页，GET /api/feed 返回排序后的事件流（仅本机），/v1/* 是 App 接口（见 app_api.py）。"""
from __future__ import annotations

import gzip
import json
import logging
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

from ..config import Config
from ..pipeline.feed import build_feed
from ..store import Store
from . import app_api

log = logging.getLogger(__name__)
STATIC = Path(__file__).parent / "static"
MAX_BODY = 64 * 1024


def make_handler(config: Config) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            log.debug("%s %s", self.address_string(), fmt % args)

        def reply(self, status: int, body: bytes, ctype: str) -> None:
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            if len(body) > 1024 and "gzip" in self.headers.get("Accept-Encoding", ""):
                body = gzip.compress(body, 6)  # 事件流 JSON 约 450KB，压缩后约 100KB，SSH 转发时明显更快
                self.send_header("Content-Encoding", "gzip")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def app(self, method: str) -> None:
            body = None
            if method == "POST":
                length = int(self.headers.get("Content-Length", 0))
                if length > MAX_BODY:
                    return self.reply(413, b'{"error": "too large"}', "application/json")
                try:
                    body = json.loads(self.rfile.read(length) or b"{}")
                except ValueError:
                    return self.reply(400, b'{"error": "bad json"}', "application/json")
            store = Store(config.db_path)
            try:
                status, payload = app_api.handle(config, store, method, urlparse(self.path).path, self.headers, body)
            finally:
                store.con.close()
            self.reply(status, json.dumps(payload, ensure_ascii=False).encode(), "application/json; charset=utf-8")

        def do_POST(self):
            if urlparse(self.path).path.startswith("/v1/"):
                return self.app("POST")
            self.reply(404, b"not found\n", "text/plain; charset=utf-8")

        def do_GET(self):
            path = urlparse(self.path).path
            if path.startswith("/v1/"):
                return self.app("GET")
            if path == "/api/feed":
                store = Store(config.db_path)  # 每个请求独立连接，sqlite 连接不跨线程
                try:
                    body = json.dumps(build_feed(config, store), ensure_ascii=False).encode()
                finally:
                    store.con.close()
                self.reply(200, body, "application/json; charset=utf-8")
            elif path in ("/", "/index.html"):
                self.reply(200, (STATIC / "index.html").read_bytes(), "text/html; charset=utf-8")
            else:
                self.reply(404, b"not found\n", "text/plain; charset=utf-8")

    return Handler


def serve(config: Config, host: str, port: int) -> None:
    ThreadingHTTPServer.daemon_threads = True
    server = ThreadingHTTPServer((host, port), make_handler(config))
    log.info("serving on http://%s:%d", host, port)
    server.serve_forever()
