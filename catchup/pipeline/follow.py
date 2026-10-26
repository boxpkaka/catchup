"""持续追踪：用户追踪的事件，每次采集后主动搜一次后续报道，再让模型判断有没有“实质进展”。

只有实质进展（新的评测、官方澄清、许可证或价格变化、开源、社区复现等）才记入事件时间线并推送；
换个说法的重复报道只并入事件，不打扰用户。追踪 30 天后自动不再主动搜索。
"""
from __future__ import annotations

import logging
from datetime import date, datetime, timedelta

from ..models import Signal
from ..sources.deepseek import tool
from ..util import clean_text, parse_time, to_iso

log = logging.getLogger(__name__)

SEARCH_DAYS = 30
SEARCH_EVERY = timedelta(hours=1)  # 采集每 10 分钟一轮，主动搜索（按次计费）不用跟着这么频繁
MAX_FOLLOWS = 10

UPDATE_SYSTEM = """用户在追踪一件 AI 领域的事。下面给出这件事的简报和新出现的报道。
判断新报道里有没有“实质进展”：新的评测或数据、官方澄清或回应、许可证或价格变化、开源或发布可用、社区复现或问题、
后续版本等。只是换个说法重复原来的消息不算。
输出 JSON：{"substantive": true 或 false, "update": "有进展时用一句话说明新进展，不超过 50 个字；没有就输出空字符串"}"""


def run(store, llm, now: datetime, search: bool = True) -> list[dict]:
    """处理所有追踪中的事件，返回本次发现的进展 [{event_id, headline, text}]，由调用方决定是否推送。"""
    found = []
    for f in store.follows()[:MAX_FOLLOWS]:
        eid = f["event_id"]
        brief = store.briefs([eid]).get(eid)
        if brief is None:
            continue
        searched = parse_time(f["searched"])
        if search and now - (parse_time(f["created"]) or now) <= timedelta(days=SEARCH_DAYS) \
                and (searched is None or now - searched >= SEARCH_EVERY):
            added = _search(store, eid, brief["headline"], now)
            if added:
                log.info("follow %d: %d new reports from search", eid, added)
        signals = store.event_signals(eid)
        if len(signals) <= f["seen_signals"]:
            continue
        fresh = signals[f["seen_signals"]:] if f["seen_signals"] else signals[-3:]
        store.update_follow(eid, seen_signals=len(signals))
        verdict = _judge(llm, brief, fresh)
        if verdict:
            store.add_event_update(eid, verdict, now)
            found.append({"event_id": eid, "headline": brief["headline"], "text": verdict})
    return found


def _search(store, event_id: int, headline: str, now: datetime) -> int:
    since = (date.today() - timedelta(days=2)).isoformat()
    try:
        res = tool("search", {"query": f"{headline} after:{since}", "count": 8})
    except Exception as e:
        log.warning("follow search %d failed: %r", event_id, e)
        return 0
    store.update_follow(event_id, searched=to_iso(now))
    results = [r for r in res.get("results", []) if r.get("url") and r.get("rerank_score", 0) >= 1.0]
    known = store.known_urls(r["url"] for r in results)
    fresh = [Signal(url=r["url"].strip(), title=clean_text(r.get("title", ""), 300), summary=clean_text(r.get("snippet", "")),
                    source="deepseek_search", channel=r.get("site_name") or "追踪搜索", kind="新闻",
                    extra={"follow": event_id})
             for r in results if r["url"] not in known]
    if not fresh:
        return 0
    store.add(fresh, now=now)
    store.assign(event_id, [s.url for s in fresh])
    store.commit()
    return len(fresh)


def _judge(llm, brief: dict, signals: list[Signal]) -> str:
    reports = "\n".join(f"- {s.title}\n  {s.summary[:200]}" for s in signals[:8])
    try:
        raw = llm.json(UPDATE_SYSTEM, f"简报：{brief['headline']}。{brief['summary']}\n\n新报道：\n{reports}",
                       max_tokens=300)
    except Exception as e:
        log.warning("follow judge failed: %r", e)
        return ""
    text = str(raw.get("update") or "").strip()[:120]
    return text if raw.get("substantive") is True and text else ""

