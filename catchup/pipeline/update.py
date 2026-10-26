"""采集之后的处理：信号归入持久化事件 → 结合记忆打分 → 模型合并重复、写简报 → 追踪进展 → 从反馈学习 → 推送。

每一步失败都不影响前面已写入的结果；没有 DEEPSEEK_API_KEY 时只做规则部分。
"""
from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone

from .. import personal
from ..config import Config
from ..llm import from_config
from ..models import Event
from ..sources import build_sources
from ..store import Store
from . import answers, brief, follow, push
from .events import assign_events, group_events

log = logging.getLogger(__name__)


def ranked(config: Config, store: Store, now: datetime) -> tuple[list, list[Event]]:
    """时间窗内的信号，以及按个人记忆排好序的事件。"""
    signals = store.recent(now - timedelta(hours=config.ranking.get("window_hours", 72)), now + timedelta(hours=1))
    return signals, personal.build_ranker(config, store, now).rank(group_events(signals), now)


def refresh_briefs(config: Config, store: Store, llm, now: datetime | None = None, top: int | None = None) -> int:
    """按当前记忆给前排事件写或重写简报（记忆变了的、有新报道的、还没有简报的）。"""
    now = now or datetime.now(timezone.utc)
    _, events = ranked(config, store, now)
    return brief.write_briefs(store, events, personal.focus(config, store, now), llm, build_sources(config.sources),
                              now, top or config.llm.get("brief_top", 25), personal.memory_rev(store))


def update(config: Config, store: Store, now: datetime | None = None, llm=None, deliver: bool = True) -> dict:
    now = now or datetime.now(timezone.utc)
    llm = llm if llm is not None else from_config(config.llm)
    sources = build_sources(config.sources)
    signals, _ = ranked(config, store, now)
    stats = {"new_events": assign_events(store, signals, now), "merged": 0, "briefs": 0, "learned": 0,
             "follow_updates": 0, "answered": 0, "pushed": 0}
    _, events = ranked(config, store, now)
    updates = []
    if llm:
        stats["merged"] = brief.merge_duplicates(store, events, llm, sources, config.llm.get("merge_top", 40))
        learned = personal.learn_from_feedback(config, store, llm, now)
        stats["learned"] = len(learned["added"]) + len(learned["updated"])
        stats["briefs"] = refresh_briefs(config, store, llm, now)
        updates = follow.run(store, llm, now)
        stats["follow_updates"] = len(updates)
        stats["answered"] = len(answers.run(store, llm, now))
        _, events = ranked(config, store, now)
    if deliver:
        stats["pushed"] = push.deliver(store, push.select(config.push, store, events, now), now, config.push)
        push.deliver_updates(config.push, updates, now)
        push.deliver_answers(config.push, store, now)
    log.info("update: %s", stats)
    return stats
