"""日报、周报、月报：按本地时间的自然日、周（周一到周日）、月，时间段结束后各写一份，只写信息。

日报写这一天见报的事件（简报重要性够推送门槛、没被标记不需要）；周报和月报由这段时间的日报汇总，再补上重要事件，
不用把几百件事都塞给模型。每天本地 READY_HOUR 点之后写上一个结束了的时间段，开始采集之前的时间段不写，
周报月报缺的日报先补上。周报和月报写好后推送一次（见 deliver），补写的旧报告不推。
"""
from __future__ import annotations

import json
import logging
import re
from collections import Counter
from datetime import date, datetime, timedelta, timezone

from .. import personal
from ..config import Config
from . import push

log = logging.getLogger(__name__)

KINDS = {"daily": "日报", "weekly": "周报", "monthly": "月报"}
READY_HOUR = 6
WEEKDAYS = "一二三四五六日"
DAY_EVENTS = 60   # 日报最多给模型这么多件事
KEY_EVENTS = 60   # 周报、月报在日报之外再给的重要事件
MAX_ITEMS = {"daily": 25, "weekly": 30, "monthly": 40}

ITEM_SCHEMA = '''{"highlights": [{"text": "一句话写一件最重要的事，带关键的具体信息（数字、价格、时间、怎么获取）", "events": [编号]}],
 "sections": [{"name": "分组名", "items": [{"event": 编号, "text": "一句话：谁做了什么、关键信息"}]}]'''
PERIOD_SCHEMA = ''',
 "trends": [{"text": "把几件事放在一起看出的变化，一两句话，点名具体的事", "events": [编号, ...]}],
 "ongoing": [{"text": "这段时间持续发展的事：起因和最新进展", "events": [编号, ...]}]'''


# ---------- 时间段 ----------

def current(kind: str, today: date) -> date:
    """今天所在时间段的第一天。"""
    if kind == "daily":
        return today
    if kind == "weekly":
        return today - timedelta(days=today.weekday())
    return today.replace(day=1)


def previous(kind: str, start: date) -> date:
    if kind == "daily":
        return start - timedelta(days=1)
    if kind == "weekly":
        return start - timedelta(days=7)
    return (start - timedelta(days=1)).replace(day=1)


def end(kind: str, start: date) -> date:
    """时间段结束后的第一天。"""
    if kind == "daily":
        return start + timedelta(days=1)
    if kind == "weekly":
        return start + timedelta(days=7)
    return (start.replace(day=28) + timedelta(days=4)).replace(day=1)


def key(kind: str, start: date) -> str:
    return start.strftime("%Y-%m") if kind == "monthly" else start.isoformat()


def start_of(kind: str, period: str) -> date:
    return date.fromisoformat(period + "-01" if kind == "monthly" else period)


def label(kind: str, start: date) -> str:
    if kind == "daily":
        return f"{start.month}月{start.day}日 周{WEEKDAYS[start.weekday()]}"
    if kind == "weekly":
        last = start + timedelta(days=6)
        return f"{start.month}月{start.day}日–{f'{last.month}月' if last.month != start.month else ''}{last.day}日"
    return f"{start.year}年{start.month}月"


def local_now(options: dict, now: datetime) -> datetime:
    return now.astimezone(timezone(timedelta(hours={**push.DEFAULTS, **options}["utc_offset"])))


def _utc(day: date, options: dict) -> datetime:
    """本地某天零点对应的 UTC 时间。"""
    offset = timedelta(hours={**push.DEFAULTS, **options}["utc_offset"])
    return datetime(day.year, day.month, day.day, tzinfo=timezone(offset)).astimezone(timezone.utc)


# ---------- 生成 ----------

def run(config: Config, store, llm, now: datetime) -> list[str]:
    """写到期还没写的报告，返回写了哪些（如 daily:2026-10-26）。"""
    local = local_now(config.push, now)
    started = store.first_fetched()
    if local.hour < READY_HOUR or started is None:
        return []
    written = []
    for kind in KINDS:
        start = previous(kind, current(kind, local.date()))
        if _utc(end(kind, start), config.push) > started and not store.report(kind, key(kind, start)):
            written += _write(config, store, llm, kind, start, now, started)
    return written


def _write(config: Config, store, llm, kind: str, start: date, now: datetime, started: datetime) -> list[str]:
    since, until = _utc(start, config.push), _utc(end(kind, start), config.push)
    written, material = [], []
    if kind == "daily":
        events = _events(config, store, since, until)[:DAY_EVENTS]
        material = [_line(i, b, n) for i, b, n in events]
    else:  # 先补齐这段时间的日报
        day = start
        while day < end(kind, start):
            if _utc(end("daily", day), config.push) > started and not store.report("daily", key("daily", day)):
                written += _write(config, store, llm, "daily", day, now, started)
            if daily := store.report("daily", key("daily", day)):
                material.append(_render(daily))
            day += timedelta(days=1)
        events = [x for x in _events(config, store, since, until) if x[1]["importance"] >= 4][:KEY_EVENTS]
        if events:
            material.append("这段时间的重要事件：\n" + "\n".join(_line(i, b, n) for i, b, n in events))
    if not material:
        return written
    allowed = {int(i) for i in re.findall(r"\[E(\d+)\]", "\n".join(material))}
    try:
        raw = llm.json(_system(config, store, kind, start, now), "\n\n".join(material), max_tokens=4000)
    except Exception as e:
        log.warning("report %s %s failed: %r", kind, start, e)
        return written
    content = _clean(raw, allowed, kind)
    if content is None:
        log.warning("report %s %s: nothing usable in %r", kind, start, raw)
        return written
    fresh = now - until < timedelta(days=1)  # 刚结束的周报月报要推送；补写的旧报告和日报不推
    store.save_report(kind, key(kind, start), content, now, notified=kind == "daily" or not fresh)
    return written + [f"{kind}:{key(kind, start)}"]


def _events(config: Config, store, since: datetime, until: datetime) -> list[tuple[int, dict, int]]:
    """这段时间见报、够推送门槛、没被标记不需要的事件：(id, 简报, 报道数)，按重要性和报道数排。"""
    counts = Counter(s.event_id for s in store.recent(since, until) if s.event_id)
    published = store.event_published(counts)
    briefs, feedback = store.briefs(counts), store.feedback()
    bar = {**push.DEFAULTS, **config.push}["min_importance"]
    out = [(i, briefs[i], n) for i, n in counts.items()
           if i in briefs and briefs[i]["importance"] >= bar and feedback.get(i) != "not_needed"
           and since <= published[i] < until]
    return sorted(out, key=lambda x: (-x[1]["importance"], -x[2]))


def _line(event_id: int, brief: dict, reports: int) -> str:
    return f"[E{event_id}] {brief['headline']}：{brief['summary']}（重要性 {brief['importance']}，{reports} 条报道）"


def _render(report: dict) -> str:
    """把日报写成周报月报的材料。"""
    c = report["content"]
    lines = [f"{label('daily', start_of('daily', report['period']))}的日报", "要点："]
    lines += [f"- {h['text']} " + "".join(f"[E{i}]" for i in h["events"]) for h in c["highlights"]]
    for s in c["sections"]:
        lines.append(f"{s['name']}：")
        lines += [f"- [E{item['event']}] {item['text']}" for item in s["items"]]
    return "\n".join(lines)


def _system(config: Config, store, kind: str, start: date, now: datetime) -> str:
    topics = "、".join(i["name"] for i in config.interests)
    rules = f"""highlights 3～5 条，按重要性排。sections 按领域分组，优先用读者关注的领域（{topics}），其余归入“行业”“研究”“产品”等，
最多 6 组，组内按重要性排；重要性 4 以上的事都要列进去，其余挑有具体信息的，一共不超过 {MAX_ITEMS[kind]} 条。每条不超过 50 个字。"""
    if kind != "daily":
        rules += "\ntrends 2～4 条；ongoing 只写跨了好几天、有新进展的事，没有就输出空数组。"
    return f"""你是 Catchup 的编辑，给一名 AI 从业者写{KINDS[kind]}（{label(kind, start)}）。材料里每件事以 [E编号] 开头。输出 JSON：
{ITEM_SCHEMA}{PERIOD_SCHEMA if kind != "daily" else ""}}}
{rules}
只写材料里有的信息，不要编造；不要写“本期”“共整理了”这类关于报告本身的话，也不要开场白和总结套话。

读者是谁：
{personal.focus(config, store, now)}"""


def _id(value) -> int | None:
    digits = re.sub(r"\D", "", str(value))  # 模型有时写成 "E123"
    return int(digits) if digits else None


def _clean(raw: dict, allowed: set[int], kind: str) -> dict | None:
    def notes(items, limit):
        out = []
        for x in items if isinstance(items, list) else []:
            if isinstance(x, dict) and str(x.get("text") or "").strip():
                ids = [i for i in dict.fromkeys(_id(v) for v in x.get("events") or []) if i in allowed]
                out.append({"text": str(x["text"]).strip()[:160], "events": ids})
        return out[:limit]

    sections = []
    for s in raw.get("sections") if isinstance(raw.get("sections"), list) else []:
        items = [{"event": _id(x.get("event")), "text": str(x.get("text") or "").strip()[:120]}
                 for x in (s.get("items") or []) if isinstance(x, dict)] if isinstance(s, dict) else []
        items = [x for x in items if x["event"] in allowed and x["text"]]
        if items:
            sections.append({"name": str(s.get("name") or "其他").strip()[:12], "items": items})
    content = {"highlights": notes(raw.get("highlights"), 5), "sections": sections[:6]}
    if kind != "daily":
        content.update(trends=notes(raw.get("trends"), 4), ongoing=notes(raw.get("ongoing"), 4))
    return content if content["highlights"] or sections else None


def deliver(options: dict, store, now: datetime) -> int:
    """周报、月报写好后推送一次：标题是“周报 · 10月19日–25日”，正文是要点，点开进报告页；免打扰时段先攒着。"""
    if push.quiet(options, now):
        return 0
    sent = 0
    for r in store.unnotified_reports():
        title = f"{KINDS[r['kind']]} · {label(r['kind'], start_of(r['kind'], r['period']))}"
        body = "\n".join(f"· {h['text']}" for h in r["content"]["highlights"])
        if push._send(title, body, f"catchup://report/{r['kind']}/{r['period']}"):
            store.set_report_notified(r["kind"], r["period"])
            sent += 1
    return sent
