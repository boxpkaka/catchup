"""采集之后的处理：信号归入持久化事件 → 结合记忆打分 → 模型合并重复、初筛排名靠后的新报道、给新事件写简报 → 追踪进展
→ 从反馈学习 → 推送 → 按新记忆重写旧简报（每轮一部分） → 写到期的日报、周报、月报 → 给只有名字的 HF 模型写简介
→ 翻译英文原文。

每一步失败都不影响前面已写入的结果；没有 DEEPSEEK_API_KEY 时只做规则部分。
"""
from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone

from .. import images, personal
from ..config import Config
from ..llm import from_config
from ..models import Event
from ..sources import build_sources
from ..store import Store
from . import answers, brief, follow, intros, push, reports, translate
from .events import assign_events, group_events

log = logging.getLogger(__name__)
# 推送之后每轮最多重写的旧简报（记忆变了、补 AI 解读、补读原文）。一条约 3 秒，记忆一变要重写两百条，
# 一次写完要十分钟以上，会挡住下一轮采集；分到几轮里写
REWRITES = 30


def ranked(config: Config, store: Store, now: datetime) -> tuple[list, list[Event]]:
    """时间窗内的信号，以及按个人记忆排好序的事件。"""
    signals = store.recent(now - timedelta(hours=config.ranking.get("window_hours", 72)), now + timedelta(hours=1))
    return signals, personal.build_ranker(config, store, now).rank(group_events(signals), now)


def refresh_briefs(config: Config, store: Store, llm, now: datetime | None = None, top: int | None = None,
                   rewrites: int | None = None, also: set[int] = frozenset()) -> int:
    """按当前记忆给前排事件写或重写简报（记忆变了的、有新报道的、还没有简报的），只是要重写的最多 rewrites 条。
    不指定 top 时（每轮采集后）还包括排在后面但正热门的事件，见 brief.candidates；also 是初筛选中的事件 id，见 brief.screen。"""
    now = now or datetime.now(timezone.utc)
    _, events = ranked(config, store, now)
    sources = build_sources(config.sources)
    pool = events[:top] if top else brief.candidates(events, config.llm.get("brief_top", 25), sources)
    pool += [e for e in events if e.id in also and e not in pool]
    return brief.write_briefs(store, pool, personal.focus(config, store, now), llm, sources,
                              now, len(pool), personal.memory_rev(store), brief.READ_WAIT, rewrites)


def update(config: Config, store: Store, now: datetime | None = None, llm=None, deliver: bool = True) -> dict:
    now = now or datetime.now(timezone.utc)
    llm = llm if llm is not None else from_config(config.llm)
    sources = build_sources(config.sources)
    signals, _ = ranked(config, store, now)
    arrived = {s.url for s in signals if s.event_id is None}
    stats = {"new_events": assign_events(store, signals, now), "merged": 0, "screened": 0, "briefs": 0, "learned": 0,
             "follow_updates": 0, "answered": 0, "pushed": 0, "topics": 0, "reports": 0, "intros": 0, "translated": 0}
    _, events = ranked(config, store, now)
    boards = {name for name, s in sources.items() if s.board}
    updates = []
    if llm:
        if stats["new_events"]:  # 每 10 分钟一轮，没有新事件时前排事件上一轮已经查过重复
            pool = brief.candidates(events, config.llm.get("merge_top", 40), sources)
            stats["merged"] = brief.merge_duplicates(store, pool, llm, sources, len(pool))
        # 这一轮来了新报道、排不进写简报候选池、还没有简报的事件（榜单上的不用逐条判断）
        pool = brief.candidates(events, config.llm.get("brief_top", 25), sources)
        fresh = [e for e in events if any(s.url in arrived for s in e.signals) and e not in pool
                 and not all(s.source in boards for s in e.signals)]
        briefed = store.briefs(e.id for e in fresh)
        screened = brief.screen([e for e in fresh if e.id not in briefed], personal.focus(config, store, now),
                                llm, sources)
        stats["screened"] = len(screened)
        learned = personal.learn_from_feedback(config, store, llm, now)
        stats["learned"] = len(learned["added"]) + len(learned["updated"])
        stats["briefs"] = refresh_briefs(config, store, llm, now, rewrites=0, also=screened)  # 新事件先写，赶上这一轮推送
        updates = follow.run(store, llm, now)
        stats["follow_updates"] = len(updates)
        stats["answered"] = len(answers.run(store, llm, now))
        _, events = ranked(config, store, now)
    if deliver:
        picked = push.select(config.push, store, events, now, push.strict_sources(sources))
        for e in picked:  # 先取好封面，手机收到推送时马上拿得到大图
            images.cover(config, e.id, e.signals)
        stats["pushed"] = push.deliver(store, picked, now, config.push)
        push.deliver_updates(config.push, updates, now)
        push.deliver_answers(config.push, store, now)
        reports.deliver(config.push, store, now)
    if llm:  # 放在推送之后，慢了不耽误推送；写好的周报月报下一轮推送
        stats["briefs"] += refresh_briefs(config, store, llm, now, rewrites=REWRITES)
        stats["topics"] = brief.assign_topics(store, events, llm, [i["name"] for i in config.interests])
        stats["reports"] = len(reports.run(config, store, llm, now))
        stats["intros"] = intros.run(store, llm, sources, now)  # 写成中文，翻译那一步就不用再翻
        stats["translated"] = translate.run(store, llm, events, [
            x["summary"][:translate.SUMMARY_CHARS] for name in boards for x in store.board(name)])
    log.info("update: %s", stats)
    return stats
