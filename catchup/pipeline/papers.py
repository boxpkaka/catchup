"""论文专题：从 arXiv 和 HF Papers 采到的论文里挑出和你有关的，再按你的记忆主动去 arXiv 搜。

论文的见报时间是投稿时间，arXiv 隔一两天才公布、周末不公布，采到时常常已经在排序的时间窗（72 小时）之外，
信息流和推送都轮不到它。论文专题看最近 DAYS 天，有自己的一套：
- assign：时间窗之外才采到的论文另外归入事件。
- judge：每轮给新论文判断和你的关系（模型看标题和摘要开头，结合“读者是谁”），记下 0～5 分和一句理由，0 是看过、无关。
  每篇只判一次，记忆变了不回头重判，之后的新论文按新记忆判。
- to_brief：值得读（RELEVANT 以上）的写简报，读全文开头，每轮最多 BRIEFS 篇。论文来源只推重要的（Source.paper），
  一般的留在专题里。
- chase：每 CHASE_EVERY 按最强的几条“正在做 / 长期关注”记忆的英文关键词去 arXiv 搜最近的论文，补上配置的分类之外的。
- page / card：专题页和专题卡片的数据。
"""
from __future__ import annotations

import logging
import time
from datetime import datetime, timedelta

from .. import personal, reader
from ..config import Config
from ..models import Event
from ..sources import build_sources
from ..tone import TONE
from ..util import parse_time, to_iso
from .events import assign_events, group_events
from .translate import SUMMARY_CHARS

log = logging.getLogger(__name__)

KEY = "papers"     # 专题的 key，App 按它取颜色和徽记
DAYS = 7           # 专题列最近一周的论文
PICK = 3           # 进“为你挑的”
RELEVANT = 4       # 值得读：写简报，专题卡片上的数字
BRIEFS = 6         # 每轮最多给这么多篇值得读的论文写简报
JUDGE_BATCH = 30
JUDGE_LIMIT = 300  # 每轮最多判这么多篇，积压时分几轮
HOT = 30
LATEST = 150
CHASE_EVERY = timedelta(hours=12)
CHASE_MEMORIES = 4  # 按最强的几条记忆搜
CHASE_RESULTS = 20
CHASE_CATS = "cat:cs.CL OR cat:cs.AI OR cat:cs.LG OR cat:cs.SD OR cat:eess.AS OR cat:cs.SE OR cat:cs.IR OR cat:cs.CV"

JUDGE_SYSTEM = """下面是最近的 AI 论文，每行“编号: 标题：摘要开头”。读者的情况见“读者是谁”。
判断每篇论文和他的关系：
3 = 属于他关注的方向，值得扫一眼；
4 = 和他关注或正在做的事直接相关，值得读；
5 = 正好对上他正在做的事（同一个问题或方法），建议精读。
只输出 3 分以上的；和他无关、只是沾边（比如只是用到了大模型）的不要输出。宁缺毋滥，一批里通常只有少数几篇。
why 用一句话说为什么和他有关，不超过 30 个字，具体到问题或方法（如“用约束解码做结构化输出，和你在看的方向一致”）。
输出 JSON：{"papers": {"编号": {"relevance": 3～5, "why": "..."}}}

why 会直接给他看：""" + TONE


def is_paper(event: Event) -> bool:
    """主来源是 arXiv 或 HF Papers 的事件；新闻里顺带链到论文的不算。"""
    return reader.arxiv_id(event.lead.url) is not None


def _signals(config: Config, store, now: datetime) -> list:
    names = {n for n, s in build_sources(config.sources).items() if s.paper}
    return [s for s in store.recent(now - timedelta(days=DAYS), now + timedelta(hours=1)) if s.source in names]


def assign(config: Config, store, now: datetime) -> int:
    """最近 DAYS 天、还没归入事件的论文归入事件（同一篇的 arXiv 和 HF Papers 合成一件，不同的论文不会合并），返回新建几件。"""
    signals = _signals(config, store, now)
    return assign_events(store, signals, now) if any(s.event_id is None for s in signals) else 0


def recent(config: Config, store, now: datetime) -> list[Event]:
    """最近 DAYS 天的论文，每篇一件事，只含论文来源的信号。"""
    return [e for e in group_events(_signals(config, store, now)) if is_paper(e)]


def judge(config: Config, store, llm, now: datetime) -> int:
    """给最近还没判过的论文判断和你的关系，返回判了几篇；调用失败的这一批下一轮再判。"""
    papers = recent(config, store, now)
    marks = store.paper_marks(e.id for e in papers)
    pending = [e for e in papers if e.id not in marks][:JUDGE_LIMIT]
    if not pending:
        return 0
    focus = personal.focus(config, store, now)
    done = 0
    for n in range(0, len(pending), JUDGE_BATCH):
        batch = pending[n:n + JUDGE_BATCH]
        lines = "\n".join(f"{e.id}: {e.lead.title}：{e.lead.summary[:300]}" for e in batch)
        try:
            raw = llm.json(JUDGE_SYSTEM, f"读者是谁：\n{focus}\n\n论文：\n{lines}", max_tokens=1500).get("papers")
        except Exception as err:
            log.warning("paper judge failed: %r", err)
            continue
        raw = raw if isinstance(raw, dict) else {}
        out = {}
        for e in batch:
            x = raw.get(str(e.id))
            try:
                relevance = min(max(int(x["relevance"]), 0), 5) if isinstance(x, dict) else 0
            except (KeyError, TypeError, ValueError):
                relevance = 0
            why = str(x.get("why") or "").strip()[:60] if relevance >= PICK else ""
            out[e.id] = (relevance if relevance >= PICK else 0, why)
        store.save_paper_marks(out, now)
        done += len(out)
    return done


def to_brief(config: Config, store, now: datetime) -> list[Event]:
    """这一轮要写简报的论文：值得读、还没有简报、没标不需要的，最相关、最新的在前，最多 BRIEFS 篇。"""
    papers = recent(config, store, now)
    marks, briefs, feedback = store.paper_marks(e.id for e in papers), store.briefs(e.id for e in papers), store.feedback()
    picked = [e for e in papers if marks.get(e.id, {}).get("relevance", 0) >= RELEVANT and e.id not in briefs
              and feedback.get(e.id) != "not_needed"]
    picked.sort(key=lambda e: (-marks[e.id]["relevance"], -e.time.timestamp()))
    return picked[:BRIEFS]


def texts(config: Config, store, now: datetime) -> list[str]:
    """专题页会显示的英文原文（没写简报的论文的标题和摘要开头），和你有关的在前，交给 translate.run 翻译。"""
    papers = recent(config, store, now)
    marks, briefs = store.paper_marks(e.id for e in papers), store.briefs(e.id for e in papers)
    papers.sort(key=lambda e: -(marks.get(e.id) or {}).get("relevance", 0))
    return [t for e in papers if e.id not in briefs for t in (e.lead.title, e.lead.summary[:SUMMARY_CHARS])]


def chase(config: Config, store, now: datetime) -> int:
    """按记忆去 arXiv 搜最近的论文，返回新增几篇。配置的分类每次只取最新的一百篇，Agent、编码这类方向的论文多在别的分类，
    这里按最强的几条“正在做 / 长期关注”的英文关键词去全站搜，只留最近 DAYS 天的，下一轮就归入事件、判断关系。
    个性化关闭、没有配置 arXiv 来源时不搜。"""
    last = parse_time(store.setting("papers_chased") or "")
    if last and now - last < CHASE_EVERY:
        return 0
    arxiv = next((s for s in build_sources(config.sources).values() if s.type == "arxiv"), None)
    if arxiv is None:
        return 0
    store.set_setting("papers_chased", to_iso(now))
    memories = sorted((m for m in personal.active(store, now) if m["kind"] in ("task", "interest")),
                      key=lambda m: -personal.strength(m, now))
    queries = {}
    for m in memories:
        terms = [k.replace('"', "").strip() for k in m["keywords"] if k.isascii() and len(k.strip()) >= 3][:4]
        if terms:
            queries["(" + " OR ".join(f'all:"{k}"' for k in terms) + f") AND ({CHASE_CATS})"] = m["text"]
        if len(queries) >= CHASE_MEMORIES:
            break
    if not queries:
        return 0
    searcher = type(arxiv)(arxiv.name, {**arxiv.options, "queries": list(queries), "max_results": CHASE_RESULTS})
    time.sleep(3)  # 刚采过 arXiv 的话，接口要求请求间隔 3 秒
    try:
        signals = searcher.fetch()
    except Exception as e:
        log.warning("paper chase failed: %r", e)
        return 0
    fresh = [s for s in signals if s.published and s.published >= now - timedelta(days=DAYS)]
    for s in fresh:
        s.extra = {**s.extra, "chase": queries.get(s.channel, "")}
        s.channel = "按记忆搜索"
    new = store.add(fresh, now=now)
    log.info("paper chase: %d queries, %d papers, %d new", len(queries), len(fresh), new)
    return new


def _items(config: Config, store, now: datetime) -> list[dict]:
    """最近 DAYS 天的论文，每篇一条：中文标题（有简报用简报的）、概要、和你的关系、作者、HF 点赞数；标了不需要的不列。"""
    events = recent(config, store, now)
    ids = [e.id for e in events]
    briefs, marks, feedback = store.briefs(ids), store.paper_marks(ids), store.feedback()
    zh = store.translations([t for e in events if e.id not in briefs
                             for t in (e.lead.title, e.lead.summary[:SUMMARY_CHARS])])
    out = []
    for e in events:
        if feedback.get(e.id) == "not_needed":
            continue
        b, m, lead = briefs.get(e.id), marks.get(e.id) or {}, e.lead
        authors = [a for a in next((s.author for s in e.signals if s.author), "").split(", ") if a]
        chased = next((s.extra["chase"] for s in e.signals if s.extra.get("chase")), "")
        out.append({"event_id": e.id, "url": f"https://arxiv.org/abs/{reader.arxiv_id(lead.url)}",
                    "title": b["headline"] if b else zh.get(lead.title, lead.title), "original": lead.title,
                    "summary": b["summary"] if b else zh.get(s := lead.summary[:SUMMARY_CHARS], s),
                    "relevance": m.get("relevance"), "why": m.get("why") or "",
                    "authors": authors[0] + (" 等" if len(authors) > 1 else "") if authors else "",
                    "upvotes": max((s.extra.get("upvotes") or 0 for s in e.signals), default=0),
                    "importance": b["importance"] if b else None, "ai": b is not None,
                    "chased": chased, "time": to_iso(e.time)})
    return out


def _channels(items: list[dict]) -> list[dict]:
    """三个列表：为你挑的（最相关的在前，同样相关的新的在前）、社区热门（HF Papers 点赞多的）、最新。"""
    latest = sorted(items, key=lambda x: x["time"], reverse=True)
    picked = sorted((x for x in latest if (x["relevance"] or 0) >= PICK), key=lambda x: -x["relevance"])
    hot = sorted((x for x in items if x["upvotes"]), key=lambda x: -x["upvotes"])[:HOT]
    return [{"key": "for_you", "name": "为你挑的", "items": picked},
            {"key": "hot", "name": "社区热门", "items": hot},
            {"key": "latest", "name": "最新", "items": latest[:LATEST]}]


def _updated(config: Config, store) -> str | None:
    runs = store.last_runs()
    names = [n for n, s in build_sources(config.sources).items() if s.paper]
    return max((runs[n]["started"] for n in names if n in runs), default=None)


def card(config: Config, store, now: datetime) -> dict | None:
    """专题页的论文卡片：值得读的有几篇、最值得读的两篇、一周一共几篇。没有配置论文来源时为空。"""
    if not any(s.paper for s in build_sources(config.sources).values()):
        return None
    items = _items(config, store, now)
    picked = _channels(items)[0]["items"]
    return {"key": KEY, "kind": "papers", "title": "论文", "count": sum(x["relevance"] >= RELEVANT for x in picked),
            "total": len(items), "days": DAYS, "updated": _updated(config, store),
            "latest": [{"id": x["event_id"], "headline": x["title"], "why": x["why"], "time": x["time"]} for x in picked[:2]]}


def page(config: Config, store, now: datetime) -> dict:
    items = _items(config, store, now)
    return {"key": KEY, "kind": "papers", "title": "论文", "updated": _updated(config, store), "days": DAYS,
            "channels": _channels(items)}
