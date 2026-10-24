from __future__ import annotations

import time
import urllib.parse

from .. import http
from ..util import parse_time
from .base import Source, register

API = "https://hn.algolia.com/api/v1/search_by_date"


@register
class HackerNews(Source):
    type = "hackernews"
    default_kind = "社区"
    label_format = "Hacker News"

    def fetch(self):
        since = int(time.time()) - self.options.get("lookback_hours", 24) * 3600
        min_points = self.options.get("min_points", 20)
        signals = []
        for q in self.options["queries"]:
            params = urllib.parse.urlencode({"query": q, "tags": "story",
                                             "numericFilters": f"points>={min_points},created_at_i>{since}"})
            for h in http.get_json(f"{API}?{params}")["hits"]:
                hn = f"https://news.ycombinator.com/item?id={h['objectID']}"
                points = h.get("points") or 0
                # 外链优先：这样同一篇文章在 HN 和其他来源出现时能按 URL 合并
                signals.append(self.signal(
                    h.get("url") or hn, h["title"], channel=q, author=h.get("author", ""),
                    published=parse_time(h["created_at"]), heat=points / 100,
                    extra={"hn": hn, "points": points, "comments": h.get("num_comments")}))
        return signals
