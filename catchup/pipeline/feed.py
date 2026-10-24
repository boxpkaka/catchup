"""组装给前端的信息流：取时间窗内的信号 → 合并事件 → 打分排序 → 序列化。"""
from __future__ import annotations

from collections import Counter
from datetime import datetime, timedelta, timezone

from ..config import Config
from ..models import Event, Signal
from ..sources import Source, build_sources
from ..store import Store
from ..util import to_iso
from .events import cluster
from .ranking import Ranker


def build_feed(config: Config, store: Store, now: datetime | None = None, limit: int = 400) -> dict:
    now = now or datetime.now(timezone.utc)
    window = config.ranking.get("window_hours", 72)
    signals = store.recent(now - timedelta(hours=window), now + timedelta(hours=1))
    sources = build_sources(config.sources)
    events = Ranker.from_config(config.interests, config.ranking).rank(cluster(signals), now)
    counts = Counter(s.source for s in signals)
    runs = store.last_runs()
    return {
        "generated": to_iso(now),
        "window_hours": window,
        "push_count": config.ranking.get("push_count", 5),
        "total_signals": len(signals),
        "event_count": len(events),
        "events": [_event(e, sources) for e in events[:limit]],
        "sources": [{"name": name, "title": src.title, "count": counts.get(name, 0), "last_run": runs.get(name)}
                    for name, src in sorted(sources.items(), key=lambda x: -counts.get(x[0], 0))],
    }


def _label(s: Signal, sources: dict[str, Source]) -> str:
    src = sources.get(s.source)
    return src.label(s.channel) if src else s.source


def _event(e: Event, sources: dict[str, Source]) -> dict:
    lead = e.lead
    return {
        "key": e.key, "title": lead.title, "url": lead.url, "summary": lead.summary[:280],
        "time": to_iso(e.time), "score": e.score, "topics": e.topics, "reasons": e.reasons,
        "tier": lead.tier, "kind": lead.kind,
        "labels": list(dict.fromkeys(_label(s, sources) for s in e.signals)),
        "signals": [{"url": s.url, "title": s.title, "time": to_iso(s.published), "label": _label(s, sources)}
                    for s in e.signals],
    }
