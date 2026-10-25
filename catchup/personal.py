"""个人记忆：从对话、反馈、漏报里沉淀“你是谁、在做什么、想看什么”，再反过来影响排序、简报和对话。

记忆分五类（产品论文第 04 章）：正在做的事权重最高且会过期；长期关注；不想看；偏好的来源；希望怎么写。
每条记忆都记录出处，可查看、编辑、删除；个性化可以整体关闭（个人信息保护法第二十四条要求提供不针对个人特征的选项）。
记忆每次变化都把 memory_rev 加一，简报据此判断要不要按新记忆重写“与你有关”。
"""
from __future__ import annotations

import logging
import threading
from datetime import datetime, timedelta, timezone

from .config import Config
from .pipeline.ranking import Interest, Ranker
from .util import parse_time

log = logging.getLogger(__name__)

KINDS = {
    "task": "正在做",
    "interest": "长期关注",
    "avoid": "不想看",
    "source": "偏好的来源",
    "style": "希望怎么写",
}
TASK_DAYS = 21          # “正在做”的事默认三周后过期，过期后不再参与排序
WEIGHTS = {"task": 3.0, "interest": 2.0}

EXTRACT_SYSTEM = """你在维护一位 AI 从业者的个人记忆，用来决定以后给他推什么、怎么写。
根据刚发生的对话，判断用户有没有透露关于他自己的、以后仍然有用的信息。只记用户明确说出或强烈暗示的事实，
不要记对话内容本身，不要记新闻事实，不要猜测。大多数对话不需要记任何东西。
记忆类型：task=最近在做的事或项目；interest=长期关注的领域、技术、公司；avoid=明确不想看的内容；
source=更信任或更想看的来源；style=希望内容怎么写（详略、角度、语言）。
已有记忆会一并给出：同一件事已经记过就用 update 改写，不要重复添加；用户否认了某条就放进 remove。
输出 JSON：{"add": [{"kind": "...", "text": "第二人称、不超过 30 字，如“你在评估用 FunASR 替换 Whisper”",
"keywords": ["用于匹配新闻的关键词，中英文都给，3～8 个"]}], "update": [{"id": 编号, "text": "...", "keywords": [...]}],
"remove": [编号]}。没有就输出 {"add": [], "update": [], "remove": []}。"""

FEEDBACK_SYSTEM = """下面是一位 AI 从业者最近对推送的反馈（有用 / 不需要）和他已有的记忆。
只有当反馈呈现出明确规律时（例如连续几条同类内容都标了不需要）才归纳成记忆，单条反馈不要下结论。
输出 JSON，格式同记忆维护：{"add": [{"kind": "interest|avoid|source", "text": "第二人称、不超过 30 字",
"keywords": [...]}], "update": [], "remove": []}；没有规律就全部输出空数组。"""

MISS_SYSTEM = """用户反映：有一条他关心的消息 Catchup 没有推给他。请把它转成一条记忆，帮助以后不再漏掉同类内容。
输出 JSON：{"add": [{"kind": "interest|task|source", "text": "第二人称、不超过 30 字", "keywords": ["3～8 个中英文关键词"]}],
"update": [], "remove": []}。"""

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
    interests, avoid, sources = list(base.interests), [], []
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
    return Ranker(interests, avoid=avoid, sources=sources, **_ranking_options(config))


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
                      thread_id: int, event_id: int | None) -> dict:
    memories = "\n".join(f"{m['id']}: [{m['kind']}] {m['text']}" for m in store.memories()) or "（暂无）"
    context = f"讨论的事件：{event_headline}\n" if event_headline else ""
    try:
        ops = llm.json(EXTRACT_SYSTEM, f"已有记忆：\n{memories}\n\n{context}用户：{user}\n助手：{reply[:1500]}",
                       max_tokens=600)
    except Exception as e:
        log.warning("memory extraction failed: %r", e)
        return {"added": [], "updated": [], "removed": []}
    return apply_ops(store, ops, "对话", now, thread_id, event_id)


def learn_from_feedback(store, llm, now: datetime) -> dict:
    """有新反馈时，结合最近 30 天的反馈归纳规律；单条反馈不会直接变成记忆。"""
    last = store.setting("feedback_learned", "")
    if not store.feedback_since(last):
        return {"added": [], "updated": [], "removed": []}
    recent = store.feedback_since((now - timedelta(days=30)).isoformat())
    briefs = store.briefs(r[0] for r in recent)
    lines = [f"[{'有用' if v == 'useful' else '不需要'}] {briefs[e]['headline']}" for e, v, _ in recent if e in briefs]
    store.set_setting("feedback_learned", recent[-1][2])
    if len(lines) < 3:
        return {"added": [], "updated": [], "removed": []}
    memories = "\n".join(f"{m['id']}: [{m['kind']}] {m['text']}" for m in store.memories()) or "（暂无）"
    try:
        ops = llm.json(FEEDBACK_SYSTEM, f"已有记忆：\n{memories}\n\n反馈：\n" + "\n".join(lines[-40:]), max_tokens=500)
    except Exception as e:
        log.warning("feedback learning failed: %r", e)
        return {"added": [], "updated": [], "removed": []}
    return apply_ops(store, ops, "你的反馈", now)


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
    """记忆变了以后在后台重写前排事件的简报，让“与你有关”尽快反映新记忆。已有一轮在跑就跳过。"""
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
