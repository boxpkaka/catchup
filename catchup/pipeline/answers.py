"""替你留意的问题：对话里暂时没有答案的问题（什么时候开源、多少钱、支不支持中文……），之后每次采集都看看有没有答案。

候选报道是上次检查之后新采集到的：同一事件里的，以及标题摘要命中问题关键词的；每天还会主动搜一次。
模型确认回答了问题，才把答案写回原来的对话并推送（推送见 push.deliver_answers）；30 天没有答案就不再留意。
"""
from __future__ import annotations

import logging
from datetime import date, datetime, timedelta

from ..models import Signal
from ..sources.deepseek import tool
from ..util import clean_text, parse_time, to_iso
from .ranking import keyword_in

log = logging.getLogger(__name__)

WATCH_DAYS = 30
MAX_QUESTIONS = 10
SEARCH_EVERY = timedelta(hours=24)
MAX_CANDIDATES = 8

ANSWER_SYSTEM = """用户之前问过一个当时还没有答案的问题。下面是之后新出现的报道（编号 [1]、[2]…）。
判断这些报道是否明确回答了他的问题；只是相关、没有给出答案的不算，猜测和传闻也不算。
输出 JSON：{"answered": true 或 false, "answer": "回答了就用一句话直接给出答案，不超过 60 个字；没有就输出空字符串",
"source": 给出答案的报道编号}"""


def run(store, llm, now: datetime, search: bool = True) -> list[dict]:
    """检查所有在留意的问题，返回本次找到答案的 [{id, text, answer}]。"""
    found = []
    for q in store.questions("open")[:MAX_QUESTIONS]:
        if now - (parse_time(q["created"]) or now) > timedelta(days=WATCH_DAYS):
            store.update_question(q["id"], status="expired")
            continue
        since = parse_time(q["checked"] or q["created"]) or now
        if search and (not q["searched"] or now - (parse_time(q["searched"]) or now) >= SEARCH_EVERY):
            _search(store, q, now)
        candidates = _candidates(store, q, since)
        store.update_question(q["id"], checked=to_iso(now))
        if not candidates:
            continue
        verdict = _judge(llm, q, candidates)
        if verdict is None:
            continue
        answer, signal = verdict
        store.update_question(q["id"], status="answered", answer=answer, answer_url=signal.url, answered=to_iso(now))
        store.add_message(q["thread_id"], "assistant",
                          f"**你之前问的“{q['text']}”现在有答案了：**{answer}（[来源]({signal.url})）", now,
                          {"answer_to": q["id"]})
        found.append({"id": q["id"], "text": q["text"], "answer": answer})
    return found


def _candidates(store, q: dict, since: datetime) -> list[Signal]:
    """上次检查之后新采集到的报道：同一事件的排前面，其余要命中至少两个关键词（关键词不足两个时全部命中）。"""
    keywords = [k.lower() for k in q["keywords"]]
    need = min(2, len(keywords))
    scored = []
    for s in store.fetched_since(since):
        same = q["event_id"] is not None and s.event_id == q["event_id"]
        text = f"{s.title} {s.summary}".lower()
        hits = sum(keyword_in(k, text) for k in keywords)
        if same or (need and hits >= need):
            scored.append((not same, -hits, s))
    return [s for *_, s in sorted(scored, key=lambda x: x[:2])][:MAX_CANDIDATES]


def _search(store, q: dict, now: datetime) -> None:
    since = (date.today() - timedelta(days=2)).isoformat()
    try:
        res = tool("search", {"query": f"{q['text']} after:{since}", "count": 6})
    except Exception as e:
        log.warning("question search %d failed: %r", q["id"], e)
        return
    store.update_question(q["id"], searched=to_iso(now))
    results = [r for r in res.get("results", []) if r.get("url") and r.get("rerank_score", 0) >= 1.0]
    known = store.known_urls(r["url"] for r in results)
    store.add([Signal(url=r["url"].strip(), title=clean_text(r.get("title", ""), 300),
                      summary=clean_text(r.get("snippet", "")), source="deepseek_search",
                      channel=r.get("site_name") or "替你留意", kind="新闻", extra={"question": q["id"]})
               for r in results if r["url"] not in known], now=now)


def _judge(llm, q: dict, signals: list[Signal]) -> tuple[str, Signal] | None:
    reports = "\n".join(f"[{i}] {s.title}\n  {s.summary[:300]}" for i, s in enumerate(signals, 1))
    try:
        raw = llm.json(ANSWER_SYSTEM, f"他的问题：{q['text']}\n\n新报道：\n{reports}", max_tokens=300)
    except Exception as e:
        log.warning("answer judge %d failed: %r", q["id"], e)
        return None
    answer = str(raw.get("answer") or "").strip()[:120]
    if raw.get("answered") is not True or not answer:
        return None
    try:
        index = int(raw.get("source"))
    except (TypeError, ValueError):
        index = 1
    return answer, signals[min(max(index, 1), len(signals)) - 1]
