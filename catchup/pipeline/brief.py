"""用大模型给事件写简报、合并同一件事的多条报道。

规则版聚类会把同一次发布的不同报道拆成几个事件，规则打分也挡不住标题里恰好带关键词的噪声；
这里让模型做两件规则做不好的事：判断“是不是同一件事”和“重不重要”。模型只看候选池（排名靠前的事件），成本可控；
排不进候选池的事件来了新报道时只看标题初筛，够格的当轮写简报。
简报先读主来源的原文（和对话共用原文缓存），概要和要点写的是原文里的具体内容，读者不用再点开链接；
另写一段 AI 解读（insight），说模型怎么看这件事，和只写事实的概要、要点分开。
"""
from __future__ import annotations

import logging
import re
from datetime import date, datetime, timedelta

from .. import chat, reader
from ..models import Event
from ..sources import Source, source_of
from .events import distinct

log = logging.getLogger(__name__)

IMPORTANCE = "importance 的含义：5 = 行业重大发布，或读者关注领域的关键进展；4 = 值得当天知道；3 = 一般动态；2 = 边缘信息；1 = 噪声、营销或与 AI 无关。"

BRIEF_SYSTEM = """你是 Catchup 的编辑，读者是一名 AI 从业者。下面是同一事件的若干条报道，可能附有主来源的原文。
先看报道本身讲的是什么：以报道的标题为准，原文用来补充细节。是新闻（有人新做了某事）就写这件事；是评论、分析、盘点、教程，就写它的主题和观点（如“分析：AI 游戏门槛骤降，大厂押注精品化”），不要把文中为引出话题提到的背景（“前不久某某发布了……”）当成这件事；是日报、周报这类汇总，写标题里的头条那件。
请输出 JSON：
{"headline": "中文标题，不超过 24 个字，说清谁做了什么",
 "summary": "1～2 句中文概要，不超过 80 个字：说清谁做了什么，以及最关键的具体内容（能力、数字、价格、时间、怎么获取），让读者不点原文也知道主要内容；不夸大、不评价",
 "points": ["概要之外的具体要点 3～5 条，每条一句话、不超过 40 个字，取自原文；没有原文、材料只有标题时输出空数组"],
 "importance": 1～5 的整数,
 "insight": "AI 解读，2～3 句、不超过 150 个字。概要和要点写发生了什么，这里写怎么看：新在哪（和之前的版本、同类产品或常见做法比），可能带来什么影响，有没有值得留意的局限或疑点（比如只有厂商自己的测试数据）。要有判断、判断要有依据；不复述概要，不写和读者个人有什么关系。importance 为 1～2 时输出空字符串",
 "questions": ["读者看完最可能追问的 3 个问题，每个不超过 18 个字，结合他的关注点"],
 "subject": "这件事主角的标准名字，只写一个：新发布的产品、模型（带版本号）、论文、开源项目，交易写被投资或被收购的公司，如 Claude Opus 4.8、FlashProve、Hy4。主角是做这件事、或这件事要讲的那个对象：用到的工具、被拿来评测或替换的模型不是主角（“Revolut 改用 GLM”的主角是 Revolut；用 Opus 审计网络的论文，主角是这篇论文，没有简称就写论文标题）。评论、分析、盘点没有主角，输出空字符串",
 "aliases": ["主角的其他写法 0～3 个：中文名、英文名、常用简称，如 Opus 4.8、混元 Hy4；不要写别的产品、公司或工具"],
 "happened": "材料里写明的、这次报道的这件事发生的日期，格式 YYYY-MM-DD，只写了月日的按今天推算年份。这件事可以是发布、上线、宣布、融资，也可以是新曝出的问题、新的评测、回应；讲的是旧产品的新情况时写新情况的日期，不要写产品当初发布的日期。材料没写就输出空字符串，不要用公司成立、项目创建这类背景日期，也不要用报道或采集的时间"}
""" + IMPORTANCE + """
headline、summary、points 只写材料里有的信息，不要编造数字和结论；也不要写“未披露”“原文没有提到”这类说明缺了什么的话。
insight 可以结合你了解的行业背景，但你的知识可能过时：拿不准的对比和“首个”“最强”这类说法不要写，数字只用材料里的。"""

SCREEN_SYSTEM = """你是 Catchup 的编辑，读者是一名 AI 从业者。下面是刚出现、还没写简报的新闻，每行一件事，格式为“编号: 标题（来源）”，几篇报道的标题用“ / ”隔开。
只凭标题判断每件事的重要性，输出 JSON：{"importance": {"编号": 1～5 的整数}}。
""" + IMPORTANCE + """
标题看不出具体是什么事的往低里给。"""
SCREEN_BATCH = 40
SCREEN_MIN = 4  # 初筛只把“值得当天知道”的提前写简报，一般动态照旧等排名上来

MERGE_SYSTEM = """下面是若干 AI 领域的新闻事件，每行一个，格式为“编号: 标题（来源）”。
找出报道的是同一件事的编号，分组输出。只合并确定是同一件事的（同一次发布、同一篇论文、同一个产品更新），
同一家公司的不同新闻不要合并。输出 JSON：{"groups": [[编号, 编号], ...]}，没有可合并的就输出 {"groups": []}。"""

# 主来源是论文时加在 BRIEF_SYSTEM 后面：写成读论文的简报
PAPER_NOTE = """
这件事是一篇论文，附的原文是论文全文的开头。headline 说清它做了什么（如“用 X 让 Y 的 Z 提升多少”），不要直译论文标题；
summary 写它要解决的问题、核心做法和最关键的结果；points 依次写方法的关键做法、主要实验结果（带数字和比较对象）、作者提到的局限或适用条件；
insight 写新意在哪、结论有多可靠（比如只在小模型或单一数据集上验证）；questions 写读这篇论文时最可能追问的问题。"""

TOPIC_SYSTEM = """给下面每件 AI 领域的事选一个主话题，只能从这几个里选：{names}。
看这件事主要讲什么，不要因为顺带提到某个词就归过去：比如世界模型顺带能生成音频，主话题不是 AI 语音；都不太沾边的选“其他”。
每行是“编号: 标题：概要”。输出 JSON：{{"topics": {{"编号": "话题"}}}}"""
TOPIC_BATCH = 40

IMPORTANCE_WEIGHT = {1: 0.3, 2: 0.6, 3: 1.0, 4: 1.4, 5: 1.8}
UNBRIEFED_WEIGHT = 0.8
FROM_TITLE = 3000  # 原文开头这么多字里有报道的标题，就从标题开始给模型
READ_WAIT = 120  # 采集后写简报时最多等原文这么多秒；没等到的先用摘要写，下一轮读到原文再重写


def final_score(event: Event, brief: dict | None) -> float:
    """规则分 × 模型给的重要性权重，App 排序和推送都用它。"""
    weight = IMPORTANCE_WEIGHT.get(brief["importance"], 1.0) if brief else UNBRIEFED_WEIGHT
    return round(event.score * weight, 2)


def hot(event: Event, sources: dict[str, Source]) -> bool:
    """正热门：多家媒体在报（同一家刷十条不算），或来源自己的热度高。"""
    return len({source_of(s, sources)[0] for s in event.signals}) >= 2 or sum(s.heat for s in event.signals) >= 1


def candidates(events: list[Event], top: int, sources: dict[str, Source]) -> list[Event]:
    """要让模型看的事件：排名前 top 的，加上排在后面但正热门的，和来自只推重要的非榜单来源（创投媒体等）的。
    热门新闻不论和读者的关注有没有关，都先判断重要性、查重，够格就推；不感兴趣的由读者划掉。
    创投媒体量不大、多数排不进前面，每件都要模型判断重要性和主话题，一般的才能留在专题里；
    榜单在榜单专题里按名次全列，不用逐条判断；论文一天几百篇，按和你的关系挑着写（pipeline/papers.py）。"""
    judged = {name for name, s in sources.items() if s.important_only and not s.board and not s.paper}
    return events[:top] + [e for e in events[top:] if hot(e, sources) or any(s.source in judged for s in e.signals)]


def screen(events: list[Event], focus: str, llm, sources: dict[str, Source]) -> set[int]:
    """排不进候选池的事件只看标题（前 3 篇报道）让模型初筛，返回重要性至少 SCREEN_MIN、这一轮就该写简报的事件 id。
    新事件往往只有一篇报道，排名靠后，要等后续报道把排名抬上来才写简报、推送，常常晚几个小时；
    只看标题，比逐条读原文写简报便宜得多。每来一篇新报道再筛一次，没选上的照旧等排名。"""
    picked = set()
    for n in range(0, len(events), SCREEN_BATCH):
        batch = events[n:n + SCREEN_BATCH]
        titles = {e.id: " / ".join(dict.fromkeys(s.title for s in e.signals[:3])) for e in batch}
        lines = "\n".join(f"{e.id}: {titles[e.id]}（{_label(e.lead, sources)}）" for e in batch)
        try:
            raw = llm.json(SCREEN_SYSTEM, f"读者是谁：\n{focus}\n\n新闻：\n{lines}", max_tokens=800).get("importance")
        except Exception as err:
            log.warning("screen failed: %r", err)
            continue
        ids = {e.id for e in batch}
        picked |= {int(i) for i, v in (raw.items() if isinstance(raw, dict) else [])
                   if str(i).isdigit() and int(i) in ids and str(v).isdigit() and int(v) >= SCREEN_MIN}
    return picked


def merge_duplicates(store, events: list[Event], llm, sources: dict[str, Source], top: int = 40) -> int:
    """在排名前 top 的事件里让模型找出同一件事，并入编号最小的事件。返回被并掉的事件数。
    模型会把同类的几个仓库、模型当成一件事（几个走红的 Claude 技能仓库并成了一件），两边是不同的论文、仓库或模型时
    代码里拦下，不靠提示词。"""
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
        kept = []
        for i in ids:
            if any(distinct(pool[i], pool[k]) for k in kept):
                log.info("not merging event %d into %s: different paper, repo or model", i, kept)
            else:
                kept.append(i)
        if len(kept) < 2:
            continue
        store.merge_events(kept[0], kept[1:])
        merged += len(kept) - 1
        log.info("merged events %s into %d", kept[1:], kept[0])
    store.commit()
    return merged


def assign_topics(store, events: list[Event], llm, names: list[str]) -> int:
    """给写好简报、还没有主话题的事件选一个主话题（names 是配置里的兴趣名，另加“其他”），App 按它给卡片上色。
    关键词匹配会因为顺带提到的词误判，这里让模型看整件事判断。返回选了几件；漏掉的下一轮再选。"""
    briefs = store.briefs(e.id for e in events)
    pending = [i for i, b in briefs.items() if not b["topic"]]
    allowed = names + ["其他"]
    done = 0
    for n in range(0, len(pending), TOPIC_BATCH):
        batch = pending[n:n + TOPIC_BATCH]
        lines = "\n".join(f"{i}: {briefs[i]['headline']}：{briefs[i]['summary']}" for i in batch)
        try:
            raw = llm.json(TOPIC_SYSTEM.format(names="、".join(allowed)), lines, max_tokens=800).get("topics") or {}
        except Exception as e:
            log.warning("topics failed: %r", e)
            continue
        picked = {i: raw[str(i)] if raw[str(i)] in allowed else "其他" for i in batch if str(i) in raw}
        store.save_topics(picked)
        done += len(picked)
    return done


def write_briefs(store, events: list[Event], focus: str, llm, sources: dict[str, Source],
                 now: datetime, top: int = 25, memory_rev: int = 0, read_wait: float = 0,
                 rewrites: int | None = None) -> int:
    """给排名前 top 的事件写简报；已有简报、没有新报道、记忆没变过且已有 AI 解读的跳过。返回新写的条数。

    focus 是“读者是谁”的描述（配置兴趣 + 个人记忆，见 personal.focus），记忆变化后 memory_rev 变大，简报按新记忆重写。
    写之前读主来源的原文，最多等 read_wait 秒，为 0 时只用已缓存的原文。之前没读到原文写的简报（points 为 None），
    这次读到了就重写。
    还没有简报、有新报道的总是写；只是要重写的（记忆变了、缺 AI 解读、补读原文）这次最多写 rewrites 条，None 不限。
    """
    pool = events[:top]
    existing = store.briefs(e.id for e in pool)
    fresh = [e for e in pool if not (old := existing.get(e.id)) or old["signal_count"] < len(e.signals)]
    redo = [e for e in pool if e not in fresh
            and (existing[e.id]["memory_rev"] < memory_rev or existing[e.id]["insight"] is None)][:rewrites]
    unread = [e for e in pool if e not in fresh and e not in redo and existing[e.id]["points"] is None]
    if rewrites is not None:
        unread = unread[:rewrites - len(redo)]
    originals = chat.read_pages(store, [e.lead.url for e in fresh + redo + unread], now, read_wait)
    written = 0
    for e in fresh + redo + [e for e in unread if originals.get(e.lead.url) is not None]:
        reports = "\n".join(f"- [{_label(s, sources)}] {s.title}\n  {s.summary[:200]}" for s in e.signals[:8])
        original = _from_title(originals.get(e.lead.url), e.lead.title)
        if original:
            reports += f"\n\n主来源原文（{_label(e.lead, sources)}）：\n{original[:chat.SOURCE_CHARS]}"
        try:
            system = BRIEF_SYSTEM + (PAPER_NOTE if reader.arxiv_id(e.lead.url) else "")
            brief = _clean(llm.json(system, f"今天是 {now:%Y-%m-%d}。\n\n读者是谁：\n{focus}\n\n报道：\n{reports}",
                                    max_tokens=1400), now, reports)
        except Exception as err:
            log.warning("brief for event %d failed: %r", e.id, err)
            continue
        if brief:
            if original is None:
                brief["points"] = None  # 还没读到原文，读到后重写
            store.save_brief(e.id, now, llm.model, len(e.signals), brief, memory_rev)
            written += 1
    return written


def _clean(raw: dict, now: datetime, material: str = "") -> dict | None:
    headline = str(raw.get("headline") or "").strip()
    if not headline:
        return None
    try:
        importance = min(max(int(raw.get("importance", 3)), 1), 5)
    except (TypeError, ValueError):
        importance = 3
    questions = [str(q).strip()[:40] for q in raw.get("questions") or [] if str(q).strip()][:3]
    points = [str(p).strip()[:80] for p in raw.get("points") or [] if str(p).strip()][:5]
    # 主角的几种写法存成 entities，第一个是标准名字；查旧（pipeline/novelty.py）用它们检索
    aliases = raw.get("aliases") if isinstance(raw.get("aliases"), list) else []
    entities = [raw.get("subject")] + aliases if str(raw.get("subject") or "").strip() else []
    return {"headline": headline[:60], "summary": str(raw.get("summary") or "").strip()[:200],
            "why": str(raw.get("why") or "").strip()[:120], "importance": importance, "questions": questions,
            "points": points, "insight": str(raw.get("insight") or "").strip()[:300],
            "entities": list(dict.fromkeys(str(x).strip()[:80] for x in entities if str(x).strip()))[:6],
            "happened": _day(raw.get("happened"), now, material)}


def _day(value, now: datetime, material: str = "") -> str | None:
    """模型给的发生日期：YYYY-MM-DD，晚于今天的不可信，当没写。now 是 UTC，北京时间可能已经是第二天，多放一天。
    年份在材料里没出现的（只写了月日），年份由这里推：取不晚于今天的最近一个这一天。模型推的年份会错，问它年份是不是
    写明的也会答错（“10 月 31 日”写成了去年、还说年份是写明的），差一年就成了旧闻。material 为空时不核对。"""
    try:
        day = date.fromisoformat(str(value or "").strip()[:10])
        if material and str(day.year) not in material:
            latest = now.date() + timedelta(days=1)
            day = day.replace(year=latest.year)
            if day > latest:
                day = day.replace(year=latest.year - 1)
    except ValueError:  # 格式不对，或 2 月 29 日换到了平年
        return None
    return day.isoformat() if day <= now.date() + timedelta(days=1) else None


def _from_title(original: str | None, title: str) -> str | None:
    """原文从报道的标题开始：网页开头常是导航和站内横幅（“Introducing Eleven v4”），模型会把它当成这件事。
    在开头一段里找不到标题就原样返回。"""
    head = re.sub(r"\s+-\s+[^-]+$", "", title).strip()[:30]  # 去掉 Google News 标题末尾的“ - 媒体名”
    i = original.find(head) if original and len(head) >= 8 else -1
    return original[i:] if 0 < i < FROM_TITLE else original


def _label(signal, sources: dict[str, Source]) -> str:
    return source_of(signal, sources)[0]
