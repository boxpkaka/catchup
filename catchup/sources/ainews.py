"""AINews（Latent Space 每个工作日的 AI 日报）：免费拿到 X 上最热的 AI 讨论，替代付费的 X API。

每期 RSS 的 content:encoded 是整期 HTML。只取“Top tweets (by engagement)”这一节：它后面的第一个列表里每项是一条 X 帖子
和互动数，互动数写法不统一（“30.4K — 标题”“标题 — 19,600”“标题 (~6.5K)”）。没有这一节的期次直接跳过。
信号 URL 用帖子本身，这样同一条帖子从 HN 或分享进来时能按 URL 合并；摘要取正文里引用同一条帖子的那一条详述。
"""
from __future__ import annotations

import logging
import re
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

from .. import http
from ..util import clean_text
from .base import Source, register

log = logging.getLogger(__name__)
FEED = "https://latent.space/feeds/news.xml"
CONTENT = "{http://purl.org/rss/1.0/modules/content/}encoded"
MARKER = "Top tweets (by engagement)"
POST = re.compile(r"https://(?:x|twitter)\.com/(\w+)/status/(\d+)")
COUNT = re.compile(r"~?([\d.,]+)\s*([KM]?)", re.I)
TWITTER_EPOCH_MS = 1288834974657  # 帖子 id 是 snowflake，高位是相对这个时间的毫秒数


@register
class AINews(Source):
    type = "ainews"
    default_kind = "社区"
    label_format = "X · @{channel}"

    def fetch(self):
        root = ET.fromstring(http.get(self.options.get("url", FEED)))
        signals = []
        for item in root.iter("item"):
            body = item.findtext(CONTENT, "")
            start = body.find(MARKER)
            if start < 0:
                log.info("%s: no top tweets in %s", self.name, item.findtext("link"))
                continue
            top = re.search(r"<(ul|ol)>(.*?)</\1>", body[start:], re.S)
            for li in re.findall(r"<li>(.*?)</li>", top.group(2) if top else "", re.S):
                post = POST.search(li)
                if not post:
                    continue
                handle, pid = post.groups()
                title, likes = split_engagement(clean_text(li))
                signals.append(self.signal(
                    f"https://x.com/{handle}/status/{pid}", title, detail(body[:start], pid), channel=handle,
                    published=datetime.fromtimestamp(((int(pid) >> 22) + TWITTER_EPOCH_MS) // 1000, timezone.utc),
                    heat=likes / 10000, extra={"likes": likes, "issue": item.findtext("link", "")}))
        return signals


def split_engagement(text: str) -> tuple[str, int]:
    """把热帖条目拆成 (标题, 互动数)，认不出互动数时为 0。"""
    m = re.fullmatch(r"(.+?)\s*\((~?[\d.,]+\s*[KM]?)\)", text, re.I)
    if m:
        return m.group(1), _count(m.group(2))
    parts = re.split(r"\s+[—–]\s+", text)
    if len(parts) > 1:
        if COUNT.fullmatch(parts[0]):
            return " — ".join(parts[1:]), _count(parts[0])
        if COUNT.fullmatch(parts[-1]):
            return " — ".join(parts[:-1]), _count(parts[-1])
    return text, 0


def _count(text: str) -> int:
    m = COUNT.fullmatch(text.strip())
    return round(float(m.group(1).replace(",", "")) * {"k": 1000, "m": 1000000}.get(m.group(2).lower(), 1))


def detail(recap: str, pid: str) -> str:
    """正文里第一条引用这条帖子的列表项。按 <li> 切开，每段到下一个 </li> 为止就是该项自己的文字（不含嵌套子项）。"""
    for part in recap.split("<li>")[1:]:
        own = part.split("</li>", 1)[0]
        if f"/status/{pid}" in own:
            return clean_text(own)
    return ""
