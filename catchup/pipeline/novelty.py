"""查旧：一件事以前是不是已经见过。

有的来源不带发布时间（联网搜索的结果），采到的时间就被当成了发布时间；搜索还会翻出几个月前的旧文、转载和汇总。
只看时间分不出新旧，所以给每件写好简报的事查一次：
1. 用简报里主角的名字（entities：标准名字和其他写法；加这一项之前写的简报用标题里的名字）到检索库（Store.search：信号和
   简报的全文索引，不限时间）里找更早的内容。只用主角的名字：顺带提到的工具、评测用的模型会拉进来一堆只沾边的内容，
   回测里模型因此把不相干的事判成了同一件。没有明确主角的（行业观点、市场分析）不检索。
2. 找到了更早的内容，或材料写明的发生日期（简报的 happened）早于 STALE_DAYS 天前，让模型对照判断：same（同一件事、
   没有新内容）/ update（同一个产品或同一件事有了新的实质内容，比如被曝出问题、降价）/ old（检索库里对不上，但材料讲的
   就是很早以前的事）/ new。日期只是证据，不单独定案：“5 月发布的 Opus 4.8 被曝出问题”这类报道，日期可能被写成发布日。
   模型先写理由再下结论，并打开思考：回测里关着思考、先给结论时，会出现理由说不是一件事、结论却是 same。
   推理也算在 max_tokens 里，难判断的要一千七八百个 token，额度不够时返回空内容，当作这次没查成。
   两样都没有的是 new，不用问模型。
3. 怎么处理由代码决定，旧闻按你的角度算：已经推送给你过的，或一周（STALE_DAYS）以前的事。
   - same 对上的事还在时间窗里：并进那件事（同一件事的又一篇报道，简报重写）；两边是不同的论文、仓库或模型时不并
     （events.distinct）。
   - same 对上的在时间窗外：那件推送过、判过旧闻，或它和材料写明的日期有一个在 STALE_DAYS 天以前，记为旧闻；
     只早几天、也没推送过的（比如几天前只上了 HF 模型页，今天才有报道），你还不知道，当 new。
   - old 要材料写明的日期确实早于 STALE_DAYS 天前才算旧闻（模型自己的知识可能过时），否则当 new。update、new 照常。

结论记在 novelty 表，每件事只查一次（被合并时 Store.merge_events 会清掉并掉那件的结论）。旧闻不推送，也不进信息流、专题、
报告和对话（Store.recent 的 include_stale），时间窗里不再吸收新报道；没查过的不推送，模型调用失败的下一轮再查。
论文按 arXiv 编号识别、发布时间可靠，不查；你分享的不推送，也不查。
"""
from __future__ import annotations

import logging
import re
from datetime import date, datetime, timedelta

from .. import reader
from ..models import Event
from ..sources import SHARED, Source, source_of
from ..store import squash
from .events import distinct

log = logging.getLogger(__name__)

STALE_DAYS = 7    # 这么多天以前的事算旧闻（材料写明的发生日期、对上的更早内容），之内的要推送过才算
SEARCH = 30       # 检索库的简报和信号各取最相关的几条
CANDIDATES = 8    # 给模型对照的更早内容最多几条，一半留给简报
LIMIT = 20        # 每轮最多让模型判断几件（开着思考，一件几秒）；上线时积压的分几轮查，新事件先查
SNIPPET = 160
PUSHABLE = {"new", "update"}
# 标题里像名字的部分：带版本号的（“Opus 4.8”“GPT-5”“Qwen3-TTS”）和大小写混写的（“OpenAI”“vLLM”）。
# 英文标题每个词都大写开头，按大写连词会把整个标题当成一个名字
NAME = re.compile(r"[A-Z][\w.+-]*\s+\d[\w.+-]*|[A-Za-z][\w.+-]*\d[\w.+-]*|[A-Za-z]*[a-z][A-Z][\w.+-]*", re.ASCII)

SYSTEM = """你帮 Catchup 判断一件刚出现的 AI 领域的事，是不是以前就有过的旧闻。下面先是这件事，再是检索库里更早的、提到同样名字的内容（可能没有），每条“编号: 日期 来源：标题：摘要”。
先想清楚再下结论，按顺序输出 JSON：
{"reason": "先说这件事是什么，再说检索库里最像的那条是什么、两者是不是同一个具体对象，不超过 80 个字", "verdict": "same、update、old 或 new", "match": 编号或 null}
- same：和检索库里的某条是同一件事（同一次发布、同一篇论文、同一个项目、同一个产品更新、同一笔交易），这次没有新的实质内容，比如转载、汇总、回顾、晚到的报道；match 填对上的里面最早的一条
- update：和检索库里的某条说的是同一个产品或同一件事，但这次有新的实质内容，比如正式上线、开放范围或价格变了、被曝出问题或漏洞、有了新的数据或评测、官方回应；match 填相关的那条
- old：检索库里没有对得上的，但材料讲的就是很早以前发生的事，这次没有新情况
- new：其余情况
same、update 要求是同一个具体对象：同一领域、同一个话题、同一家公司的别的事、都提到同一个工具或模型，都不算；同一系列的不同版本（比如 4.7 和 4.8）也不算。“报道”里列的是这件事自己的报道，不在检索库里，不能拿来对。拿不准时输出 new。"""


def run(store, events: list[Event], llm, sources: dict[str, Source], now: datetime) -> tuple[dict, set[int]]:
    """给 events（时间窗里还在的事件）里写好简报、还没查过的查旧，新事件先查。
    返回 (统计, 并进了新报道、简报被清掉的事件 id)，调用方这一轮写简报时带上它们。"""
    stats = {"checked": 0, "stale": 0, "deduped": 0}
    briefs, pushed = store.briefs(e.id for e in events), store.pushed()
    long_ago = now - timedelta(days=STALE_DAYS)
    done = store.novelty(briefs)
    live = {e.id: e for e in events}
    pending = sorted((e for e in events if e.id in briefs and e.id not in done
                      and not any(s.source == SHARED for s in e.signals)),
                     key=lambda e: max(s.published for s in e.signals), reverse=True)
    moved: dict[int, int] = {}  # 这一轮并掉的事件 → 并进的事件
    asked = 0
    for e in pending:
        if e.id in moved or e.id in moved.values():  # 简报清掉了，重写以后下一轮再查
            continue
        b = briefs[e.id]
        if reader.arxiv_id(e.lead.url):
            _save(store, e.id, "new", now, "论文按 arXiv 编号识别，不查旧", stats)
            continue
        happened = _date(b.get("happened"))
        dated = bool(happened and happened < long_ago.date())  # 材料写明是很早以前的事
        found = candidates(store, e, b, sources)
        if not found and not dated:
            _save(store, e.id, "new", now, "检索库里没有更早提到这些名字的内容", stats)
            continue
        if asked >= LIMIT:
            continue
        asked += 1
        try:
            raw = llm.json(SYSTEM, material(e, b, found, sources, now), max_tokens=4000, think=True)
        except Exception as err:
            log.warning("novelty check for event %d failed: %r", e.id, err)
            continue
        verdict, match, reason = parse(raw, found)
        if verdict == "same":
            target = _resolve(match["event"], moved)
            if target in live and not distinct(e, live[target]):
                store.merge_events(target, [e.id])
                store.commit()
                moved[e.id] = target
                stats["deduped"] += 1
                log.info("event %d is the same as event %d, merged: %s", e.id, target, reason)
                continue
            if target in live:
                verdict, reason = "new", f"不并：两边是不同的论文、仓库或模型（{reason}）"
            elif match["stale"] or match["event"] in pushed or match["time"] < long_ago or dated:
                verdict = "stale"
            else:
                verdict, reason = "new", f"更早的那条是 {match['time']:%m-%d} 的，没推送过，这次照常（{reason}）"
        elif verdict == "old":
            verdict = "stale" if dated else "new"
        _save(store, e.id, verdict, now, reason, stats, match)
    return stats, set(moved.values())


def candidates(store, event: Event, brief: dict, sources: dict[str, Source]) -> list[dict]:
    """检索库里比这件事更早、提到同样名字的内容：编号更小的事件，和没归入事件的信号（时间窗外采到的），按相关度最多
    CANDIDATES 条。每条：{event, url, time（最早的发布时间）, label, title, summary, stale（已判为旧闻）}。"""
    brief_hits, signal_hits = store.search(_names(event, brief), SEARCH)
    half = CANDIDATES // 2
    keys: list[int | object] = brief_hits[:half] + [s if s.event_id is None else s.event_id for s in signal_hits]
    keys += brief_hits[half:]
    picked, seen = [], set()
    for k in keys:
        mark = k if isinstance(k, int) else k.url
        if mark in seen or (isinstance(k, int) and k >= event.id):
            continue
        seen.add(mark)
        picked.append(k)
    ids = [k for k in picked if isinstance(k, int)]
    briefs, verdicts = store.briefs(ids), store.novelty(ids)
    out = []
    for k in picked:
        if isinstance(k, int):
            signals = store.event_signals(k)
            if not signals:
                continue
            lead, b = Event(key=f"event:{k}", id=k, signals=signals).lead, briefs.get(k)
            out.append({"event": k, "url": lead.url, "time": min(s.published for s in signals),
                        "label": source_of(lead, sources)[0], "title": b["headline"] if b else lead.title,
                        "summary": b["summary"] if b else lead.summary,
                        "stale": verdicts.get(k, {}).get("verdict") == "stale"})
        else:
            out.append({"event": None, "url": k.url, "time": k.published, "label": source_of(k, sources)[0],
                        "title": k.title, "summary": k.summary, "stale": False})
        if len(out) >= CANDIDATES:
            break
    return out


def _names(event: Event, brief: dict) -> list[str]:
    """检索用的名字：简报里主角的几种写法（为空是没有明确主角），加这一项之前写的简报（None）用标题里的名字。"""
    return brief["entities"] if brief.get("entities") is not None else names_in(brief["headline"], event.lead.title)


def names_in(*texts: str) -> list[str]:
    """标题里像名字的部分（见 NAME），给还没有 entities 的简报检索用，最多 4 个。"""
    return list(dict.fromkeys(m.group(0).rstrip(".+-") for t in texts for m in NAME.finditer(t)))[:4]


def material(event: Event, brief: dict, found: list[dict], sources: dict[str, Source], now: datetime) -> str:
    reports = "\n".join(f"- {s.published:%Y-%m-%d} {source_of(s, sources)[0]}：{s.title}：{s.summary[:SNIPPET]}"
                        for s in event.signals[:6])
    earlier = "\n".join(f"{n}: {c['time']:%Y-%m-%d} {c['label']}：{c['title']}：{c['summary'][:SNIPPET]}"
                        for n, c in enumerate(found, 1)) or "（没有）"
    return (f"今天是 {now:%Y-%m-%d}。\n\n这件事：{brief['headline']}\n概要：{brief['summary']}\n"
            f"材料里写的发生日期：{brief.get('happened') or '没写'}\n"
            f"报道（时间是来源给的发布时间，没有发布时间的来源记的是采到的时间）：\n{reports}\n\n"
            f"检索库里更早的内容：\n{earlier}")


def parse(raw: dict, found: list[dict]) -> tuple[str, dict | None, str]:
    """模型的判断 → (same / update / old / new, 对上的那条, 理由)。same、update 的编号对不上的当 new。"""
    verdict = str(raw.get("verdict") or "").strip().lower()
    try:
        n = int(raw.get("match"))
    except (TypeError, ValueError):
        n = 0
    match = found[n - 1] if 1 <= n <= len(found) else None
    reason = str(raw.get("reason") or "").strip()[:80]
    if verdict == "old":
        return "old", None, reason
    if verdict not in ("same", "update") or match is None:
        return "new", None, reason
    return verdict, match, reason


def pushable(store, ids) -> set[int]:
    """查过、不是旧闻的事件。"""
    return {i for i, v in store.novelty(ids).items() if v["verdict"] in PUSHABLE}


def _resolve(event_id: int | None, moved: dict[int, int]) -> int | None:
    while event_id in moved:
        event_id = moved[event_id]
    return event_id


def _date(value) -> date | None:
    try:
        return date.fromisoformat(value) if value else None
    except ValueError:
        return None


def _save(store, event_id: int, verdict: str, now: datetime, reason: str, stats: dict, match: dict | None = None) -> None:
    store.save_novelty(event_id, verdict, now, reason, match and match["event"], match and match["url"])
    stats["checked"] += 1
    stats["stale"] += verdict == "stale"
    if verdict != "new":
        log.info("event %d is %s (like %s): %s", event_id, verdict, match and (match["event"] or match["url"]), reason)
