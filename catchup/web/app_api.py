"""手机 App 的接口，挂在 /v1 下，经 Caddy 的 https://43.139.27.91:8443/v1/* 对外。

认证：App 首次启动调用 POST /v1/devices 换取令牌。还没有任何已批准设备时自动批准（只有本人使用），
之后的新设备处于待确认状态，需要在服务器上运行 `python3 -m catchup devices approve <id>`。
数据库只存令牌的 sha256。

handle() 返回 (状态码, JSON)；对话接口返回 (200, 生成器)，由 server.py 以 SSE 流式写回。
"""
from __future__ import annotations

import hashlib
import re
import secrets
from datetime import datetime, timedelta, timezone

from .. import chat, personal, share
from ..config import Config
from ..llm import from_config
from ..models import Event
from ..pipeline.brief import final_score
from ..pipeline.events import group_events
from ..pipeline.update import ranked
from ..sources import Source, build_sources, source_of
from ..store import Store
from ..util import parse_time, to_iso

FEED_LIMIT = 80
MAX_PENDING_DEVICES = 10
# 反馈值和可带的原因：不需要可以说明原因（见 personal.REASONS）；在“替你略过的”里点“想看这类”是 useful + topic
FEEDBACK_REASONS = {"not_needed": {None, *personal.REASONS}, "useful": {None, "topic"}, None: {None}}
SKIP_LIMIT = 10
ANSWER_DAYS = 3
FOLLOW_DAYS = 30
DISMISSED_DAYS = 30

DIGEST_SYSTEM = """用户离开了一段时间，下面是这期间出现的值得他知道的事件。写一段补课简报：2～4 句、不超过 140 个字，
先说最重要的；直接用“你”称呼他，口吻像替他盯着信息的助理；结合他的情况点明和他的关系；不要列表、不要寒暄。
输出 JSON：{"text": "..."}"""


def handle(config: Config, store: Store, method: str, path: str, headers, body: dict | None,
           query: dict | None = None) -> tuple[int, object]:
    now = datetime.now(timezone.utc)
    body = body or {}
    if method == "POST" and path == "/v1/devices":
        return register(store, body.get("name", ""), now)
    device = store.device_by_token(_hash(_bearer(headers)))
    if device is None:
        return 401, {"error": "unknown device"}
    if path == "/v1/me":
        return 200, device
    if not device["approved"]:
        return 403, {"error": "device not approved", "device": device}
    store.touch_device(device["id"], now)

    if method == "GET" and path == "/v1/feed":
        return 200, feed(config, store, now)
    if method == "GET" and path == "/v1/dismissed":
        return 200, {"events": dismissed(store, now)}
    if method == "GET" and path == "/v1/digest":
        return 200, digest(config, store, int((query or {}).get("since", "0") or 0), now)
    if method == "POST" and path == "/v1/chat":
        llm = from_config(config.llm)
        if llm is None:
            return 503, {"error": "没有配置大模型"}
        return 200, chat.chat(config, store, llm, body, now)
    if path == "/v1/memories":
        return (200, memories(config, store, now)) if method == "GET" else add_memory(config, store, body, now)
    if method == "POST" and (m := re.fullmatch(r"/v1/memories/(\d+)", path)):
        return edit_memory(config, store, int(m.group(1)), body, now)
    if method == "POST" and path == "/v1/settings":
        if "personalize" in body:
            store.set_setting("personalize", "1" if body["personalize"] else "0")
            personal.bump(store)
            personal.rebrief_async(config)
        return 200, {"personalize": personal.personalized(store)}
    if method == "POST" and path == "/v1/misses":
        text = str(body.get("text") or "").strip()[:500]
        llm = from_config(config.llm)
        if not text or llm is None:
            return 400, {"error": "内容是空的"}
        changes = personal.from_miss(store, llm, text, now)
        if any(changes.values()):
            personal.rebrief_async(config)
        return 200, changes
    if method == "POST" and path == "/v1/shares":
        event_id = share.share(config, store, from_config(config.llm), str(body.get("text") or "")[:4000],
                               str(body.get("subject") or ""), now)
        return (200, {"event_id": event_id}) if event_id else (400, {"error": "没有找到链接"})
    if method == "POST" and (m := re.fullmatch(r"/v1/questions/(\d+)", path)):
        if body.get("drop"):  # 不用再替我留意了
            store.update_question(int(m.group(1)), status="dropped")
        return 200, {"ok": True}
    if m := re.fullmatch(r"/v1/events/(\d+)(/\w+)?", path):
        return event_route(config, store, method, int(m.group(1)), m.group(2) or "", body, now)
    if m := re.fullmatch(r"/v1/threads/(\d+)", path):
        thread = store.thread(int(m.group(1)))
        return (200, {"thread": thread, "messages": store.messages(thread["id"])}) if thread else (404, {"error": "not found"})
    return 404, {"error": "not found"}


def event_route(config: Config, store: Store, method: str, event_id: int, action: str, body: dict,
                now: datetime) -> tuple[int, object]:
    if method == "GET" and action == "":
        detail = event_detail(config, store, event_id, now)
        return (200, detail) if detail else (404, {"error": "not found"})
    if method == "GET" and action == "/thread":
        thread = store.latest_thread(event_id)
        return 200, {"thread": thread, "messages": store.messages(thread["id"]) if thread else []}
    if method != "POST":
        return 404, {"error": "not found"}
    if action == "/feedback":
        value, reason = body.get("value"), body.get("reason")
        if value not in FEEDBACK_REASONS or reason not in FEEDBACK_REASONS[value]:
            return 400, {"error": "bad value"}
        previous = store.feedback().get(event_id)
        store.set_feedback(event_id, value, now, reason)
        if value or previous:  # 撤回“不需要”单独记一笔，每小时的反馈学习据此不再把它当证据
            store.add_action(event_id, value or ("restore" if previous == "not_needed" else "clear"), reason, now)
        changes = {"added": [], "updated": [], "removed": []}
        if previous and value != previous:  # 撤回或改了反馈：当时因原因记下的记忆一起删
            changes = personal.retract(store, event_id, now)
        if reason in ("topic", "source"):
            made = personal.from_reason(config, store, from_config(config.llm), event_id, value, reason, now)
            changes = {**made, "removed": changes["removed"] + made["removed"]}
        if any(changes.values()):
            personal.rebrief_async(config)
        return 200, {"ok": True, "memory": changes}
    if action == "/follow":
        on = bool(body.get("on"))
        store.set_follow(event_id, on, len(store.event_signals(event_id)), now)
        store.add_action(event_id, "follow" if on else "unfollow", None, now)
        return 200, {"following": on}
    if action == "/rating":
        would_miss = bool(body.get("would_miss"))
        store.set_rating(event_id, would_miss, now)
        store.add_action(event_id, "rating", "1" if would_miss else "0", now)
        return 200, {"ok": True}
    if action == "/opened":  # 在详情页点开了原文
        store.add_action(event_id, "open_source", None, now)
        return 200, {"ok": True}
    return 404, {"error": "not found"}


def register(store: Store, name: str, now: datetime) -> tuple[int, dict]:
    devices = store.devices()
    if sum(not d["approved"] for d in devices) >= MAX_PENDING_DEVICES:
        return 429, {"error": "too many pending devices"}
    approved = not any(d["approved"] for d in devices)
    token = secrets.token_urlsafe(32)
    device_id = store.add_device(str(name)[:80], _hash(token), approved, now)
    return 200, {"id": device_id, "token": token, "approved": approved}


# ---------- 事件流与详情 ----------

def dismissed(store: Store, now: datetime) -> list[dict]:
    """最近标记为不需要的事件，新标记的在前；App 里可以恢复（反馈改回空）。"""
    rows = [(i, at, reason) for i, value, at, reason in store.feedback_since(
        to_iso(now - timedelta(days=DISMISSED_DAYS))) if value == "not_needed"][::-1][:50]
    briefs = store.briefs(i for i, _, _ in rows)
    out = []
    for i, at, reason in rows:
        signals = [] if i in briefs else store.event_signals(i)
        headline = briefs[i]["headline"] if i in briefs else (signals[0].title if signals else "")
        if headline:
            out.append({"id": i, "headline": headline, "at": at, "reason": personal.REASONS.get(reason, "")})
    return out


def feed(config: Config, store: Store, now: datetime) -> dict:
    sources = build_sources(config.sources)
    _, events = ranked(config, store, now)
    briefs, feedback, pushed = store.briefs(e.id for e in events), store.feedback(), store.pushed()
    follows = {f["event_id"]: f for f in store.follows()}
    events = [e for e in events if feedback.get(e.id) != "not_needed"]
    events.sort(key=lambda e: -final_score(e, briefs.get(e.id)))
    passed = skipped(events, briefs, feedback, sources)
    events = events[:FEED_LIMIT]
    created = store.event_created(e.id for e in events)
    out = [_event(e, briefs.get(e.id), feedback.get(e.id), e.id in pushed, created.get(e.id), sources,
                  e.id in follows) for e in events]
    return {"generated": to_iso(now), "events": out, "following": following(config, store, follows, now),
            "answers": answers(store, now), "skipped": passed, "personalize": personal.personalized(store)}


def skipped(events: list[Event], briefs: dict, feedback: dict, sources) -> list[dict]:
    """热度高但和你无关、没放进“值得知道”的事件：首页说出替你略过了什么，你可以点开纠正。
    热度按报道的不同媒体数算（同一家刷十条是垃圾内容，不算热），或者来源自己的热度。"""
    out = []
    for e in events:
        b = briefs.get(e.id)
        if feedback.get(e.id) or (b and b["importance"] >= 4):
            continue
        demoted = [r for r in e.reasons if r.endswith("已降低优先级")]
        if e.topics and not demoted:  # 和你有关、只是不够重要的，不算替你略过
            continue
        outlets = len({source_of(s, sources)[0] for s in e.signals})
        if outlets < 2 and sum(s.heat for s in e.signals) < 1:
            continue
        out.append({"id": e.id, "headline": b["headline"] if b else e.lead.title, "reports": len(e.signals),
                    "outlets": outlets, "why": demoted[0].removesuffix("，已降低优先级") if demoted else "和你的关注无关"})
    return sorted(out, key=lambda x: (-x["outlets"], -x["reports"]))[:SKIP_LIMIT]


def answers(store: Store, now: datetime) -> list[dict]:
    """最近几天有了答案的“替你留意的问题”，首页放在追踪中的最前面。"""
    since = to_iso(now - timedelta(days=ANSWER_DAYS))
    return [{"id": q["id"], "question": q["text"], "answer": q["answer"], "thread_id": q["thread_id"],
             "event_id": q["event_id"], "answered": q["answered"]}
            for q in store.questions("answered") if (q["answered"] or "") >= since]


def following(config: Config, store: Store, follows: dict, now: datetime) -> list[dict]:
    """追踪中的事件（不受 72 小时窗口限制），带最近一条进展，按最近进展时间排序。"""
    if not follows:
        return []
    ids = [i for i, f in follows.items() if now - (parse_time(f["created"]) or now) <= timedelta(days=FOLLOW_DAYS)]
    briefs, updates = store.briefs(ids), store.event_updates(ids)
    out = []
    for i in ids:
        if i not in briefs:
            continue
        latest = (updates.get(i) or [None])[0]
        out.append({"id": i, "headline": briefs[i]["headline"], "update": latest["text"] if latest else "",
                    "updated": latest["at"] if latest else follows[i]["created"],
                    "update_count": len(updates.get(i) or [])})
    return sorted(out, key=lambda x: x["updated"], reverse=True)


def event_detail(config: Config, store: Store, event_id: int, now: datetime) -> dict | None:
    signals = store.event_signals(event_id)
    if not signals:
        return None
    sources = build_sources(config.sources)
    [event] = personal.build_ranker(config, store, now).rank(group_events(signals), now)
    brief = store.briefs([event_id]).get(event_id)
    following_ids = {f["event_id"] for f in store.follows()}
    out = _event(event, brief, store.feedback().get(event_id), event_id in store.pushed(),
                 store.event_created([event_id]).get(event_id), sources, event_id in following_ids)
    out["signals"] = [{"title": s.title, "url": s.url, "label": _label(s, sources), "time": to_iso(s.published),
                       "summary": s.summary[:240]} for s in reversed(signals)]
    out["updates"] = store.event_updates([event_id]).get(event_id, [])
    out["questions"] = brief.get("questions", []) if brief else []
    out["would_miss"] = store.rating(event_id)
    thread = store.latest_thread(event_id)
    out["thread"] = {"id": thread["id"], "count": len(store.messages(thread["id"]))} if thread else None
    if chat.needs_reading(store, signals, now):
        chat.prefetch(config, event_id)
    return out


def _event(e: Event, brief: dict | None, feedback: str | None, pushed: bool, created, sources,
           following: bool = False) -> dict:
    lead = e.lead
    return {
        "id": e.id,
        "headline": brief["headline"] if brief else lead.title,
        "summary": brief["summary"] if brief else lead.summary[:160],
        "why": brief["why"] if brief else "",
        "importance": brief["importance"] if brief else None,
        "ai": brief is not None,
        "highlight": bool(brief and brief["importance"] >= 4),
        "score": final_score(e, brief),
        "topics": e.topics,
        "reasons": e.reasons,
        "url": lead.url,
        "time": to_iso(max(s.published for s in e.signals)),
        "first_seen": to_iso(created),
        "source_count": len(e.signals),
        "sources": list(dict.fromkeys(_label(s, sources) for s in e.signals))[:6],
        "source": _label(lead, sources),  # “不想看这个来源”指的就是它
        "feedback": feedback,
        "pushed": pushed,
        "following": following,
    }


# ---------- 补课简报 ----------

def digest(config: Config, store: Store, since_ms: int, now: datetime) -> dict:
    """离开期间（since 之后出现）的重要事件，写成一段话；同一批事件只写一次。"""
    if since_ms <= 0:
        return {"text": "", "count": 0}
    since = datetime.fromtimestamp(since_ms / 1000, timezone.utc)
    data = feed(config, store, now)
    fresh = [e for e in data["events"] if e["highlight"] and (parse_time(e["first_seen"]) or now) > since]
    if not fresh:
        return {"text": "", "count": 0}
    key = ",".join(str(e["id"]) for e in fresh[:8])
    text = store.digest(key)
    if text is None:
        llm = from_config(config.llm)
        if llm is None:
            return {"text": "", "count": len(fresh)}
        hours = max(1, int((now - since).total_seconds() // 3600))
        lines = "\n".join(f"- {e['headline']}：{e['summary']}（与他的关系：{e['why'] or '无'}）" for e in fresh[:8])
        try:
            text = str(llm.json(DIGEST_SYSTEM, f"用户是谁：\n{personal.focus(config, store, now)}\n\n"
                                               f"离开约 {hours} 小时，期间的事件：\n{lines}", max_tokens=400)
                       .get("text") or "").strip()[:300]
        except Exception:
            return {"text": "", "count": len(fresh)}
        store.save_digest(key, text, now)
    return {"text": text, "count": len(fresh)}


# ---------- 记忆 ----------

def memories(config: Config, store: Store, now: datetime) -> dict:
    items = store.memories()
    briefs = store.briefs(m["event_id"] for m in items if m["event_id"])
    out = []
    for m in items:
        expires = parse_time(m["expires"]) if m["expires"] else None
        out.append({"id": m["id"], "kind": m["kind"], "kind_label": personal.KINDS.get(m["kind"], m["kind"]),
                    "text": m["text"], "origin": m["origin"], "created": m["created"], "updated": m["updated"],
                    "event": briefs[m["event_id"]]["headline"] if m["event_id"] in briefs else None,
                    "days_left": max(0, (expires - now).days) if expires else None,
                    "expired": bool(expires and expires <= now)})
    week_ago = to_iso(now - timedelta(days=7))
    questions = [{"id": q["id"], "text": q["text"], "status": q["status"], "answer": q["answer"], "created": q["created"],
                  "thread_id": q["thread_id"]}
                 for q in store.questions() if q["status"] == "open"
                 or (q["status"] == "answered" and (q["answered"] or "") >= week_ago)]
    return {"memories": out, "kinds": personal.KINDS, "personalize": personal.personalized(store),
            "week": store.week_stats(now - timedelta(days=7)), "questions": questions}


def add_memory(config: Config, store: Store, body: dict, now: datetime) -> tuple[int, dict]:
    kind, text = body.get("kind"), str(body.get("text") or "").strip()[:80]
    if kind not in personal.KINDS or not text:
        return 400, {"error": "bad memory"}
    llm = from_config(config.llm)
    keywords = personal.keywords_for(llm, text) if llm else []
    expires = now + timedelta(days=personal.TASK_DAYS) if kind == "task" else None
    mid = store.add_memory(kind, text, keywords, "你手动添加", now, expires)
    personal.bump(store)
    personal.rebrief_async(config)
    return 200, {"id": mid}


def edit_memory(config: Config, store: Store, memory_id: int, body: dict, now: datetime) -> tuple[int, dict]:
    if body.get("delete"):
        ok = store.update_memory(memory_id, now, deleted=True)
    else:
        fields = {}
        if body.get("kind") in personal.KINDS:
            fields["kind"] = body["kind"]
        if str(body.get("text") or "").strip():
            fields["text"] = str(body["text"]).strip()[:80]
            llm = from_config(config.llm)
            if llm:
                fields["keywords"] = personal.keywords_for(llm, fields["text"])
        if fields.get("kind", "") == "task" or body.get("renew"):
            fields["expires"] = now + timedelta(days=personal.TASK_DAYS)
        ok = bool(fields) and store.update_memory(memory_id, now, **fields)
    if not ok:
        return 404, {"error": "not found"}
    personal.bump(store)
    personal.rebrief_async(config)
    return 200, {"ok": True}


def _label(signal, sources: dict[str, Source]) -> str:
    return source_of(signal, sources)[0]


def _bearer(headers) -> str:
    value = headers.get("Authorization", "")
    return value[7:] if value.startswith("Bearer ") else ""


def _hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()
