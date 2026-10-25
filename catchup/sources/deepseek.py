"""DeepSeek Tools API 来源（从 ../infofeed/feed.py 迁入）：联网搜索主题、监控固定页面的变化。

接口：POST https://api.deepseek.com/v1/tools/{search,fetch}，Bearer 认证，凭据是 .env 的 DEEPSEEK_API_KEY。
search 结果字段为 url/title/snippet/site_name/rerank_score；fetch 返回的 Markdown 几乎每个标点都被反斜杠转义。
"""
from __future__ import annotations

import hashlib
import logging
import os
import re
from datetime import date, timedelta
from pathlib import Path

from .. import http
from .base import Source, register

log = logging.getLogger(__name__)
API = "https://api.deepseek.com/v1/tools"


def tool(endpoint: str, payload: dict) -> dict:
    return http.post_json(f"{API}/{endpoint}", payload,
                          {"Authorization": f"Bearer {os.environ['DEEPSEEK_API_KEY']}"}, timeout=60)


def unescape_md(text: str) -> str:
    return re.sub(r"\\([\\`*_{}\[\]()#+\-.!|:/<>\"'=,~&?%;@$^])", r"\1", text)


@register
class DeepSeekSearch(Source):
    """按主题联网搜索，补上固定信息源覆盖不到的新闻。频道是网站名，便于在来源列表里区分。"""
    type = "deepseek_search"
    default_kind = "新闻"

    def fetch(self):
        since = (date.today() - timedelta(days=self.options.get("lookback_days", 1))).isoformat()
        min_score = self.options.get("min_score", 1.0)
        signals = []
        for q in self.options["queries"]:
            try:
                res = tool("search", {"query": f"{q['query']} after:{since}", "count": q.get("count", 10)})
            except Exception as e:
                log.warning("%s/%s failed: %r", self.name, q["name"], e)
                continue
            for r in res.get("results", []):
                if r.get("url") and r.get("rerank_score", 0) >= min_score:
                    signals.append(self.signal(r["url"], r.get("title", ""), r.get("snippet", ""),
                                               channel=r.get("site_name") or q["name"], extra={"query": q["name"]}))
        return signals


@register
class PageWatch(Source):
    """监控固定页面（如更新日志），页面变化时产出一条信号，摘要是新增的行。

    上一次的页面内容存在 state_dir（默认 data/pages）；首次运行只存基线不产出信号。
    信号 URL 带上新内容的哈希，因为 URL 是信号主键，同一页面的每次变化都要算新信号。
    """
    type = "page_watch"
    default_tier = "official"
    default_kind = "更新日志"

    def fetch(self):
        state = Path(self.options.get("state_dir", "data/pages"))
        state.mkdir(parents=True, exist_ok=True)
        signals = []
        for page in self.options["pages"]:
            try:
                content = unescape_md(tool("fetch", {"url": page["url"]}).get("content", ""))
            except Exception as e:
                log.warning("%s/%s failed: %r", self.name, page["name"], e)
                continue
            snapshot = state / (hashlib.sha1(page["url"].encode()).hexdigest()[:16] + ".txt")
            old = snapshot.read_text(encoding="utf-8") if snapshot.exists() else None
            snapshot.write_text(content, encoding="utf-8")
            if old is None or old == content:
                continue
            seen = set(old.splitlines())
            added = [line for line in content.splitlines() if line.strip() and line not in seen]
            if added:
                digest = hashlib.sha256(content.encode()).hexdigest()[:12]
                signals.append(self.signal(f"{page['url']}#{digest}", f"{page['name']} 有更新", " ".join(added),
                                           channel=page["name"]))
        return signals
