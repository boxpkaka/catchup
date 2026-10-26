"""决定哪些事件值得现在打扰用户，并经 hub 推到手机。

原则：有价值就推，不限条数。只推模型判定重要、规则分也过线、还没推过的事件；
夜里不推（攒到早上合成一条早报）。参数见 config.json 的 push。
"""
from __future__ import annotations

import logging
import os
from datetime import datetime, timedelta

from .. import http
from ..models import Event
from ..sources import SHARED
from .brief import final_score

log = logging.getLogger(__name__)

DEFAULTS = {
    "min_importance": 4,     # 模型给的重要性至少到“值得当天知道”
    "min_score": 1.5,        # 规则分（兴趣 + 来源 + 多源 + 热度，含时间衰减）
    "quiet_hours": [23, 8],  # 本地时间 23:00～8:00 不推
    "utc_offset": 8,
    "max_age_hours": 24,     # 最新报道超过这个时间的事件不再推
}


def quiet(options: dict, now: datetime) -> bool:
    opt = {**DEFAULTS, **options}
    start, end = opt["quiet_hours"]
    hour = (now + timedelta(hours=opt["utc_offset"])).hour
    return (start <= hour or hour < end) if start > end else (start <= hour < end)


def morning(options: dict, store, now: datetime) -> bool:
    """免打扰结束后的第一个小时、且这一小时里还没推过：夜里攒下的事件合成一条早报推送。
    采集每 10 分钟一轮，这一小时里会跑好几轮，早报只发一次。utc_offset 是整小时，UTC 的整点就是本地的整点。"""
    opt = {**DEFAULTS, **options}
    return ((now + timedelta(hours=opt["utc_offset"])).hour == opt["quiet_hours"][1]
            and not store.pushes_since(now.replace(minute=0, second=0, microsecond=0)))


def select(options: dict, store, events: list[Event], now: datetime) -> list[Event]:
    opt = {**DEFAULTS, **options}
    if quiet(opt, now):
        return []
    pushed, feedback = store.pushed(), store.feedback()
    briefs = store.briefs(e.id for e in events)
    candidates = [
        e for e in events
        if e.id not in pushed and feedback.get(e.id) != "not_needed" and e.id in briefs
        and not any(s.source == SHARED for s in e.signals)  # 用户自己分享的，不用再推给他
        and briefs[e.id]["importance"] >= opt["min_importance"] and e.score >= opt["min_score"]
        and now - max(s.published for s in e.signals) <= timedelta(hours=opt["max_age_hours"])
    ]
    candidates.sort(key=lambda e: -final_score(e, briefs[e.id]))
    return candidates


def _send(title: str, message: str, click: str) -> bool:
    url, token = os.environ.get("FEED_URL"), os.environ.get("FEED_TOKEN")
    if not url or not token:
        log.warning("FEED_URL / FEED_TOKEN not set, skipping push")
        return False
    try:
        http.post_json(url, {"title": title, "message": message, "click": click}, {"Authorization": f"Bearer {token}"})
        return True
    except Exception as err:
        log.warning("push %r failed: %r", title, err)
        return False


def deliver(store, events: list[Event], now: datetime, options: dict | None = None) -> int:
    """逐条发到 hub，成功的才记为已推送。早上第一轮且攒了两件以上时合成一条早报，点开进首页。"""
    briefs = store.briefs(e.id for e in events)
    if len(events) >= 2 and morning(options or {}, store, now):
        lines = "\n".join(f"· {briefs[e.id]['headline']}" for e in events)
        if not _send(f"夜里有 {len(events)} 件值得知道", lines, "catchup://home"):
            return 0
        for e in events:
            store.record_push(e.id, now)
        return len(events)
    sent = 0
    for e in events:
        b = briefs[e.id]
        if _send(b["headline"], b["summary"] + (f"\n{b['why']}" if b["why"] else ""), f"catchup://event/{e.id}"):
            store.record_push(e.id, now)
            sent += 1
    return sent


def deliver_answers(options: dict, store, now: datetime, limit: int = 3) -> int:
    """替你留意的问题有了答案：推送一次，点开回到原来的对话；免打扰时段先攒着，下一轮再推。"""
    if quiet(options, now):
        return 0
    sent = 0
    for q in [q for q in store.questions("answered") if not q["notified"]][:limit]:
        if _send("你问过的问题有答案了", f"{q['text']}\n{q['answer']}", f"catchup://thread/{q['thread_id']}"):
            store.update_question(q["id"], notified=1)
            sent += 1
    return sent


def deliver_updates(options: dict, updates: list[dict], now: datetime, limit: int = 3) -> int:
    """追踪事件的实质进展；免打扰时段只记入时间线、不推送。"""
    if quiet(options, now):
        return 0
    return sum(_send(f"追踪：{u['headline']}", u["text"], f"catchup://event/{u['event_id']}") for u in updates[:limit])
