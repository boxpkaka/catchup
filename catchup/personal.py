"""个人记忆：从对话、反馈、漏报里沉淀“你是谁、在做什么、想看什么”，再反过来影响排序、简报和对话。

记忆分五类（产品论文第 04 章）：正在做的事权重最高且会过期；长期关注；不想看；偏好的来源；希望怎么写。
每条记忆都记录出处，可查看、编辑、删除；个性化可以整体关闭（个人信息保护法第二十四条要求提供不针对个人特征的选项）。
记忆每次变化都把 memory_rev 加一，简报据此判断要不要按新记忆重写（重要性、建议追问）。
"""
from __future__ import annotations

import logging
import threading
from datetime import datetime, timedelta, timezone

from .config import Config
from .pipeline.events import group_events
from .pipeline.ranking import Interest, Ranker
from .sources import build_sources, source_of
from .util import parse_time

log = logging.getLogger(__name__)

KINDS = {
    "task": "正在做",
    "interest": "长期关注",
    "avoid": "不想看",
    "source": "偏好的来源",
    "avoid_source": "不想看的来源",
    "style": "希望怎么写",
}
TASK_DAYS = 21          # “正在做”的事默认三周后过期，过期后不再参与排序
WEIGHTS = {"task": 3.0, "interest": 2.0}
# “不需要”的原因；“其他”不当场记，和没选原因的一样留给 learn_from_feedback 攒几条一起找共同点
REASONS = {"topic": "不想看这类", "source": "不想看这个来源", "known": "已经知道了", "other": "其他原因"}
FEEDBACK_ORIGIN = "你的反馈"          # 每小时从行为规律里归纳的
# 标“不需要 / 想看”时明说了原因，当场记下（记 event_id），撤回时一起删
REASON_ORIGINS = {"not_needed": "你标记的不需要", "useful": "你标记的想看"}
LEARN_DAYS = 30

EXTRACT_SYSTEM = """你在维护一位 AI 从业者的个人记忆，用来决定以后给他推什么、怎么写。
根据刚发生的对话，判断用户有没有透露关于他自己的、以后仍然有用的信息。只记用户明确说出或强烈暗示的事实，
不要记对话内容本身，不要记新闻事实，不要猜测。大多数对话不需要记任何东西。
记忆类型：task=最近在做的事或项目；interest=长期关注的领域、技术、公司；avoid=明确不想看的内容；
source=更信任或更想看的来源；avoid_source=明确不想看的来源（网站、频道、社区）；style=希望内容怎么写（详略、角度、语言）。
已有记忆会一并给出：同一件事已经记过就用 update 改写，不要重复添加；用户否认了某条就放进 remove。
另外看用户这一轮想知道的事，回答里是不是明确说了目前还没有答案（还没公布、来源里没有提到、要等官方消息等）：
是的话写进 pending，写成一句带上事件名的完整问题（如“Dessa 什么时候开放权重？”），并给 3～6 个用于匹配后续报道的关键词；
回答已经解决了、或者问题太泛，pending 就是 null；已在留意的问题不要重复。
输出 JSON：{"add": [{"kind": "...", "text": "第二人称、不超过 30 字，如“你在评估用 FunASR 替换 Whisper”",
"keywords": ["用于匹配新闻的关键词，中英文都给，3～8 个"]}], "update": [{"id": 编号, "text": "...", "keywords": [...]}],
"remove": [编号], "pending": {"text": "...", "keywords": [...]} 或 null}。
没有就输出 {"add": [], "update": [], "remove": [], "pending": null}。"""

FEEDBACK_SYSTEM = """下面是一位 AI 从业者最近 30 天对一些事件的行为，以及他已有的记忆。据此维护“由反馈归纳的记忆”。
信号强弱：你分享的、追踪、在事件下提问、会错过、有用是较强的正面信号；看原文是中等正面信号；不会错过是较弱的负面信号。
带原因的“不需要：不想看这类 / 不想看这个来源”已经单独记下了，只作为规律的一部分；
“不需要：其他原因”和没写原因的“不需要”是他没说为什么，正要靠你把这几件放在一起找共同点：几件明显是同一类时归纳成 avoid，看不出就先不动；
“不需要：已经知道了”只说明内容重复，不代表不感兴趣，不要当作负面信号；“误点了不需要，已撤回”不是证据，忽略。
只有呈现出明确规律时（例如几件同类事件都追踪或提问过、都标了不需要）才归纳，单条不下结论。
不想看某类内容时 kind 用 avoid，keywords 只写内容关键词，不要写来源名；
不想看某个来源时 kind 用 avoid_source，text 用来源的名字，keywords 只写该来源〔〕里的标识，原样照抄。
已有记忆里标注“由反馈归纳”的是以前从这些行为归纳出来的：证据不再支持的（比如支撑它的“不需要”被撤回了）放进 remove，
需要修正的放进 update；其他记忆只作参考，不要改动，也不要重复添加。
每条新增记忆都要在 evidence 里写出支撑它的所有行为编号。
输出 JSON：{"add": [{"kind": "interest|avoid|avoid_source|source", "text": "第二人称、不超过 30 字",
"keywords": ["3～8 个中英文关键词"], "evidence": [编号]}], "update": [{"id": 编号, "text": "...", "keywords": [...]}],
"remove": [编号]}；没有要改的就全部输出空数组。"""

MISS_SYSTEM = """用户反映：有一条他关心的消息 Catchup 没有推给他。请把它转成一条记忆，帮助以后不再漏掉同类内容。
输出 JSON：{"add": [{"kind": "interest|task|source", "text": "第二人称、不超过 30 字", "keywords": ["3～8 个中英文关键词"]}],
"update": [], "remove": []}。"""

WANT_SYSTEM = """用户在“替你略过的”列表里对下面这条内容点了“想看这类”，说明这类内容他其实关心。
判断“这类”具体指什么，写成一条“长期关注”的记忆。要具体，不要泛泛地写“AI 新闻”。
keywords 写这一类内容常见的词，英文用不会有歧义的短语，不要写这条内容里的专有名词，也不写来源名。
已有记忆里有同样意思的就输出空数组。
输出 JSON：{"add": [{"kind": "interest", "text": "第二人称、不超过 30 字", "keywords": ["3～8 个中英文关键词"]}]}"""

AVOID_OPTIONS_SYSTEM = """用户把下面这条内容标为“不需要”。列出他最可能不想看的 3 类内容，从具体到宽泛排（例如“智能眼镜评测”“消费电子硬件”“硬件产品”），
由他自己选一个，所以每一类都要说得清楚、互不重复。
要具体，不要误伤他关注的方向：例如他关注 TTS 语音合成，而这条讲的是股票代码 TTS，就写“股票行情和估值”，不要写“TTS”。
text 是不超过 10 个字的名词短语；keywords 写这一类内容常见的通用词（如 股价、估值、市值、stock price、market cap），
英文用不会有歧义的短语，不要单独写 stock、shares 这种还有别的意思的词；不要写这条内容里的专有名词（公司名、人名、产品名、股票代码），
不写来源名，也不写他关注方向本身的词。他已经不想看的类别不要再列。
输出 JSON：{"options": [{"text": "...", "keywords": ["3～8 个中英文关键词"]}]}"""

KEYWORDS_SYSTEM = """为下面这条个人记忆生成 3～8 个用于匹配新闻标题的关键词，中英文都给，专有名词保持原样。
输出 JSON：{"keywords": [...]}。"""


def personalized(store) -> bool:
    return store.setting("personalize", "1") == "1"


def memory_rev(store) -> int:
    return int(store.setting("memory_rev", "0"))


def bump(store) -> None:
    store.set_setting("memory_rev", str(memory_rev(store) + 1))


def active(store, now: datetime) -> list[dict]:
    """参与个性化的记忆：未删除、未过期。个性化关闭时为空。"""
    if not personalized(store):
        return []
    return [m for m in store.memories()
            if not m["expires"] or (parse_time(m["expires"]) or now) > now]


def build_ranker(config: Config, store, now: datetime | None = None) -> Ranker:
    """配置里的兴趣是起点，记忆在其上叠加；个性化关闭时只用通用规则（来源、热度、时效）。"""
    now = now or datetime.now(timezone.utc)
    if not personalized(store):
        return Ranker([], **_ranking_options(config))
    base = Ranker.from_config(config.interests, config.ranking)
    interests, avoid, sources, avoid_sources = list(base.interests), [], [], []
    for m in active(store, now):
        if not m["keywords"]:
            continue
        item = Interest(name=m["text"][:12], weight=WEIGHTS.get(m["kind"], 1.0), keywords=m["keywords"], said=m["text"])
        if m["kind"] in WEIGHTS:
            interests.append(item)
        elif m["kind"] == "avoid":
            avoid.append(item)
        elif m["kind"] == "source":
            sources.append(item)
        elif m["kind"] == "avoid_source":
            avoid_sources.append(item)
    return Ranker(interests, avoid=avoid, sources=sources, avoid_sources=avoid_sources, **_ranking_options(config))


def _ranking_options(config: Config) -> dict:
    keys = Ranker([]).opt.keys()
    return {k: v for k, v in config.ranking.items() if k in keys}


def focus(config: Config, store, now: datetime | None = None) -> str:
    """写进提示词的“读者是谁”：配置里的长期兴趣 + 各类记忆。"""
    now = now or datetime.now(timezone.utc)
    lines = []
    if personalized(store):
        lines.append("长期关注：" + "；".join(f"{i['name']}（{'、'.join(i['keywords'][:6])}）" for i in config.interests))
    groups: dict[str, list[str]] = {}
    for m in active(store, now):
        groups.setdefault(m["kind"], []).append(m["text"])
    for kind, label in KINDS.items():
        if kind in groups:
            lines.append(f"{label}：" + "；".join(groups[kind][:8]))
    return "\n".join(lines) if lines else "（用户关闭了个性化，按通用 AI 从业者的视角写）"


def apply_ops(store, ops: dict, origin: str, now: datetime, thread_id: int | None = None,
              event_id: int | None = None) -> dict:
    """执行模型给出的增删改，返回实际生效的条目，供 App 当场展示“已记住”。"""
    existing = {m["id"]: m for m in store.memories()}
    added, updated, removed = [], [], []
    for item in ops.get("add") or []:
        kind, text = item.get("kind"), str(item.get("text") or "").strip()[:80]
        if kind not in KINDS or not text or any(m["text"] == text for m in existing.values()):
            continue
        expires = now + timedelta(days=TASK_DAYS) if kind == "task" else None
        keywords = [str(k)[:40] for k in item.get("keywords") or []][:10]
        mid = store.add_memory(kind, text, keywords, origin, now, expires, thread_id, event_id)
        added.append({"id": mid, "kind": kind, "text": text})
    for item in ops.get("update") or []:
        mid = _int(item.get("id"))
        if mid not in existing:
            continue
        fields = {}
        if item.get("text"):
            fields["text"] = str(item["text"]).strip()[:80]
        if item.get("keywords"):
            fields["keywords"] = [str(k)[:40] for k in item["keywords"]][:10]
        if existing[mid]["kind"] == "task":
            fields["expires"] = now + timedelta(days=TASK_DAYS)  # 又提到了，说明还在做
        if fields and store.update_memory(mid, now, **fields):
            updated.append({"id": mid, "kind": existing[mid]["kind"], "text": fields.get("text", existing[mid]["text"])})
    for raw in ops.get("remove") or []:
        mid = _int(raw)
        if mid in existing and store.update_memory(mid, now, deleted=True):
            removed.append({"id": mid, "kind": existing[mid]["kind"], "text": existing[mid]["text"]})
    if added or updated or removed:
        bump(store)
    return {"added": added, "updated": updated, "removed": removed}


def extract_from_chat(store, llm, user: str, reply: str, event_headline: str | None, now: datetime,
                      thread_id: int, event_id: int | None) -> tuple[dict, dict | None]:
    """返回记忆的增删改，以及这一轮里暂时没有答案、值得替他留意的问题（{text, keywords} 或 None）。"""
    memories = "\n".join(f"{m['id']}: [{m['kind']}] {m['text']}" for m in store.memories()) or "（暂无）"
    watching = "\n".join(q["text"] for q in store.questions("open") if q["thread_id"] == thread_id) or "（暂无）"
    context = f"讨论的事件：{event_headline}\n" if event_headline else ""
    try:
        ops = llm.json(EXTRACT_SYSTEM, f"已有记忆：\n{memories}\n\n已在留意的问题：\n{watching}\n\n{context}"
                                       f"用户：{user}\n助手：{reply[:1500]}", max_tokens=700)
    except Exception as e:
        log.warning("memory extraction failed: %r", e)
        return {"added": [], "updated": [], "removed": []}, None
    pending = ops.get("pending")
    if not (isinstance(pending, dict) and str(pending.get("text") or "").strip()):
        pending = None
    else:
        pending = {"text": str(pending["text"]).strip()[:120],
                   "keywords": [str(k)[:40] for k in pending.get("keywords") or []][:8]}
    return apply_ops(store, ops, "对话", now, thread_id, event_id), pending


def learn_from_feedback(config: Config, store, llm, now: datetime) -> dict:
    """有新行为时，结合最近 30 天的行为归纳记忆；只改动由反馈归纳的记忆，对话里说过的不动。"""
    empty = {"added": [], "updated": [], "removed": []}
    recent = store.actions(now - timedelta(days=LEARN_DAYS))
    if not recent or recent[-1][0] <= int(store.setting("actions_learned", "0")):
        return empty
    store.set_setting("actions_learned", str(recent[-1][0]))
    lines = evidence(config, store, recent)[-40:]
    learned = {m["id"] for m in store.memories() if m["origin"] == FEEDBACK_ORIGIN}
    strengths = [strength for _, strength in lines]
    if strengths.count("signal") < 2 and not learned:
        return empty
    memories = "\n".join(f"{m['id']}: [{m['kind']}] {m['text']}" + ("（由反馈归纳）" if m["id"] in learned else "")
                         for m in store.memories()) or "（暂无）"
    shown = "\n".join(f"{n}. {line}" for n, (line, _) in enumerate(lines, 1)) or "（暂无）"
    try:
        ops = llm.json(FEEDBACK_SYSTEM, f"已有记忆：\n{memories}\n\n行为：\n{shown}", max_tokens=600)
    except Exception as e:
        log.warning("feedback learning failed: %r", e)
        return empty
    # 单条不下结论：新增记忆至少要两条行为支撑；光靠提示词模型做不到，这里兜底
    ops["add"] = [a for a in ops.get("add") or [] if isinstance(a, dict) and _supported(a, strengths)]
    ops["update"] = [u for u in ops.get("update") or [] if isinstance(u, dict) and _int(u.get("id")) in learned]
    ops["remove"] = [r for r in ops.get("remove") or [] if _int(r) in learned]
    return apply_ops(store, ops, FEEDBACK_ORIGIN, now)


def _supported(item: dict, strengths: list[str]) -> bool:
    cited = {n for n in (_int(i) for i in item.get("evidence") or []) if n and 1 <= n <= len(strengths)}
    return sum(strengths[n - 1] == "signal" for n in cited) >= 2


def evidence(config: Config, store, actions) -> list[tuple[str, str]]:
    """把行为日志按事件汇总成“[追踪，提问 2 次] 标题（来源：r/LocalLLaMA〔LocalLLaMA〕）”，并标出强度：
    neutral 是不算证据的（已经知道了、误点已撤回），其余是 signal。"""
    state: dict[int, dict] = {}
    for _, event_id, kind, value, _ in actions:
        s = state.setdefault(event_id, {"feedback": None, "reason": None, "restored": False, "follow": False,
                                        "asks": 0, "opens": 0, "rating": None, "shared": False})
        if kind in ("useful", "not_needed"):
            s["feedback"], s["reason"] = kind, value
        elif kind in ("clear", "restore"):
            s["feedback"], s["reason"] = None, None
            s["restored"] = s["restored"] or kind == "restore"
        elif kind in ("follow", "unfollow"):
            s["follow"] = kind == "follow"
        elif kind == "ask":
            s["asks"] += 1
        elif kind == "open_source":
            s["opens"] += 1
        elif kind == "rating":
            s["rating"] = value == "1"
        elif kind == "share":
            s["shared"] = True
    briefs = store.briefs(state.keys())
    labels = build_sources(config.sources)
    lines = []
    for event_id, s in state.items():
        tags, neutral = [], []
        if s["feedback"] == "useful":
            tags.append("有用")
        elif s["feedback"] == "not_needed" and s["reason"] == "known":
            neutral.append("不需要：已经知道了")
        elif s["feedback"] == "not_needed":
            tags.append("不需要" + (f"：{REASONS[s['reason']]}" if s["reason"] in REASONS else ""))
        elif s["restored"]:
            neutral.append("误点了不需要，已撤回")
        if s["shared"]:
            tags.append("你分享的")
        if s["follow"]:
            tags.append("追踪")
        if s["asks"]:
            tags.append(f"提问 {s['asks']} 次")
        if s["opens"]:
            tags.append(f"看原文 {s['opens']} 次")
        if s["rating"] is not None:
            tags.append("会错过" if s["rating"] else "不会错过")
        signals = store.event_signals(event_id)
        if not (tags or neutral) or not signals:
            continue
        headline = briefs[event_id]["headline"] if event_id in briefs else signals[0].title
        origins = "、".join(dict.fromkeys("{}〔{}〕".format(*source_of(x, labels)) for x in signals[:6]))
        lines.append((f"[{'，'.join(neutral + tags)}] {headline}（来源：{origins}）", "signal" if tags else "neutral"))
    return lines


def avoid_options(config: Config, store, llm, event_id: int, now: datetime) -> list[dict]:
    """点了“不需要”时列出几类可能不想看的内容 [{text, keywords}]，由用户自己选，选中的原样记下（见 from_reason）。"""
    events = group_events(store.event_signals(event_id))
    if not events or llm is None:
        return []
    event, labels = events[0], build_sources(config.sources)
    brief = store.briefs([event_id]).get(event_id) or {}
    avoided = "\n".join(m["text"] for m in store.memories() if m["kind"] == "avoid") or "（暂无）"
    titles = "\n".join(f"- {s.title}" for s in event.signals[:6])
    try:
        raw = llm.json(AVOID_OPTIONS_SYSTEM, f"他是谁：\n{focus(config, store, now)}\n\n已经不想看的：\n{avoided}\n\n"
                                             f"内容：{brief.get('headline') or event.lead.title}。{brief.get('summary', '')}\n"
                                             f"报道标题：\n{titles}", max_tokens=400)
    except Exception as e:
        log.warning("avoid options failed: %r", e)
        return []
    names = {n.lower() for s in event.signals for n in source_of(s, labels)}
    options = []
    for o in raw.get("options") or []:
        text = str(o.get("text") or "").strip()[:12] if isinstance(o, dict) else ""
        keywords = [str(k)[:40] for k in o.get("keywords") or [] if str(k).lower() not in names][:8] if text else []
        if text and keywords:
            options.append({"text": text, "keywords": keywords})
    return options[:3]


def from_reason(config: Config, store, llm, event_id: int, value: str, reason: str, now: datetime,
                picked: dict | None = None) -> dict:
    """标“不需要 / 想看”时明说了原因，当场记下：不想看这个来源直接记；不想看这类记用户从 avoid_options 里选的那一类；
    想看这类请模型判断“这类”是什么。撤回或改了反馈时由 retract() 删除。"""
    empty = {"added": [], "updated": [], "removed": []}
    events = group_events(store.event_signals(event_id))
    if not events:
        return empty
    event, labels = events[0], build_sources(config.sources)
    names = {n.lower() for s in event.signals for n in source_of(s, labels)}
    if value == "not_needed" and reason == "source":
        name, key = source_of(event.lead, labels)
        ops = {"add": [{"kind": "avoid_source", "text": f"你不想看「{name}」的内容", "keywords": [key]}]}
    elif value == "not_needed" and reason == "topic" and picked:
        ops = {"add": [{"kind": "avoid", "text": f"你不想看{picked['text']}相关的内容", "keywords": picked["keywords"]}]}
    elif value == "useful" and reason == "topic" and llm:
        brief = store.briefs([event_id]).get(event_id) or {}
        same = "\n".join(m["text"] for m in store.memories() if m["kind"] == "interest") or "（暂无）"
        titles = "\n".join(f"- {s.title}" for s in event.signals[:6])
        try:
            ops = llm.json(WANT_SYSTEM, f"他是谁：\n{focus(config, store, now)}\n\n已有的“{KINDS['interest']}”：\n{same}\n\n"
                                        f"内容：{brief.get('headline') or event.lead.title}。{brief.get('summary', '')}\n"
                                        f"报道标题：\n{titles}", max_tokens=400)
        except Exception as e:
            log.warning("reason to memory failed: %r", e)
            return empty
        ops = {"add": [a for a in ops.get("add") or [] if isinstance(a, dict) and a.get("kind") == "interest"][:1]}
    else:
        return empty
    for a in ops["add"]:  # 模型常把来源名也写进关键词，那样会把这个来源的内容全压下去（或全抬上来）
        if a["kind"] != "avoid_source":
            a["keywords"] = [k for k in a.get("keywords") or [] if str(k).lower() not in names]
    return apply_ops(store, ops, REASON_ORIGINS[value], now, event_id=event_id)


def retract(store, event_id: int, now: datetime) -> dict:
    """撤回“不需要 / 想看”时，删掉当时因它的原因记下的记忆。"""
    removed = []
    origins = set(REASON_ORIGINS.values())
    for m in store.memories():
        if m["origin"] in origins and m["event_id"] == event_id and store.update_memory(m["id"], now, deleted=True):
            removed.append({"id": m["id"], "kind": m["kind"], "text": m["text"]})
    if removed:
        bump(store)
    return {"added": [], "updated": [], "removed": removed}


def from_miss(store, llm, text: str, now: datetime) -> dict:
    store.add_miss(text, now)
    try:
        ops = llm.json(MISS_SYSTEM, f"用户说：{text}", max_tokens=400)
    except Exception as e:
        log.warning("miss to memory failed: %r", e)
        return {"added": [], "updated": [], "removed": []}
    return apply_ops(store, ops, "漏报", now)


def keywords_for(llm, text: str) -> list[str]:
    try:
        return [str(k)[:40] for k in llm.json(KEYWORDS_SYSTEM, text, max_tokens=200).get("keywords", [])][:10]
    except Exception as e:
        log.warning("keywords failed: %r", e)
        return []


_rebrief_lock = threading.Lock()


def rebrief_async(config: Config, top: int = 15) -> None:
    """记忆变了以后在后台重写前排事件的简报，让重要性和建议追问尽快反映新记忆。已有一轮在跑就跳过。"""
    if not _rebrief_lock.acquire(blocking=False):
        return

    def run():
        from .llm import from_config
        from .pipeline.update import refresh_briefs
        from .store import Store
        try:
            store = Store(config.db_path)
            llm = from_config(config.llm)
            if llm:
                refresh_briefs(config, store, llm, top=top)
            store.con.close()
        except Exception as e:
            log.warning("rebrief failed: %r", e)
        finally:
            _rebrief_lock.release()

    threading.Thread(target=run, name="rebrief", daemon=True).start()


def _int(value) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None
