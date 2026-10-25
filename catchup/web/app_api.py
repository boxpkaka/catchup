"""手机 App 的接口，挂在 /v1 下，经 Caddy 的 https://43.139.27.91:8443/v1/* 对外。

认证：App 首次启动调用 POST /v1/devices 换取令牌。还没有任何已批准设备时自动批准（只有本人使用），
之后的新设备处于待确认状态，需要在服务器上运行 `python3 -m catchup devices approve <id>`。
数据库只存令牌的 sha256。
"""
from __future__ import annotations

import hashlib
import re
import secrets
from datetime import datetime, timedelta, timezone

from ..config import Config
from ..models import Event
from ..pipeline.brief import final_score
from ..pipeline.events import group_events
from ..pipeline.ranking import Ranker
from ..sources import Source, build_sources
from ..store import Store
from ..util import to_iso

FEED_LIMIT = 80
MAX_PENDING_DEVICES = 10
FEEDBACK_VALUES = {"useful", "not_needed", None}


def handle(config: Config, store: Store, method: str, path: str, headers, body: dict | None) -> tuple[int, dict]:
    now = datetime.now(timezone.utc)
    if method == "POST" and path == "/v1/devices":
        return register(store, (body or {}).get("name", ""), now)
    device = store.device_by_token(_hash(_bearer(headers)))
    if device is None:
        return 401, {"error": "unknown device"}
    if path == "/v1/me":
        return 200, device
    if not device["approved"]:
        return 403, {"error": "device not approved", "device": device}
    store.touch_device(device["id"], now)
    if method == "GET" and path == "/v1/feed":
        return 200, feed(config, store, now)
    if m := re.fullmatch(r"/v1/events/(\d+)", path):
        detail = event_detail(config, store, int(m.group(1)), now) if method == "GET" else None
        return (200, detail) if detail else (404, {"error": "not found"})
    if method == "POST" and (m := re.fullmatch(r"/v1/events/(\d+)/feedback", path)):
        value = (body or {}).get("value")
        if value not in FEEDBACK_VALUES:
            return 400, {"error": "bad value"}
        store.set_feedback(int(m.group(1)), value, now)
        return 200, {"ok": True}
    return 404, {"error": "not found"}


def register(store: Store, name: str, now: datetime) -> tuple[int, dict]:
    devices = store.devices()
    if sum(not d["approved"] for d in devices) >= MAX_PENDING_DEVICES:
        return 429, {"error": "too many pending devices"}
    approved = not any(d["approved"] for d in devices)
    token = secrets.token_urlsafe(32)
    device_id = store.add_device(str(name)[:80], _hash(token), approved, now)
    return 200, {"id": device_id, "token": token, "approved": approved}


def feed(config: Config, store: Store, now: datetime) -> dict:
    sources = build_sources(config.sources)
    events = _ranked(config, store, now)
    briefs, feedback, pushed = store.briefs(e.id for e in events), store.feedback(), store.pushed()
    events = [e for e in events if feedback.get(e.id) != "not_needed"]
    events.sort(key=lambda e: -final_score(e, briefs.get(e.id)))
    events = events[:FEED_LIMIT]
    created = store.event_created(e.id for e in events)
    return {"generated": to_iso(now),
            "events": [_event(e, briefs.get(e.id), feedback.get(e.id), e.id in pushed, created.get(e.id), sources)
                       for e in events]}


def event_detail(config: Config, store: Store, event_id: int, now: datetime) -> dict | None:
    signals = store.event_signals(event_id)
    if not signals:
        return None
    sources = build_sources(config.sources)
    [event] = Ranker.from_config(config.interests, config.ranking).rank(group_events(signals), now)
    brief = store.briefs([event_id]).get(event_id)
    out = _event(event, brief, store.feedback().get(event_id), event_id in store.pushed(),
                 store.event_created([event_id]).get(event_id), sources)
    out["signals"] = [{"title": s.title, "url": s.url, "label": _label(s, sources), "time": to_iso(s.published),
                       "summary": s.summary[:240]} for s in reversed(signals)]
    return out


def _ranked(config: Config, store: Store, now: datetime) -> list[Event]:
    window = config.ranking.get("window_hours", 72)
    signals = store.recent(now - timedelta(hours=window), now + timedelta(hours=1))
    return Ranker.from_config(config.interests, config.ranking).rank(group_events(signals), now)


def _event(e: Event, brief: dict | None, feedback: str | None, pushed: bool, created, sources) -> dict:
    lead = e.lead
    return {
        "id": e.id,
        "headline": brief["headline"] if brief else lead.title,
        "summary": brief["summary"] if brief else lead.summary[:160],
        "why": brief["why"] if brief else "",
        "importance": brief["importance"] if brief else None,
        "ai": brief is not None,
        "highlight": bool(brief and brief["importance"] >= 4),
        "score": final_score(e, brief),
        "topics": e.topics,
        "reasons": e.reasons,
        "url": lead.url,
        "time": to_iso(max(s.published for s in e.signals)),
        "first_seen": to_iso(created),
        "source_count": len(e.signals),
        "sources": list(dict.fromkeys(_label(s, sources) for s in e.signals))[:6],
        "feedback": feedback,
        "pushed": pushed,
    }


def _label(signal, sources: dict[str, Source]) -> str:
    src = sources.get(signal.source)
    return src.label(signal.channel) if src else signal.source


def _bearer(headers) -> str:
    value = headers.get("Authorization", "")
    return value[7:] if value.startswith("Bearer ") else ""


def _hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()
