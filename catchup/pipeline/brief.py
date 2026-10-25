"""用大模型给事件写简报、合并同一件事的多条报道。

规则版聚类会把同一次发布的不同报道拆成几个事件，规则打分也挡不住标题里恰好带关键词的噪声；
这里让模型做两件规则做不好的事：判断“是不是同一件事”和“重不重要”。模型只看候选池（排名靠前的事件），成本可控。
"""
from __future__ import annotations

import logging
from datetime import datetime

from ..models import Event
from ..sources import Source

log = logging.getLogger(__name__)

BRIEF_SYSTEM = """你是 Catchup 的编辑，读者是一名 AI 从业者。下面是同一事件的若干条报道，请输出 JSON：
{"headline": "中文标题，不超过 24 个字，说清谁做了什么",
 "summary": "1～2 句中文事实陈述，不超过 90 个字，只写报道里有的信息，不夸大、不评价",
 "why": "直接对读者说话，用“你”称呼，结合他的关注点说明这件事和你有什么关系，不超过 50 个字；和关注点无关就输出空字符串",
 "importance": 1～5 的整数}
importance 的含义：5 = 行业重大发布，或读者关注领域的关键进展；4 = 值得当天知道；3 = 一般动态；2 = 边缘信息；1 = 噪声、营销或与 AI 无关。
不要编造报道里没有的数字和结论。"""

MERGE_SYSTEM = """下面是若干 AI 领域的新闻事件，每行一个，格式为“编号: 标题（来源）”。
找出报道的是同一件事的编号，分组输出。只合并确定是同一件事的（同一次发布、同一篇论文、同一个产品更新），
同一家公司的不同新闻不要合并。输出 JSON：{"groups": [[编号, 编号], ...]}，没有可合并的就输出 {"groups": []}。"""

IMPORTANCE_WEIGHT = {1: 0.3, 2: 0.6, 3: 1.0, 4: 1.4, 5: 1.8}
UNBRIEFED_WEIGHT = 0.8


def final_score(event: Event, brief: dict | None) -> float:
    """规则分 × 模型给的重要性权重，App 排序和推送都用它。"""
    weight = IMPORTANCE_WEIGHT.get(brief["importance"], 1.0) if brief else UNBRIEFED_WEIGHT
    return round(event.score * weight, 2)


def merge_duplicates(store, events: list[Event], llm, sources: dict[str, Source], top: int = 40) -> int:
    """在排名前 top 的事件里让模型找出同一件事，并入编号最小的事件。返回被并掉的事件数。"""
    pool = {e.id: e for e in events[:top]}
    if len(pool) < 2:
        return 0
    briefs = store.briefs(pool)
    lines = []
    for e in pool.values():
        title = briefs[e.id]["headline"] if e.id in briefs else e.lead.title
        lines.append(f"{e.id}: {title}（{_label(e.lead, sources)}）")
    try:
        groups = llm.json(MERGE_SYSTEM, "\n".join(lines), max_tokens=600).get("groups", [])
    except Exception as e:
        log.warning("merge failed: %r", e)
        return 0
    merged = 0
    for group in groups:
        ids = sorted({int(i) for i in group if str(i).isdigit() and int(i) in pool})  # 模型有时把编号写成字符串
        if len(ids) < 2:
            continue
        store.merge_events(ids[0], ids[1:])
        merged += len(ids) - 1
        log.info("merged events %s into %d", ids[1:], ids[0])
    store.commit()
    return merged


def write_briefs(store, events: list[Event], interests: list[dict], llm, sources: dict[str, Source],
                 now: datetime, top: int = 25) -> int:
    """给排名前 top 的事件写简报；已有简报且没有新报道的跳过。返回新写的条数。"""
    pool = events[:top]
    existing = store.briefs(e.id for e in pool)
    focus = "；".join(f"{i['name']}（{'、'.join(i['keywords'][:8])}）" for i in interests)
    written = 0
    for e in pool:
        old = existing.get(e.id)
        if old and old["signal_count"] >= len(e.signals):
            continue
        reports = "\n".join(f"- [{_label(s, sources)}] {s.title}\n  {s.summary[:200]}" for s in e.signals[:8])
        try:
            brief = _clean(llm.json(BRIEF_SYSTEM, f"读者关注：{focus}\n\n报道：\n{reports}", max_tokens=500))
        except Exception as err:
            log.warning("brief for event %d failed: %r", e.id, err)
            continue
        if brief:
            store.save_brief(e.id, now, llm.model, len(e.signals), brief)
            written += 1
    return written


def _clean(raw: dict) -> dict | None:
    headline = str(raw.get("headline") or "").strip()
    if not headline:
        return None
    try:
        importance = min(max(int(raw.get("importance", 3)), 1), 5)
    except (TypeError, ValueError):
        importance = 3
    return {"headline": headline[:60], "summary": str(raw.get("summary") or "").strip()[:200],
            "why": str(raw.get("why") or "").strip()[:120], "importance": importance}


def _label(signal, sources: dict[str, Source]) -> str:
    src = sources.get(signal.source)
    return src.label(signal.channel) if src else signal.source
