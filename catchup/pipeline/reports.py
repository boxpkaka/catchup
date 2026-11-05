"""日报、周报、月报：按本地时间的自然日、周（周一到周日）、月，时间段结束后各写一份。

每份分两层：
- 清单（highlights / sections，周报月报另有 trends / ongoing）：日报写这一天见报的事件（简报重要性够推送门槛、
  没被标记不需要）；周报和月报由这段时间的日报汇总，再补上重要事件，不用把几百件事都塞给模型。用 deepseek-flash。
- 观察（lede / insights / voices / for_you / watch，见 _observe）：挑出几件大事，找来社区、专家、网络上的看法
  （voices.gather），加上读者是谁、他这段时间问过什么，请推理模型写判断：把几件事连起来看出变化、对照不同看法、
  说清楚对读者意味着什么。引语只能引材料里编了号的看法，是谁、在哪、链接都由代码填，模型只写意译。失败时报告只有清单。
每天本地 READY_HOUR 点之后写上一个结束了的时间段，开始采集之前的时间段不写，周报月报缺的日报先补上。
周报和月报写好后推送一次（见 deliver），补写的旧报告不推。
"""
from __future__ import annotations

import json
import logging
import re
from collections import Counter
from datetime import date, datetime, timedelta, timezone

from .. import personal
from ..config import Config
from ..tone import TONE
from ..util import to_iso
from ..sources import build_sources
from . import push, voices

log = logging.getLogger(__name__)

KINDS = {"daily": "日报", "weekly": "周报", "monthly": "月报"}
READY_HOUR = 6
WEEKDAYS = "一二三四五六日"
DAY_EVENTS = 60   # 日报最多给模型这么多件事
KEY_EVENTS = 60   # 周报、月报在日报之外再给的重要事件
MAX_ITEMS = {"daily": 25, "weekly": 30, "monthly": 40}
DEEP = {"daily": 6, "weekly": 8, "monthly": 10}       # 找评论的大事
EXPERTS = {"daily": 6, "weekly": 10, "monthly": 12}   # 读几篇专家文章
REPORT_MODEL = "deepseek-v4-pro"                      # 写观察用的模型，config.llm 的 report_model 可以改
SPAN = {"daily": "这一天", "weekly": "这一周", "monthly": "这个月"}

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
    content.update(_observe(config, store, llm, kind, start, since, until, now, material))
    fresh = now - until < timedelta(days=1)  # 刚结束的周报月报要推送；补写的旧报告和日报不推
    store.save_report(kind, key(kind, start), content, now, notified=kind == "daily" or not fresh)
    return written + [f"{kind}:{key(kind, start)}"]


def _events(config: Config, store, since: datetime, until: datetime) -> list[tuple[int, dict, int]]:
    """这段时间见报、够推送门槛、没被标记不需要的事件：(id, 简报, 报道数)，按重要性和报道数排。"""
    counts = Counter(s.event_id for s in store.recent(since, until, include_stale=False) if s.event_id)
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
    lines = [f"{label('daily', start_of('daily', report['period']))}的日报"]
    if c.get("lede"):
        lines += ["观察：" + c["lede"]] + [f"- {x['title']}：{x['body']}" for x in c.get("insights", [])]
    lines.append("要点：")
    lines += [f"- {h['text']} " + "".join(f"[E{i}]" for i in h["events"]) for h in c["highlights"]]
    for s in c["sections"]:
        lines.append(f"{s['name']}：")
        lines += [f"- [E{item['event']}] {item['text']}" for item in s["items"]]
    return "\n".join(lines)


def _system(config: Config, store, kind: str, start: date, now: datetime) -> str:
    topics = "、".join(i["name"] for i in config.interests)
    # 周报月报的材料里重要性 4 以上的事远超条数上限，要求都列进去时模型会照抄几十条日报，月报写到截断
    pick = ("重要性 4 以上的事都要列进去，其余挑有具体信息的" if kind == "daily"
            else f"只挑最重要的，每组不超过 {-(-MAX_ITEMS[kind] // 6)} 条")
    rules = f"""highlights 3～5 条，按重要性排。sections 按领域分组，优先用读者关注的领域（{topics}），其余归入“行业”“研究”“产品”等，
最多 6 组，组内按重要性排；{pick}，一共不超过 {MAX_ITEMS[kind]} 条。每条不超过 50 个字。编号只写数字，[E83] 写成 83。"""
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


def _events_of(x: dict, allowed: set[int]) -> list[int]:
    return [i for i in dict.fromkeys(_id(v) for v in x.get("events") or []) if i in allowed]


def _notes(items, allowed: set[int], limit: int, chars: int = 160) -> list[dict]:
    out = []
    for x in items if isinstance(items, list) else []:
        if isinstance(x, dict) and str(x.get("text") or "").strip():
            out.append({"text": str(x["text"]).strip()[:chars], "events": _events_of(x, allowed)})
    return out[:limit]


def _clean(raw: dict, allowed: set[int], kind: str) -> dict | None:
    def notes(items, limit):
        return _notes(items, allowed, limit)

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


# ---------- 观察 ----------

def _observe_system(kind: str, start: date) -> str:
    span = SPAN[kind]
    return f"""你是 Catchup，一位 AI 从业者的私人助手，也是懂行的分析者。现在给他准备{KINDS[kind]}（{label(kind, start)}）开头的“观察”。
他在同一份报告里能看到事件清单，不需要你复述新闻；你的价值是替他把{span}的事放在一起想清楚，告诉他意味着什么。

{TONE}

材料：读者是谁、他这段时间问过的问题和追踪的事；{span}的事件清单（[E编号]）；几件大事的详细信息，以及社区、网络上
对它们的看法；这段时间专家博客的文章。看法和文章都以 [V编号] 开头，标了是谁、在哪说的。

怎么写：
- 把几件事连起来看出变化；指出被高估或被忽视的地方；拿不同的看法对照，说清楚分歧在哪、谁更有道理；
  再说这对像他这样做产品、做研究的人可能意味着什么。
- 每个判断都要有依据：材料里的具体事实、数字，或者某条看法（引用时写它的 V 编号）。敢下判断，可以反驳主流看法，
  但要讲出理由；拿不准的说清楚拿不准在哪。
- 社区评论有很多噪音：挑有一手经验、实测数据、不同立场的；只有情绪或玩笑的不用。专家文章里有洞见的观点要用上。
- 不要空话：“竞争加剧”“格局重塑”“意义重大”“值得关注”“军备竞赛”“进入新阶段”这类换个日期也成立的话一律不写；
  不要开场白和总结套话，不要提“本报告”“本期”“今天的观察”。
- 只用材料里的事实，不编造数字和引语；外文看法意译成中文。
- 正文里不要出现 E、V 编号：涉及的事放进 events，引用的看法放进 quotes，正文里直接说是谁说的
  （如“Simon Willison 认为”“有开发者在本地实测”）。

输出 JSON：
{{"lede": "开头：像当面跟他汇报，{span}最值得跟他说的那件事和你的看法，2～4 句，不超过 150 字；直接说事和看法，不要以“{span}最值得”这类话开头",
 "insights": [{{"title": "一句话的判断（不是新闻标题），不超过 30 字",
   "body": "150～300 字：判断、依据、不同看法或反例、对像他这样的从业者可能意味着什么", "events": [编号],
   "quotes": [{{"voice": V编号, "text": "支撑或反驳这个判断的那条看法，中文意译，不超过 80 字"}}]}}],
 "voices": [{{"voice": V编号, "text": "值得一读的看法：说了什么、为什么值得看，中文，不超过 80 字", "events": [编号]}}],
 "for_you": [{{"text": "和他正在做的事、长期关注或问过的问题真正相关的一点：说清楚和他的哪件事有关、可能派上什么用场；想给建议就说成可选的（如“如果想试，可以先……”），不超过 80 字",
   "events": [编号]}}],
 "watch": [{{"text": "还没有定论、之后会有下文的事：在等什么、等到了能看出什么、大概什么时候；只是陈述，不要写成叫他去做什么，不超过 60 字", "events": [编号]}}]}}
insights 2～4 条，每条 quotes 0～2 条；voices 3～6 条，不和 insights 里引用的重复；for_you 0～3 条，没有真正相关的就
输出空数组，不要硬凑；watch 2～3 条。编号只写数字。"""


def _observe(config: Config, store, llm, kind: str, start: date, since: datetime, until: datetime, now: datetime,
             material: list[str]) -> dict:
    """报告的观察层（见模块说明）。材料：读者、他这段时间的问题和追踪、清单（日报是事件，周报月报是日报）、
    几件大事的简报和各方看法、专家文章。取评论或调用模型失败时返回空，报告只有清单。"""
    top = [i for i, _, _ in _events(config, store, since, until)[:DEEP[kind]]]
    if not top:
        return {}
    found = voices.gather(store, build_sources(config.sources), top, since, until, EXPERTS[kind])
    briefs = store.briefs(top)
    parts = [f"读者是谁：\n{personal.focus(config, store, now)}"]
    asked = [text[:120] for text, _ in store.user_messages(since, until)]
    if asked:
        parts.append("他这段时间问过的问题：\n" + "\n".join(f"- {q}" for q in asked[-15:]))
    acted = {e for _, e, k, _, at in store.actions(since) if k in ("follow", "useful", "share") and at < to_iso(until)}
    if acted:
        heads = store.briefs(acted)
        parts.append("他这段时间追踪、想多看、分享的事：\n" + "\n".join(f"- [E{e}] {heads[e]['headline']}"
                                                                for e in acted if e in heads))
    parts.append(f"{SPAN[kind]}的清单：\n" + "\n\n".join(material))
    numbered = list(enumerate(found, 1))
    for i in top:
        if i not in briefs:
            continue
        b = briefs[i]
        lines = [f"[E{i}] {b['headline']}", f"概要：{b['summary']}"]
        if b.get("points"):
            lines.append("要点：" + "；".join(b["points"]))
        if b.get("insight"):
            lines.append(f"AI 解读：{b['insight']}")
        said = [f"[V{n}] {v['who']}（{v['where']}）：{v['text']}" for n, v in numbered if v["event"] == i]
        parts.append("\n".join(lines + (["各方看法："] + said if said else [])))
    articles = [f"[V{n}] {v['who']}《{v.get('title', '')}》：{v['text']}" for n, v in numbered
                if v["where"] == "专家博客"]
    if articles:
        parts.append("这段时间专家博客的文章：\n" + "\n\n".join(articles))
    allowed = {int(i) for i in re.findall(r"\[E(\d+)\]", "\n".join(parts))}
    try:
        raw = llm.json(_observe_system(kind, start), "\n\n".join(parts), max_tokens=16000,
                       model=config.llm.get("report_model", REPORT_MODEL), think=True)
    except Exception as e:
        log.warning("report %s %s observe failed: %r", kind, start, e)
        return {}
    return _clean_observe(raw, allowed, found)


REFS = re.compile(r"\s*[（(]\s*(?:[EV]\d+\s*[、,，/和及]?\s*)+[)）]|\s*\[(?:[EV]\d+[、,，\s]*)+\]")


def _plain(text) -> str:
    """去掉模型漏写进正文的材料编号，如“（E3788）”“[V5]”；不动 DeepSeek V4 这类名字里的。"""
    return REFS.sub("", str(text or "")).strip()


def _clean_observe(raw: dict, allowed: set[int], found: list[dict]) -> dict:
    """只留引用了材料里真有的看法的引语；是谁、在哪、链接用材料里的，不用模型写的。"""
    def quote(q) -> dict | None:
        n, text = (_id(q.get("voice")), _plain(q.get("text"))) if isinstance(q, dict) else (None, "")
        if not n or not 1 <= n <= len(found) or not text:
            return None
        v = found[n - 1]
        events = _events_of(q, allowed) or ([v["event"]] if v["event"] in allowed else [])
        return {"who": v["who"], "where": v["where"], "url": v["url"], "text": text[:160], "events": events}

    insights = []
    for x in raw.get("insights") if isinstance(raw.get("insights"), list) else []:
        title, body = (_plain(x.get("title")), _plain(x.get("body"))) if isinstance(x, dict) else ("", "")
        if title and body:
            quotes = [q for q in map(quote, x.get("quotes") if isinstance(x.get("quotes"), list) else []) if q]
            insights.append({"title": title[:60], "body": body[:900], "events": _events_of(x, allowed),
                             "quotes": quotes[:2]})
    quoted = {q["url"] + q["who"] for x in insights for q in x["quotes"]}
    said = [q for q in map(quote, raw.get("voices") if isinstance(raw.get("voices"), list) else []) if q]
    lede = _plain(raw.get("lede"))[:400]
    if not (lede or insights):
        return {}
    return {"lede": lede, "insights": insights[:4], "voices": [q for q in said if q["url"] + q["who"] not in quoted][:6],
            "for_you": _plain_notes(raw.get("for_you"), allowed), "watch": _plain_notes(raw.get("watch"), allowed)}


def _plain_notes(items, allowed: set[int]) -> list[dict]:
    return [{**x, "text": _plain(x["text"])} for x in _notes(items, allowed, 3)]


def deliver(options: dict, store, now: datetime) -> int:
    """周报、月报写好后推送一次：标题是“周报 · 10月19日–25日”，正文是开头的判断（没有时是要点），点开进报告页；
    免打扰时段先攒着。"""
    if push.quiet(options, now):
        return 0
    sent = 0
    for r in store.unnotified_reports():
        title = f"{KINDS[r['kind']]} · {label(r['kind'], start_of(r['kind'], r['period']))}"
        body = r["content"].get("lede") or "\n".join(f"· {h['text']}" for h in r["content"]["highlights"])
        if push._send(title, body, f"catchup://report/{r['kind']}/{r['period']}"):
            store.set_report_notified(r["kind"], r["period"])
            sent += 1
    return sent
