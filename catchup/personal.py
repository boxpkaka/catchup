"""个人记忆：从对话、反馈、漏报里沉淀“你是谁、在做什么、想看什么”，再反过来影响排序、简报和对话。

记忆分五类（产品论文第 04 章）：正在做的事权重最高且会过期；长期关注；不想看；偏好的来源；希望怎么写。
每条记忆都记录出处，可查看、编辑、删除；个性化可以整体关闭（个人信息保护法第二十四条要求提供不针对个人特征的选项）。
记忆每次变化都把 memory_rev 加一，简报据此判断要不要按新记忆重写（重要性、建议追问）。

每条记忆有强度：新记下的是 FULL，之后你的行为和它有关就加强（reinforce），长期关注长期没被印证会按半衰期减弱，
排序按强度给权重。刷新节奏：明说的（对话、手动、带原因的反馈）当场记；行为每轮记到强度上；从行为归纳新记忆最多
LEARN_EVERY 一次；每天整理一次（consolidate），合并意思重复的、去掉弱到不再成立的。
"""
from __future__ import annotations

import json
import logging
import re
import threading
from datetime import datetime, timedelta, timezone

from .config import Config
from .pipeline.events import group_events
from .pipeline.ranking import Interest, Ranker, keyword_in
from .sources import build_sources, source_of
from .util import parse_time, to_iso

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
LEARN_EVERY = timedelta(hours=6)      # 从行为归纳新记忆的最短间隔：攒一批行为再看规律，不必每轮都归纳一遍
CONSOLIDATE_EVERY = timedelta(days=1)

# 记忆强度：明说一次约等于 FULL，达到它按全权重算；再弱也保留 FLOOR 的权重，明说过的不会悄悄失效，删不删由你定
FULL, FLOOR, MAX_STRENGTH = 3.0, 0.3, 10.0
DECAY_DAYS = {"interest": 30}  # 只有长期关注会淡忘（半衰期）；不想看、来源、写法是偏好，不衰减；正在做的事另有过期时间
RETIRE = 0.5                   # 由反馈归纳的长期关注弱到这个程度就删掉
# 主动行为的分量：归纳新记忆只数这些（只是打开、读完、看原文不算，见 PASSIVE），至少两件事、合计 LEARN_MIN
ACTIVE = {"useful": 3.0, "follow": 3.0, "share": 3.0, "not_needed": 3.0, "ask": 1.5, "rating": 1.0}
PASSIVE = {"seen", "read", "open_source"}
LEARN_MIN = 4.0
FOR_INTEREST = {"interest", "task", "source"}   # 正面行为加强的记忆；“不需要”加强的是不想看、不想看的来源
ON_ORIGIN = {"source", "avoid_source"}          # 这两类匹配来源名，其余匹配内容

EXTRACT_SYSTEM = """你在维护一位 AI 从业者的个人记忆，用来决定以后给他推什么、怎么写。
根据刚发生的对话，判断用户有没有透露关于他自己的、以后仍然有用的信息。只记用户明确说出或强烈暗示的事实，
不要记对话内容本身，不要记新闻事实，不要猜测。大多数对话不需要记任何东西。
记忆类型：task=最近在做的事或项目；interest=长期关注的领域、技术、公司；avoid=明确不想看的内容；
source=更信任或更想看的来源；avoid_source=明确不想看的来源（网站、频道、社区）；style=希望内容怎么写（详略、角度、语言）。
已有记忆会一并给出：同一件事已经记过就用 update 改写，不要重复添加；用户否认了某条就放进 remove。
另外只看用户这一轮自己问的问题：回答里是不是明确说了这个问题目前还没有答案（还没公布、来源里没有提到、要等官方消息等）。
是的话把他的这个问题写进 pending：保留他原来的问法，只补上事件名或主语，让它单独看也能懂
（如他问“什么时候开放权重？”，写“Dessa 什么时候开放权重？”），并给 3～6 个用于匹配后续报道的关键词。
不要换成回答里提到的、他没问的别的问题，也不要往里加他没问的细节（参数、价格、评测之类）。
他的问题回答已经答上了（哪怕只答了一部分）、他问的是概念解释或看法、或者问题太泛，pending 就是 null；已在留意的问题不要重复。
输出 JSON：{"add": [{"kind": "...", "text": "第二人称、不超过 30 字，如“你在评估用 FunASR 替换 Whisper”",
"keywords": ["用于匹配新闻的关键词，中英文都给，3～8 个"]}], "update": [{"id": 编号, "text": "...", "keywords": [...]}],
"remove": [编号], "pending": {"text": "...", "keywords": [...]} 或 null}。
没有就输出 {"add": [], "update": [], "remove": [], "pending": null}。"""

FEEDBACK_SYSTEM = """下面是一位 AI 从业者最近 30 天对一些事件的行为，以及他已有的记忆。据此维护“由反馈归纳的记忆”。
信号强弱：有用、你分享的、追踪是强的正面信号；在事件下提问是中等信号（问一件事不代表长期关注这一类）；
会错过是较弱的正面信号，不会错过是较弱的负面信号。只是打开、看过的不算信号，不在下面列出。
带原因的“不需要：不想看这类 / 不想看这个来源”已经单独记下了，只作为规律的一部分；
“不需要：其他原因”和没写原因的“不需要”是他没说为什么，正要靠你把这几件放在一起找共同点：几件明显是同一类时归纳成 avoid，看不出就先不动；
“不需要：已经知道了”只说明内容重复，不代表不感兴趣，不要当作负面信号；“误点了不需要，已撤回”不是证据，忽略。
只有呈现出明确规律时（例如几件同类事件都追踪或提问过、都标了不需要）才归纳，单条不下结论。
记忆写的是长期的方向和类别，不要写某条新闻里的产品名、模型名、版本号；keywords 也写这一类内容常用的通用词。
不想看某类内容时 kind 用 avoid，keywords 只写内容关键词，不要写来源名；
不想看某个来源时 kind 用 avoid_source，text 用来源的名字，keywords 只写该来源〔〕里的标识，原样照抄。
已有记忆里标注“由反馈归纳”的是以前从这些行为归纳出来的：证据不再支持的（比如支撑它的“不需要”被撤回了）放进 remove，
需要修正的放进 update；其他记忆只作参考，不要改动。已有记忆（不论出处）里有相近方向的就不要新增，
由反馈归纳的那条可以 update 改得更准。
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

CONSOLIDATE_SYSTEM = """下面是一位 AI 从业者的个人记忆，每行是编号、类别、强度、文字和关键词。记忆是从他的行为里陆续归纳的，
常常同一个方向换着说法记了很多条，请整理：
1. 同一类别里属于同一个大方向的几条合并成一条（例如“开源语音模型”“同声传译、空间音频”“语音转录”都是开源语音模型），
   文字概括这个方向，关键词取这个方向最常用的说法。整理后每一类最好不超过 6 条；方向确实不同的不要硬合并，不同类别不要合并。
2. 记忆写的是长期的方向，文字和关键词里不要有某条新闻里的产品名、模型名、版本号（他明确关注的公司、开源项目除外），
   单独一条里有这类词的也改写（ids 只写它一个）。
没有要改的就输出空数组。
输出 JSON：{"merge": [{"ids": [编号, ...], "text": "第二人称、不超过 30 字", "keywords": ["3～8 个中英文关键词"]}]}"""

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


def strength(m: dict, now: datetime) -> float:
    """当前强度：存的是最近一次被印证（seen）时的强度，之后按这一类的半衰期减弱。"""
    days, seen = DECAY_DAYS.get(m["kind"]), parse_time(m.get("seen") or m["updated"])
    if not days or seen is None:
        return m["strength"]
    return m["strength"] * 0.5 ** (max((now - seen).total_seconds(), 0) / 86400 / days)


def _boost(store, m: dict, amount: float, now: datetime) -> None:
    """加强（记为一次印证）或减弱一条记忆。减弱不算印证：保留 seen，存的值换算回 seen 那一刻。"""
    current = strength(m, now)
    if amount > 0:
        m["strength"], m["seen"] = min(current + amount, MAX_STRENGTH), to_iso(now)
        store.set_strength(m["id"], m["strength"], now)
    else:
        decayed = current / m["strength"] if m["strength"] else 1.0
        m["strength"] = max(current + amount, 0.0) / decayed
        store.set_strength(m["id"], m["strength"], None)


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
        scale = max(FLOOR, min(1.0, strength(m, now) / FULL))  # 只影响加分；不想看、来源的作用是有或没有
        item = Interest(name=m["text"][:12], weight=WEIGHTS.get(m["kind"], 1.0) * scale, keywords=m["keywords"],
                        said=m["text"])
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
    for m in sorted(active(store, now), key=lambda m: -strength(m, now)):  # 每类只写前 8 条，强的在前
        groups.setdefault(m["kind"], []).append(m["text"])
    for kind, label in KINDS.items():
        if kind in groups:
            lines.append(f"{label}：" + "；".join(groups[kind][:8]))
    return "\n".join(lines) if lines else "（用户关闭了个性化，按通用 AI 从业者的视角写）"


def apply_ops(store, ops: dict, origin: str, now: datetime, thread_id: int | None = None,
              event_id: int | None = None) -> dict:
    """执行模型给出的增删改，返回实际生效的条目，供 App 当场展示“已记住”。"""
    existing = {m["id"]: m for m in store.memories()}
    pool = list(existing.values())
    added, updated, removed = [], [], []
    for item in ops.get("add") or []:
        kind, text = item.get("kind"), str(item.get("text") or "").strip()[:80]
        if kind not in KINDS or not text:
            continue
        expires = now + timedelta(days=TASK_DAYS) if kind == "task" else None
        keywords = [str(k)[:40] for k in item.get("keywords") or []][:10]
        if same := similar(pool, kind, text, keywords):  # 换个说法的同一件事：加强已有的那条，不再新增
            _boost(store, same, FULL, now)
            if kind == "task":
                store.update_memory(same["id"], now, expires=expires)
            updated.append({"id": same["id"], "kind": kind, "text": same["text"]})
            continue
        mid = store.add_memory(kind, text, keywords, origin, now, expires, thread_id, event_id)
        pool.append({"id": mid, "kind": kind, "text": text, "keywords": keywords, "strength": FULL,
                     "seen": to_iso(now), "updated": to_iso(now)})
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


_BOILERPLATE = re.compile(r"^你(关注|不想看|在|希望|更信任)|相关的内容$|的内容$")


def similar(memories: list[dict], kind: str, text: str, keywords: list[str]) -> dict | None:
    """同一类里意思几乎一样的那条：去掉“你关注”这类套话后两字片段重合过半，或关键词至少 3 个相同且占一半以上。"""
    def grams(t):
        t = _BOILERPLATE.sub("", re.sub(r"\s+", "", t.lower()))
        return {t[i:i + 2] for i in range(len(t) - 1)}

    mine, keys = grams(text), {k.lower() for k in keywords}
    for m in memories:
        if m["kind"] != kind:
            continue
        theirs, other = grams(m["text"]), {k.lower() for k in m["keywords"]}
        common = len(keys & other)
        if (mine and theirs and len(mine & theirs) >= 0.5 * len(mine | theirs)) \
                or (common >= 3 and common >= 0.5 * min(len(keys), len(other))):
            return m
    return None


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
    """有新的主动行为时（最多 LEARN_EVERY 一次），结合最近 30 天的主动行为归纳记忆；
    只改动由反馈归纳的记忆，对话里说过的不动。只是看过的（PASSIVE）只加强已有记忆，不在这里归纳。"""
    empty = {"added": [], "updated": [], "removed": []}
    last = parse_time(store.setting("learned_at") or "")
    if last and now - last < LEARN_EVERY:
        return empty
    recent = [a for a in store.actions(now - timedelta(days=LEARN_DAYS)) if a[2] not in PASSIVE]
    if not recent or recent[-1][0] <= int(store.setting("actions_learned", "0")):
        return empty
    store.set_setting("actions_learned", str(recent[-1][0]))
    store.set_setting("learned_at", to_iso(now))
    lines = evidence(config, store, recent)[-40:]
    learned = {m["id"] for m in store.memories() if m["origin"] == FEEDBACK_ORIGIN}
    weights = [weight for _, weight in lines]
    if sum(w > 0 for w in weights) < 2 and not learned:
        return empty
    memories = "\n".join(f"{m['id']}: [{m['kind']}] {m['text']}" + ("（由反馈归纳）" if m["id"] in learned else "")
                         for m in store.memories()) or "（暂无）"
    shown = "\n".join(f"{n}. {line}" for n, (line, _) in enumerate(lines, 1)) or "（暂无）"
    try:
        ops = llm.json(FEEDBACK_SYSTEM, f"已有记忆：\n{memories}\n\n行为：\n{shown}", max_tokens=600)
    except Exception as e:
        log.warning("feedback learning failed: %r", e)
        return empty
    # 单条不下结论：新增记忆至少要两件事、足够分量的主动行为支撑；光靠提示词模型做不到，这里兜底
    ops["add"] = [a for a in ops.get("add") or [] if isinstance(a, dict) and _supported(a, weights)]
    ops["update"] = [u for u in ops.get("update") or [] if isinstance(u, dict) and _int(u.get("id")) in learned]
    ops["remove"] = [r for r in ops.get("remove") or [] if _int(r) in learned]
    return apply_ops(store, ops, FEEDBACK_ORIGIN, now)


def _supported(item: dict, weights: list[float]) -> bool:
    cited = [weights[n - 1] for n in {_int(i) for i in item.get("evidence") or []} if n and 1 <= n <= len(weights)]
    return sum(w > 0 for w in cited) >= 2 and sum(cited) >= LEARN_MIN


def evidence(config: Config, store, actions) -> list[tuple[str, float]]:
    """把主动行为按事件汇总成“[追踪，提问 2 次] 标题（来源：r/LocalLLaMA〔LocalLLaMA〕）”，并给出分量（见 ACTIVE）：
    0 是不算证据的（已经知道了、误点已撤回）。"""
    state: dict[int, dict] = {}
    for _, event_id, kind, value, _ in actions:
        if kind in PASSIVE:
            continue
        s = state.setdefault(event_id, {"feedback": None, "reason": None, "restored": False, "follow": False,
                                        "asks": 0, "rating": None, "shared": False})
        if kind in ("useful", "not_needed"):
            s["feedback"], s["reason"] = kind, value
        elif kind in ("clear", "restore"):
            s["feedback"], s["reason"] = None, None
            s["restored"] = s["restored"] or kind == "restore"
        elif kind in ("follow", "unfollow"):
            s["follow"] = kind == "follow"
        elif kind == "ask":
            s["asks"] += 1
        elif kind == "rating":
            s["rating"] = value == "1"
        elif kind == "share":
            s["shared"] = True
    briefs = store.briefs(state.keys())
    labels = build_sources(config.sources)
    lines = []
    for event_id, s in state.items():
        tags, neutral, weight = [], [], 0.0
        if s["feedback"] == "useful":
            tags.append("有用")
            weight += ACTIVE["useful"]
        elif s["feedback"] == "not_needed" and s["reason"] == "known":
            neutral.append("不需要：已经知道了")
        elif s["feedback"] == "not_needed":
            tags.append("不需要" + (f"：{REASONS[s['reason']]}" if s["reason"] in REASONS else ""))
            weight += ACTIVE["not_needed"]
        elif s["restored"]:
            neutral.append("误点了不需要，已撤回")
        if s["shared"]:
            tags.append("你分享的")
            weight += ACTIVE["share"]
        if s["follow"]:
            tags.append("追踪")
            weight += ACTIVE["follow"]
        if s["asks"]:
            tags.append(f"提问 {s['asks']} 次")
            weight += min(s["asks"], 2) * ACTIVE["ask"]  # 同一件事问再多也只是对这一件感兴趣
        if s["rating"] is not None:
            tags.append("会错过" if s["rating"] else "不会错过")
            weight += ACTIVE["rating"]
        signals = store.event_signals(event_id)
        if not (tags or neutral) or not signals:
            continue
        headline = briefs[event_id]["headline"] if event_id in briefs else signals[0].title
        origins = "、".join(dict.fromkeys("{}〔{}〕".format(*source_of(x, labels)) for x in signals[:6]))
        lines.append((f"[{'，'.join(neutral + tags)}] {headline}（来源：{origins}）", weight))
    return lines


def reinforce(store, now: datetime) -> int:
    """把上次以来的新行为记到相关记忆的强度上，返回改动的记忆条数：正面行为（含打开、读完、看原文）加强匹配的
    关注 / 正在做 / 偏好来源，“不需要”加强匹配的不想看、稍微减弱匹配的关注，撤回时反过来。
    不调用模型，也不改 memory_rev：强度只影响排序权重，排序每次现算，不必重写简报。"""
    rows = store.actions_after(int(store.setting("actions_reinforced", "0")))
    if not rows:
        return 0
    store.set_setting("actions_reinforced", str(rows[-1][0]))
    memories = [m for m in active(store, now) if m["keywords"] and m["kind"] != "style"]
    texts, touched = {}, set()
    reasons = {}  # 每件事最近一次“不需要”的原因，撤回时据此判断当时有没有算数
    for action_id, event_id, kind, value, at in rows:
        if kind == "restore":
            value = reasons[event_id] if event_id in reasons else _last_reason(store, event_id, action_id)
        if kind == "not_needed":
            reasons[event_id] = value
        effect = _effect(kind, value)
        if not effect or not memories:
            continue
        if event_id not in texts:
            events = group_events(store.event_signals(event_id))
            e = events[0] if events else None  # 和 Ranker.score 一样：内容匹配标题摘要，来源匹配来源名和频道
            texts[event_id] = e and (" ".join([e.lead.title, e.lead.summary] + [x.title for x in e.signals]).lower(),
                                     " ".join(f"{x.source} {x.channel} {x.author}" for x in e.signals).lower())
        if not texts[event_id]:
            continue
        text, origin = texts[event_id]
        for m in memories:
            amount = effect.get(m["kind"])
            # 当场因这个行为记下的记忆（创建时间不早于它）不再拿它加强一遍
            if amount and at > m["created"] and any(
                    keyword_in(k.lower(), origin if m["kind"] in ON_ORIGIN else text) for k in m["keywords"]):
                _boost(store, m, amount, now)
                touched.add(m["id"])
    return len(touched)


def _effect(kind: str, value: str | None) -> dict[str, float]:
    """一个行为对各类记忆强度的作用 {记忆类别: 加减多少}。"""
    if kind in ("not_needed", "restore"):  # 撤回“不需要”时反过来
        sign = 1 if kind == "not_needed" else -1
        if value == "known":  # 已经知道了只说明重复，不代表喜好
            return {}
        if value == "source":  # 不想看的是来源，不是内容
            return {"avoid_source": sign * ACTIVE["not_needed"]}
        return {"avoid": sign * ACTIVE["not_needed"], "interest": -sign, "task": -sign}
    if kind == "clear":  # 撤回了“有用”
        amount = -ACTIVE["useful"]
    elif kind == "rating":
        amount = ACTIVE["rating"] if value == "1" else 0.0
    elif kind == "open_source":
        amount = 1.0
    elif kind == "read":  # 打开详情：读了 40 秒以上算读完，10 秒以内算点开就走
        try:
            secs = int(json.loads(value or "{}").get("secs") or 0)
        except (ValueError, TypeError, AttributeError):
            secs = 0
        amount = 1.0 if secs >= 40 else 0.5 if secs >= 10 else 0.0
    else:
        amount = ACTIVE.get(kind, 0.0)  # 有用、追踪、分享、提问
    return dict.fromkeys(FOR_INTEREST, amount) if amount else {}


def _last_reason(store, event_id: int, before: int) -> str | None:
    r = store.con.execute("select value from actions where event_id = ? and kind = 'not_needed' and id < ? "
                          "order by id desc limit 1", (event_id, before)).fetchone()
    return r[0] if r else None


ORIGIN_RANK = ["你手动添加", "对话", *REASON_ORIGINS.values(), "漏报", FEEDBACK_ORIGIN]


def consolidate(store, llm, now: datetime) -> dict:
    """每天整理一次记忆：请模型把同一类里意思重复的合并成一条、去掉新闻里的专有名词，再删掉弱到不再成立的
    “由反馈归纳”的长期关注。合并时保留出处最明确的那条（手动添加的不改写文字），强度取最强的再略加，其余软删除。"""
    changes = {"added": [], "updated": [], "removed": []}
    last = parse_time(store.setting("consolidated_at") or "")
    if last and now - last < CONSOLIDATE_EVERY:
        return changes
    store.set_setting("consolidated_at", to_iso(now))
    memories = {m["id"]: m for m in active(store, now)}
    tidy = {i: m for i, m in memories.items() if m["kind"] in KINDS and m["kind"] not in ON_ORIGIN}  # 来源是确切的名字，不合并
    if llm and len(tidy) > 1:
        shown = "\n".join(f"{m['id']}: [{KINDS[m['kind']]}] 强度 {strength(m, now):.1f} {m['text']}"
                          f"（{'、'.join(m['keywords'])}）" for m in tidy.values())
        try:
            ops = llm.json(CONSOLIDATE_SYSTEM, shown, max_tokens=1500)
        except Exception as e:
            log.warning("memory consolidation failed: %r", e)
            ops = {}
        used = set()
        for group in ops.get("merge") or []:
            ids = list(dict.fromkeys(i for i in (_int(x) for x in (group.get("ids") or [] if isinstance(group, dict)
                                                                  else [])) if i in tidy and i not in used))
            if not ids or len({tidy[i]["kind"] for i in ids}) > 1:
                continue
            used.update(ids)
            ms = sorted((tidy[i] for i in ids), key=lambda m: (
                ORIGIN_RANK.index(m["origin"]) if m["origin"] in ORIGIN_RANK else len(ORIGIN_RANK), -strength(m, now)))
            keep, rest = ms[0], ms[1:]
            fields = {}
            if keep["origin"] != "你手动添加" and str(group.get("text") or "").strip():
                fields["text"] = str(group["text"]).strip()[:80]
                if group.get("keywords"):
                    fields["keywords"] = [str(k)[:40] for k in group["keywords"]][:10]
            if not (rest or fields):
                continue
            if rest and keep["event_id"] and keep["origin"] in REASON_ORIGINS.values():
                fields["event_id"] = None  # 合进了别的证据，撤回那一次反馈时不再整条删掉
            expires = [parse_time(m["expires"]) for m in ms if m["expires"]]
            if keep["kind"] == "task" and expires:
                fields["expires"] = max(expires)
            if fields:
                store.update_memory(keep["id"], now, **fields)
            if rest:
                store.set_strength(keep["id"], min(max(strength(m, now) for m in ms) + 0.5 * len(rest), MAX_STRENGTH),
                                   now)
            changes["updated"].append({"id": keep["id"], "kind": keep["kind"], "text": fields.get("text", keep["text"])})
            for m in rest:
                store.update_memory(m["id"], now, deleted=True)
                changes["removed"].append({"id": m["id"], "kind": m["kind"], "text": m["text"]})
    for m in memories.values():
        if m["id"] not in {x["id"] for x in changes["removed"]} and m["origin"] == FEEDBACK_ORIGIN \
                and m["kind"] in DECAY_DAYS and strength(m, now) < RETIRE:
            store.update_memory(m["id"], now, deleted=True)
            changes["removed"].append({"id": m["id"], "kind": m["kind"], "text": m["text"]})
    if changes["updated"] or changes["removed"]:
        bump(store)
    return changes


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
