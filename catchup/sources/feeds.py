"""RSS / Atom 类来源：通用 feed、YouTube 频道、Google News 搜索、Reddit 版块、Techmeme。"""
from __future__ import annotations

import logging
import re
import time
import urllib.parse
import xml.etree.ElementTree as ET

from .. import http
from ..util import parse_time
from .base import Source, register

log = logging.getLogger(__name__)
ATOM = "{http://www.w3.org/2005/Atom}"


def parse_feed(data: bytes) -> list[dict]:
    """把 RSS 2.0 或 Atom 解析成统一的字段字典。"""
    root = ET.fromstring(data)
    entries = []
    for item in root.iter("item"):
        entries.append({"url": item.findtext("link", ""), "title": item.findtext("title", ""),
                        "summary": item.findtext("description", ""), "author": item.findtext("source", ""),
                        "published": parse_time(item.findtext("pubDate"))})
    for entry in root.iter(f"{ATOM}entry"):
        # Element 没有子节点时布尔值为 False，不能用 or 连接两次 find
        link = entry.find(f"{ATOM}link[@rel='alternate']")
        if link is None:
            link = entry.find(f"{ATOM}link")
        entries.append({
            "url": link.get("href", "") if link is not None else "",
            "title": entry.findtext(f"{ATOM}title", ""),
            "summary": entry.findtext(f"{ATOM}summary", "") or entry.findtext(f"{ATOM}content", ""),
            "author": entry.findtext(f"{ATOM}author/{ATOM}name", ""),
            "published": parse_time(entry.findtext(f"{ATOM}published") or entry.findtext(f"{ATOM}updated")),
        })
    return entries


class FeedSource(Source):
    """feed 类来源的公共逻辑：子类只需给出 (channel, url) 列表。单个 feed 失败只记日志。"""
    default_delay = 0

    def feeds(self) -> list[tuple[str, str]]:
        raise NotImplementedError

    def fetch(self):
        delay = self.options.get("delay", self.default_delay)
        signals = []
        for i, (channel, url) in enumerate(self.feeds()):
            if i and delay:
                time.sleep(delay)
            try:
                entries = parse_feed(http.get(url))
            except Exception as e:
                log.warning("%s/%s failed: %r", self.name, channel, e)
                continue
            signals += [self.signal(channel=channel, **entry) for entry in entries]
        return signals


@register
class Feed(FeedSource):
    type = "feed"

    def feeds(self):
        return [(f["name"], f["url"]) for f in self.options["feeds"]]


@register
class YouTube(FeedSource):
    type = "youtube"
    default_kind = "视频"
    label_format = "YouTube · {channel}"

    def feeds(self):
        return [(name, f"https://www.youtube.com/feeds/videos.xml?channel_id={cid}")
                for name, cid in self.options["channels"].items()]


@register
class GoogleNews(FeedSource):
    type = "google_news"
    default_kind = "新闻"
    label_format = "Google News"
    LOCALES = {"en": "hl=en-US&gl=US&ceid=US:en", "zh": "hl=zh-CN&gl=CN&ceid=CN:zh-Hans"}

    def feeds(self):
        window = self.options.get("window", "1d")
        return [(q["name"], f"https://news.google.com/rss/search?q={urllib.parse.quote(q['q'] + ' when:' + window)}"
                            f"&{self.LOCALES[q.get('lang', 'en')]}")
                for q in self.options["queries"]]


@register
class Reddit(FeedSource):
    """Reddit 公开 RSS，不需要 OAuth，但限流很严：版块之间拉开间隔，429 就跳过等下一轮。"""
    type = "reddit"
    default_kind = "社区"
    default_delay = 60
    label_format = "r/{channel}"

    def feeds(self):
        return [(sub, f"https://www.reddit.com/r/{sub}/new/.rss") for sub in self.options["subreddits"]]


@register
class Techmeme(Source):
    """Techmeme 编辑精选的科技头条，几分钟内更新。条目链接是 Techmeme 自己的页面，原文链接是摘要里第一个外链，
    换成原文才能和其他来源按 URL 合并；标题末尾的“(作者/媒体)”去掉，媒体记为 author。"""
    type = "techmeme"
    default_kind = "新闻"
    label_format = "Techmeme"
    FEED = "https://www.techmeme.com/feed.xml"

    def fetch(self):
        signals = []
        for e in parse_feed(http.get(self.FEED)):
            link = re.search(r'href="(https?://(?!www\.techmeme\.com/)[^"]+)"', e["summary"], re.I)
            m = re.fullmatch(r"(.+?)\s*\(([^()]*)\)", e["title"])
            title, byline = (m.group(1), m.group(2)) if m else (e["title"], "")
            signals.append(self.signal(link.group(1) if link else e["url"], title, e["summary"],
                                       author=byline.split("/")[-1].strip(), published=e["published"],
                                       extra={"techmeme": e["url"]}))
        return signals
