"""DeepSeek 对话接口的最小封装：只用标准库，输出 JSON。

简报、合并这类短任务用 deepseek-flash 并关闭思考：实测 1 秒内返回；开着思考时几百个 token 都花在推理上。
"""
from __future__ import annotations

import json
import logging
import os

from . import http

log = logging.getLogger(__name__)
API = "https://api.deepseek.com/chat/completions"


class LLM:
    def __init__(self, key: str, model: str = "deepseek-flash"):
        self.key = key
        self.model = model

    def json(self, system: str, user: str, max_tokens: int = 800) -> dict:
        body = {"model": self.model, "temperature": 0.2, "max_tokens": max_tokens,
                "response_format": {"type": "json_object"}, "thinking": {"type": "disabled"},
                "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]}
        resp = http.post_json(API, body, {"Authorization": f"Bearer {self.key}"}, timeout=90)
        return json.loads(resp["choices"][0]["message"]["content"])


def from_config(options: dict) -> LLM | None:
    """没有配置 DEEPSEEK_API_KEY 时返回 None，流水线跳过简报和合并，其余照常运行。"""
    key = os.environ.get("DEEPSEEK_API_KEY")
    if not key:
        log.warning("DEEPSEEK_API_KEY not set, skipping briefs")
        return None
    return LLM(key, options.get("model", "deepseek-flash"))
