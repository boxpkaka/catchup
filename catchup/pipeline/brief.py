"""用大模型给事件写简报、合并同一件事的多条报道。

规则版聚类会把同一次发布的不同报道拆成几个事件，规则打分也挡不住标题里恰好带关键词的噪声；
这里让模型做两件规则做不好的事：判断“是不是同一件事”和“重不重要”。模型只看候选池（排名靠前的事件），成本可控。
简报先读主来源的原文（和对话共用原文缓存），概要和要点写的是原文里的具体内容，读者不用再点开链接。
"""
from __future__ import annotations

import logging
from datetime import datetime

from .. import chat
from ..models import Event
from ..sources import Source, source_of

log = logging.getLogger(__name__)

BRIEF_SYSTEM = """你是 Catchup 的编辑，读者是一名 AI 从业者。下面是同一事件的若干条报道，可能附有主来源的原文，请输出 JSON：
{"headline": "中文标题，不超过 24 个字，说清谁做了什么",
 "summary": "1～2 句中文概要，不超过 80 个字：说清谁做了什么，以及最关键的具体内容（能力、数字、价格、时间、怎么获取），让读者不点原文也知道主要内容；不夸大、不评价",
 "points": ["概要之外的具体要点 3～5 条，每条一句话、不超过 40 个字，取自原文；没有原文、材料只有标题时输出空数组"],
 "importance": 1～5 的整数,
 "questions": ["读者看完最可能追问的 3 个问题，每个不超过 18 个字，结合他的关注点"]}
importance 的含义：5 = 行业重大发布，或读者关注领域的关键进展；4 = 值得当天知道；3 = 一般动态；2 = 边缘信息；1 = 噪声、营销或与 AI 无关。
只写材料里有的信息，不要编造数字和结论；也不要写“未披露”“原文没有提到”这类说明缺了什么的话。"""

MERGE_SYSTEM = """下面是若干 AI 领域的新闻事件，每行一个，格式为“编号: 标题（来源）”。
找出报道的是同一件事的编号，分组输出。只合并确定是同一件事的（同一次发布、同一篇论文、同一个产品更新），
同一家公司的不同新闻不要合并。输出 JSON：{"groups": [[编号, 编号], ...]}，没有可合并的就输出 {"groups": []}。"""

IMPORTANCE_WEIGHT = {1: 0.3, 2: 0.6, 3: 1.0, 4: 1.4, 5: 1.8}
UNBRIEFED_WEIGHT = 0.8
READ_WAIT = 120  # 采集后写简报时最多等原文这么多秒；没等到的先用摘要写，下一轮读到原文再重写


def final_score(event: Event, brief: dict | None) -> float:
    """规则分 × 模型给的重要性权重，App 排序和推送都用它。"""
    weight = IMPORTANCE_WEIGHT.get(brief["importance"], 1.0) if brief else UNBRIEFED_WEIGHT
    return round(event.score * weight, 2)


def hot(event: Event, sources: dict[str, Source]) -> bool:
    """正热门：多家媒体在报（同一家刷十条不算），或来源自己的热度高。"""
    return len({source_of(s, sources)[0] for s in event.signals}) >= 2 or sum(s.heat for s in event.signals) >= 1


def candidates(events: list[Event], top: int, sources: dict[str, Source]) -> list[Event]:
    """要让模型看的事件：排名前 top 的，加上排在后面但正热门的。热门新闻不论和读者的关注有没有关，
    都先判断重要性、查重，够格就推；不感兴趣的由读者划掉。"""
    return events[:top] + [e for e in events[top:] if hot(e, sources)]


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


def write_briefs(store, events: list[Event], focus: str, llm, sources: dict[str, Source],
                 now: datetime, top: int = 25, memory_rev: int = 0, read_wait: float = 0) -> int:
    """给排名前 top 的事件写简报；已有简报、没有新报道且记忆没变过的跳过。返回新写的条数。

    focus 是“读者是谁”的描述（配置兴趣 + 个人记忆，见 personal.focus），记忆变化后 memory_rev 变大，简报按新记忆重写。
    写之前读主来源的原文，最多等 read_wait 秒，为 0 时只用已缓存的原文。之前没读到原文写的简报（points 为 None），
    这次读到了就重写。
    """
    pool = events[:top]
    existing = store.briefs(e.id for e in pool)
    stale = [e for e in pool if not (old := existing.get(e.id))
             or old["signal_count"] < len(e.signals) or old["memory_rev"] < memory_rev]
    unread = [e for e in pool if e not in stale and existing[e.id]["points"] is None]
    originals = chat.read_pages(store, [e.lead.url for e in stale + unread], now, read_wait)
    written = 0
    for e in stale + [e for e in unread if originals.get(e.lead.url) is not None]:
        reports = "\n".join(f"- [{_label(s, sources)}] {s.title}\n  {s.summary[:200]}" for s in e.signals[:8])
        original = originals.get(e.lead.url)
        if original:
            reports += f"\n\n主来源原文（{_label(e.lead, sources)}）：\n{original[:chat.SOURCE_CHARS]}"
        try:
            brief = _clean(llm.json(BRIEF_SYSTEM, f"读者是谁：\n{focus}\n\n报道：\n{reports}", max_tokens=900))
        except Exception as err:
            log.warning("brief for event %d failed: %r", e.id, err)
            continue
        if brief:
            if original is None:
                brief["points"] = None  # 还没读到原文，读到后重写
            store.save_brief(e.id, now, llm.model, len(e.signals), brief, memory_rev)
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
    questions = [str(q).strip()[:40] for q in raw.get("questions") or [] if str(q).strip()][:3]
    points = [str(p).strip()[:80] for p in raw.get("points") or [] if str(p).strip()][:5]
    return {"headline": headline[:60], "summary": str(raw.get("summary") or "").strip()[:200],
            "why": str(raw.get("why") or "").strip()[:120], "importance": importance, "questions": questions,
            "points": points}


def _label(signal, sources: dict[str, Source]) -> str:
    return source_of(signal, sources)[0]
