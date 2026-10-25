"""采集之后的处理：信号归入持久化事件 → 打分 → 模型合并重复、写简报 → 决定推送。

每一步失败都不影响前面已写入的结果；没有 DEEPSEEK_API_KEY 时只做规则部分。
"""
from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone

from ..config import Config
from ..llm import from_config
from ..sources import build_sources
from ..store import Store
from . import brief, push
from .events import assign_events, group_events
from .ranking import Ranker

log = logging.getLogger(__name__)


def update(config: Config, store: Store, now: datetime | None = None, llm=None, deliver: bool = True) -> dict:
    now = now or datetime.now(timezone.utc)
    llm = llm if llm is not None else from_config(config.llm)
    sources = build_sources(config.sources)
    ranker = Ranker.from_config(config.interests, config.ranking)

    def ranked():
        signals = store.recent(now - timedelta(hours=config.ranking.get("window_hours", 72)), now + timedelta(hours=1))
        return signals, ranker.rank(group_events(signals), now)

    signals, _ = ranked()
    stats = {"new_events": assign_events(store, signals, now), "merged": 0, "briefs": 0, "pushed": 0}
    events = ranker.rank(group_events(signals), now)
    if llm:
        stats["merged"] = brief.merge_duplicates(store, events, llm, sources, config.llm.get("merge_top", 40))
        if stats["merged"]:
            _, events = ranked()
        stats["briefs"] = brief.write_briefs(store, events, config.interests, llm, sources, now,
                                             config.llm.get("brief_top", 25))
    if deliver:
        stats["pushed"] = push.deliver(store, push.select(config.push, store, events, now), now)
    log.info("update: %s", stats)
    return stats
