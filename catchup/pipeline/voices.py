"""报告的评论材料：给报告挑出的几件大事找各方的看法，再加上这段时间专家博客的文章，让模型写观察时有依据、有不同声音。

- Hacker News：事件里 HN 帖子的顶层评论，按下面的回复数排，取前几条（Algolia items 接口，不用登录）；
- Reddit：帖子的 RSS 里的评论（reader.read）；限流严，每份报告最多读几帖；
- X：AINews 收进来、和这件事归在一起的热帖，帖子本身就是看法，不用再取；
- 网络：DeepSeek 搜索“标题 评论 分析”，去掉这件事报道本身的链接；
- 专家：这段时间专家博客（kind 为“观点”的来源，含 Latent Space 每个工作日的 AINews）的正文开头。
每条是 {"who", "where", "text", "url", "event"}，专家文章的 event 为空。取不到的跳过，不影响写报告。
"""
from __future__ import annotations

import logging
import re
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from urllib.parse import urlparse

from .. import http, reader
from ..sources.deepseek import tool
from ..util import clean_text

log = logging.getLogger(__name__)
HN_ITEM = "https://hn.algolia.com/api/v1/items/{}"
HN_COMMENTS, REDDIT_COMMENTS, REDDIT_THREADS, SEARCH_RESULTS = 4, 5, 3, 4
COMMENT_CHARS, ARTICLE_CHARS = 500, 2500
EXPERT_KIND = "观点"
X_SOURCE = "ainews"  # 来源类型，见 sources/ainews.py
WORKERS = 6


def gather(store, sources: dict, event_ids: list[int], since: datetime, until: datetime, experts: int) -> list[dict]:
    """event_ids 是要找评论的大事（重要的在前），experts 是最多读几篇专家文章。"""
    found, jobs, threads = [], [], 0
    for event_id in event_ids:
        signals = store.event_signals(event_id)
        brief = store.briefs([event_id]).get(event_id) or {}
        headline = brief.get("headline") or (signals[0].title if signals else "")
        for s in signals:
            if s.source in sources and sources[s.source].type == X_SOURCE:
                found.append({"who": f"@{s.channel}", "where": "X", "text": clean_text(f"{s.title} {s.summary}",
                                                                                       COMMENT_CHARS),
                              "url": s.url, "event": event_id})
        hn = next((s.extra["hn"] for s in signals if s.extra.get("hn")), None)
        if hn:
            jobs.append((_hn, hn, event_id))
        thread = next((s.url for s in signals if reader.REDDIT.match(s.url)), None)
        if thread and threads < REDDIT_THREADS:
            threads += 1
            jobs.append((_reddit, thread, event_id))
        if headline:
            jobs.append((_search, (headline, {s.url for s in signals}), event_id))
    picked = _experts(store, set(event_ids), since, until, experts)
    jobs += [(_article, s, s.event_id if s.event_id in event_ids else None) for s in picked]
    with ThreadPoolExecutor(WORKERS) as pool:
        for got in pool.map(_run, jobs):
            found += got
    return found


def _run(job) -> list[dict]:
    fn, arg, event_id = job
    try:
        return [{**v, "event": event_id} for v in fn(arg)]
    except Exception as e:
        log.info("voices %s %s: %r", fn.__name__, getattr(arg, "url", arg), e)
        return []


def _hn(url: str) -> list[dict]:
    """顶层评论按下面的回复数排：讨论最多的往往是最有料或最有争议的。"""
    item = http.get_json(HN_ITEM.format(re.search(r"id=(\d+)", url).group(1)))

    def size(c):
        return 1 + sum(size(x) for x in c.get("children") or [])

    kids = sorted((c for c in item.get("children") or [] if c.get("text")), key=size, reverse=True)
    return [{"who": f"HN 用户 {c.get('author')}", "where": "Hacker News", "text": clean_text(c["text"], COMMENT_CHARS),
             "url": f"https://news.ycombinator.com/item?id={c['id']}"} for c in kids[:HN_COMMENTS]]


def _reddit(url: str) -> list[dict]:
    _, body = reader.read(url)
    sub = reader.REDDIT.match(url).group(1)
    out = []
    for line in body.split("\n\n评论：\n", 1)[1].splitlines() if "\n\n评论：\n" in body else []:
        m = re.match(r"- (\S+?)：(.+)", line)
        if m and len(m.group(2)) > 30:  # 太短的多半是附和、玩笑
            out.append({"who": f"u/{m.group(1)}", "where": f"r/{sub}", "text": clean_text(m.group(2), COMMENT_CHARS),
                        "url": url})
    return out[:REDDIT_COMMENTS]


def _search(arg: tuple[str, set[str]]) -> list[dict]:
    headline, own = arg
    res = tool("search", {"query": f"{headline} 评论 分析", "count": SEARCH_RESULTS + 3})
    out = []
    for r in res.get("results") or []:
        if r.get("url") in own or not r.get("snippet"):
            continue
        out.append({"who": _site(r.get("site_name") or "", r["url"]), "where": "网络",
                    "text": clean_text(f"{r.get('title', '')}：{r['snippet']}", COMMENT_CHARS), "url": r["url"]})
    return out[:SEARCH_RESULTS]


def _site(name: str, url: str) -> str:
    """网站名太长或像文章标题（内容农场常把标题当站名）时用域名，读者能自己判断出处。"""
    if name and len(name) <= 12 and not re.search(r"[:：|｜]", name):
        return name
    return (urlparse(url).hostname or "网络文章").removeprefix("www.")


def _experts(store, events: set[int], since: datetime, until: datetime, limit: int) -> list:
    """这段时间的专家文章：先挑和大事归在一起的，再挑 AINews 日报，其余按新到旧。"""
    posts = [s for s in store.recent(since, until) if s.kind == EXPERT_KIND]
    posts.sort(key=lambda s: (s.event_id not in events, not s.title.startswith("[AINews]"), -s.published.timestamp()))
    return posts[:limit]


def _article(s) -> list[dict]:
    try:
        _, text = reader.read(s.url)
    except Exception as e:  # 读不到正文就用 RSS 里的摘要
        log.info("voices article %s: %r", s.url, e)
        text = s.summary
    text = clean_text(text, ARTICLE_CHARS)
    return [{"who": s.channel or s.author or "专家博客", "where": "专家博客", "title": s.title, "text": text,
             "url": s.url}] if text else []
