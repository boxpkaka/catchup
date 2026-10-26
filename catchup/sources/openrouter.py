"""OpenRouter 上新上架的模型：闭源 API 模型发布后通常一小时内就上架，隐名测试的模型也会先出现在这里。

接口 GET https://openrouter.ai/api/v1/models 免费、不需要 Key，返回全部模型，created 是上架时间（Unix 秒），
pricing 是每个 token 的美元价格（字符串）。只取最新上架的 limit 个，避免第一次运行把几百个旧模型都导进来。
"""
from __future__ import annotations

from datetime import datetime, timezone

from .. import http
from .base import Source, register

API = "https://openrouter.ai/api/v1/models"


@register
class OpenRouter(Source):
    type = "openrouter"
    default_tier = "primary"
    default_kind = "模型"
    label_format = "OpenRouter"

    def fetch(self):
        models = sorted(http.get_json(API)["data"], key=lambda m: m["created"], reverse=True)
        signals = []
        for m in models[:self.options.get("limit", 20)]:
            price = m.get("pricing") or {}
            summary = m.get("description") or ""
            if price.get("prompt") and price.get("completion"):
                summary = (f"每百万 token：输入 ${float(price['prompt']) * 1e6:g}，"
                           f"输出 ${float(price['completion']) * 1e6:g}。{summary}")
            signals.append(self.signal(
                f"https://openrouter.ai/{m['id']}", m["name"], summary, channel=m["id"].split("/")[0],
                published=datetime.fromtimestamp(m["created"], timezone.utc),
                extra={"context": m.get("context_length"), "pricing": price}))
        return signals
