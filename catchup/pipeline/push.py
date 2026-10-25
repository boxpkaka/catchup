"""决定哪些事件值得现在打扰用户，并经 hub 推到手机。

原则（产品论文的 Push-first）：宁可少推。只推模型判定重要、规则分也过线、还没推过的事件；
每次采集最多推几条、24 小时内有上限，夜里不推（留到早上那次采集）。参数见 config.json 的 push。
"""
from __future__ import annotations

import logging
import os
from datetime import datetime, timedelta

from .. import http
from ..models import Event
from .brief import final_score

log = logging.getLogger(__name__)

DEFAULTS = {
    "min_importance": 4,     # 模型给的重要性至少到“值得当天知道”
    "min_score": 1.5,        # 规则分（兴趣 + 来源 + 多源 + 热度，含时间衰减）
    "per_run": 2,
    "per_day": 6,            # 滚动 24 小时
    "quiet_hours": [23, 8],  # 本地时间 23:00～8:00 不推
    "utc_offset": 8,
    "max_age_hours": 24,     # 最新报道超过这个时间的事件不再推
}


def select(options: dict, store, events: list[Event], now: datetime) -> list[Event]:
    opt = {**DEFAULTS, **options}
    start, end = opt["quiet_hours"]
    hour = (now + timedelta(hours=opt["utc_offset"])).hour
    if (start <= hour or hour < end) if start > end else (start <= hour < end):
        return []
    room = min(opt["per_run"], opt["per_day"] - store.pushes_since(now - timedelta(hours=24)))
    if room <= 0:
        return []
    pushed, feedback = store.pushed(), store.feedback()
    briefs = store.briefs(e.id for e in events)
    candidates = [
        e for e in events
        if e.id not in pushed and feedback.get(e.id) != "not_needed" and e.id in briefs
        and briefs[e.id]["importance"] >= opt["min_importance"] and e.score >= opt["min_score"]
        and now - max(s.published for s in e.signals) <= timedelta(hours=opt["max_age_hours"])
    ]
    candidates.sort(key=lambda e: -final_score(e, briefs[e.id]))
    return candidates[:room]


def deliver(store, events: list[Event], now: datetime) -> int:
    """逐条发到 hub；单条失败只记日志，成功的才记为已推送。"""
    url, token = os.environ.get("FEED_URL"), os.environ.get("FEED_TOKEN")
    if not url or not token:
        log.warning("FEED_URL / FEED_TOKEN not set, skipping push")
        return 0
    briefs = store.briefs(e.id for e in events)
    sent = 0
    for e in events:
        b = briefs[e.id]
        message = b["summary"] + (f"\n{b['why']}" if b["why"] else "")
        try:
            http.post_json(url, {"title": b["headline"], "message": message, "click": f"catchup://event/{e.id}"},
                           {"Authorization": f"Bearer {token}"})
        except Exception as err:
            log.warning("push event %d failed: %r", e.id, err)
            continue
        store.record_push(e.id, now)
        sent += 1
    return sent
