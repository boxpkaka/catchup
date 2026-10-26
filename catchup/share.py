"""分享到 Catchup：从别的 App 分享过来（或复制）的链接，读原文、归入事件、写简报，返回事件编号让 App 直接打开。

分享的信号 source 为 SHARED，显示为“你分享的”。它代表用户主动表达的兴趣：记一笔 share 行为供反馈学习，
所在的事件不会再被推送回给用户。同一个链接再分享一次直接返回原来的事件。
"""
from __future__ import annotations

import logging
import re
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FetchTimeout
from datetime import datetime, timedelta

from . import personal, reader
from .config import Config
from .models import Signal
from .pipeline import brief
from .pipeline.events import assign_events, group_events
from .sources import SHARED, build_sources
from .util import clean_text

log = logging.getLogger(__name__)

URL = re.compile(r"https?://[^\s<>\"'，。；！？、）】」]+")
FETCH_WAIT = 20  # App 的读超时是 30 秒，留出写简报的时间；没读完的这次只用分享时附带的文字
_pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix="share")


def share(config: Config, store, llm, text: str, subject: str, now: datetime) -> int | None:
    """text 是分享过来的文字（通常是“标题 + 链接”），subject 是部分 App 单独给的标题。没有链接返回 None。"""
    m = URL.search(text)
    if not m:
        return None
    url = m.group(0).rstrip(".,;:!?)")
    event_id = store.signal_event(url)
    if event_id is None:
        note = clean_text(subject or text.replace(m.group(0), ""), 300)
        event_id = _add(config, store, llm, url, note, now)
    store.add_action(event_id, "share", None, now)
    return event_id


def _add(config: Config, store, llm, url: str, note: str, now: datetime) -> int:
    title, content = "", ""
    future = _pool.submit(reader.read, url)
    try:
        title, content = future.result(timeout=FETCH_WAIT)
    except FetchTimeout:
        log.info("share %s: still fetching after %ds", url, FETCH_WAIT)
    except Exception as e:
        log.info("share %s: fetch failed: %r", url, e)
    if future.done():  # 读到了就缓存原文，之后在这件事下提问不用再读；失败也记下，过几小时再试
        store.save_page(url, content, now)
    store.add([Signal(url=url, title=clean_text(title or note or url, 300), summary=_excerpt(content) or note,
                      source=SHARED, tier="primary", kind="分享", published=now)], now=now)
    window = timedelta(hours=config.ranking.get("window_hours", 72))
    assign_events(store, store.recent(now - window, now + timedelta(hours=1)), now)
    event_id = store.signal_event(url)
    if llm:
        brief.write_briefs(store, group_events(store.event_signals(event_id)), personal.focus(config, store, now), llm,
                           build_sources(config.sources), now, top=1, memory_rev=personal.memory_rev(store))
    return event_id


def _excerpt(content: str, limit: int = 600) -> str:
    """正文开头常是导航、按钮之类的短行，取前几段像正文的段落当摘要。"""
    paragraphs = [clean_text(p, limit) for p in re.split(r"\n\s*\n", content)]
    body = [p for p in paragraphs if len(p) >= 40 and not p.startswith(("#", "!["))]
    return clean_text(" ".join(body), limit)
