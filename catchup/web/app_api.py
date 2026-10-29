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
from ..pipeline import push, reports, translate
from ..pipeline.brief import candidates, final_score, hot
from ..pipeline.events import group_events
from ..pipeline.update import ranked
from ..sources import Source, build_sources, source_of
from ..store import Store
from ..util import parse_time, to_iso

MAX_PENDING_DEVICES = 10
# 反馈值和可带的原因：不需要可以说明原因（见 personal.REASONS），不想看这类时带上从 /reasons 里选的那一类；
# 在“未追踪信息”里点“想看这类”是 useful + topic
FEEDBACK_REASONS = {"not_needed": {None, *personal.REASONS}, "useful": {None, "topic"}, None: {None}}
SKIP_LIMIT = 10
ANSWER_DAYS = 3
FOLLOW_DAYS = 30
DISMISSED_DAYS = 30


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
        return 200, feed(config, store, now, parse_time((query or {}).get("before")))
    if method == "GET" and (m := re.fullmatch(r"/v1/reports/(daily|weekly|monthly)(?:/([\d-]+))?", path)):
        out = report(config, store, m.group(1), m.group(2), now)
        return (200, out) if out else (404, {"error": "not found"})
    if method == "GET" and path == "/v1/dismissed":
        return 200, {"events": dismissed(store, now)}
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
    if method == "GET" and action == "/reasons":  # 点“不需要”时让用户选的几类内容
        return 200, {"options": personal.avoid_options(config, store, from_config(config.llm), event_id, now)}
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
            made = personal.from_reason(config, store, from_config(config.llm), event_id, value, reason, now,
                                        _picked(body.get("topic")))
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

def _picked(topic) -> dict | None:
    """App 传回的、用户选中的那一类：{text, keywords}。"""
    if not isinstance(topic, dict) or not str(topic.get("text") or "").strip():
        return None
    keywords = [str(k)[:40] for k in topic.get("keywords") or [] if str(k).strip()][:8]
    return {"text": str(topic["text"]).strip()[:12], "keywords": keywords} if keywords else None


def dismissed(store: Store, now: datetime) -> list[dict]:
    """最近标记为不需要的事件，新标记的在前；App 里可以恢复（反馈改回空）。"""
    rows = [(i, at, reason) for i, value, at, reason in store.feedback_since(
        to_iso(now - timedelta(days=DISMISSED_DAYS))) if value == "not_needed"][::-1][:50]
    briefs = store.briefs(i for i, _, _ in rows)
    signals = {i: store.event_signals(i) for i, _, _ in rows if i not in briefs}
    zh = _zh(store, [s[0].title for s in signals.values() if s])
    out = []
    for i, at, reason in rows:
        headline = briefs[i]["headline"] if i in briefs else (zh(signals[i][0].title) if signals[i] else "")
        if headline:
            out.append({"id": i, "headline": headline, "at": at, "reason": personal.REASONS.get(reason, "")})
    return out


def feed(config: Config, store: Store, now: datetime, before: datetime | None = None) -> dict:
    """信息流：模型判定为新闻（重要性够推送门槛）的事件和推送过的事件，按见报时间从新到旧。还没写简报的前排和热门事件
    先显示，写完简报再按重要性决定去留。一页是一个时间窗（window_hours）里见报的事件，before 为空时是最新一页，
    older 是下一页的 before，到开始采集为止；追踪中、未追踪信息只在第一页。"""
    sources = build_sources(config.sources)
    until = before or now
    since = until - timedelta(hours=config.ranking.get("window_hours", 72))
    _, events = ranked(config, store, until)
    briefs, feedback, pushed = store.briefs(e.id for e in events), store.feedback(), store.pushed()
    follows = {f["event_id"]: f for f in store.follows()}
    bar = config.push.get("min_importance", push.DEFAULTS["min_importance"])
    pending = {e.id for e in candidates(events, config.llm.get("brief_top", 25), sources) if e.id not in briefs}
    published = store.event_published(e.id for e in events)
    zh = _zh(store, [t for e in events if e.id not in briefs
                     for t in (e.lead.title, e.lead.summary[:translate.SUMMARY_CHARS])])
    shown = [e for e in events if feedback.get(e.id) != "not_needed" and since <= published[e.id] < until
             and (e.id in pushed or e.id in pending or briefs.get(e.id, {}).get("importance", 0) >= bar)]
    shown.sort(key=lambda e: published[e.id], reverse=True)
    created = store.event_created(e.id for e in shown)
    started = store.first_fetched()
    out = {"generated": to_iso(now),
           "events": [_event(e, briefs.get(e.id), feedback.get(e.id), pushed.get(e.id), created.get(e.id), sources, zh,
                             e.id in follows) for e in shown],
           "older": to_iso(since) if started and since > started else None}
    if before is None:
        events.sort(key=lambda e: -final_score(e, briefs.get(e.id)))
        out.update(following=following(config, store, follows, now), answers=answers(store, now),
                   skipped=skipped(events, briefs, feedback, sources, zh, bar), personalize=personal.personalized(store))
    return out


def skipped(events: list[Event], briefs: dict, feedback: dict, sources, zh, min_importance: int) -> list[dict]:
    """热度高但和你无关、重要性也不够推送的事件（首页的“未追踪信息”），你可以点开纠正。
    热度见 brief.hot。"""
    out = []
    for e in events:
        b = briefs.get(e.id)
        if feedback.get(e.id) or (b and b["importance"] >= min_importance):
            continue
        demoted = [r for r in e.reasons if r.endswith("已降低优先级")]
        if e.topics and not demoted:  # 和你有关、只是不够重要的，不算替你略过
            continue
        if not hot(e, sources):
            continue
        outlets = len({source_of(s, sources)[0] for s in e.signals})
        out.append({"id": e.id, "headline": b["headline"] if b else zh(e.lead.title), "reports": len(e.signals),
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
    zh = _zh(store, [s.title for s in signals] + [event.lead.summary[:translate.SUMMARY_CHARS]])
    out = _event(event, brief, store.feedback().get(event_id), store.pushed().get(event_id),
                 store.event_created([event_id]).get(event_id), sources, zh, event_id in following_ids)
    out["signals"] = [{"title": zh(s.title), "url": s.url, "label": _label(s, sources), "time": to_iso(s.published),
                       "summary": s.summary[:240]} for s in reversed(signals)]
    out["updates"] = store.event_updates([event_id]).get(event_id, [])
    out["points"] = (brief.get("points") or []) if brief else []
    out["questions"] = brief.get("questions", []) if brief else []
    out["would_miss"] = store.rating(event_id)
    thread = store.latest_thread(event_id)
    out["thread"] = {"id": thread["id"], "count": len(store.messages(thread["id"]))} if thread else None
    if chat.needs_reading(store, signals, now):
        chat.prefetch(config, event_id)
    return out


def _event(e: Event, brief: dict | None, feedback: str | None, pushed_at: str | None, created, sources, zh,
           following: bool = False) -> dict:
    lead = e.lead
    return {
        "id": e.id,
        "headline": brief["headline"] if brief else zh(lead.title),
        "summary": brief["summary"] if brief else zh(lead.summary[:translate.SUMMARY_CHARS]),
        "why": brief["why"] if brief else "",
        "importance": brief["importance"] if brief else None,
        "topic": brief["topic"] if brief else None,  # 主话题，App 写在来源前面；topics 是排序命中的兴趣和记忆
        "ai": brief is not None,
        "highlight": bool(brief and brief["importance"] >= 4),
        "score": final_score(e, brief),
        "topics": e.topics,
        "reasons": e.reasons,
        "url": lead.url,
        "time": to_iso(min(s.published for s in e.signals)),  # 见报时间，信息流按它排
        "first_seen": to_iso(created),
        "source_count": len(e.signals),
        "sources": list(dict.fromkeys(_label(s, sources) for s in e.signals))[:6],
        "source": _label(lead, sources),  # “不想看这个来源”指的就是它
        "feedback": feedback,
        "pushed": pushed_at is not None,
        "pushed_at": pushed_at,  # App 把离开期间推送过的事件也算作新的
        "following": following,
    }


def report(config: Config, store: Store, kind: str, period: str | None, now: datetime) -> dict | None:
    """一份报告，period 为空时是最新一份；还没有这类报告时 period 为空，upcoming 是第一份出来的日期。
    要点、趋势里的事件和分领域的条目都带上事件标题，App 点开进详情。"""
    periods = store.report_periods(kind)
    period = period or (periods[0] if periods else None)
    out = {"kind": kind, "name": reports.KINDS[kind], "period": period}
    if period is None:
        today = reports.local_now(config.push, now).date()
        first = reports.end(kind, reports.current(kind, today))
        return {**out, "upcoming": f"{first.month}月{first.day}日"}
    r = store.report(kind, period)
    if r is None:
        return None
    c = r["content"]
    ids = {i for key in ("highlights", "trends", "ongoing") for x in c.get(key, []) for i in x["events"]}
    ids |= {item["event"] for s in c["sections"] for item in s["items"]}
    briefs = store.briefs(ids)

    def refs(ids):
        return [{"id": i, "headline": briefs[i]["headline"]} for i in ids if i in briefs]

    def notes(key):
        return [{"text": x["text"], "events": refs(x["events"])} for x in c.get(key, [])]

    i = periods.index(period)
    return {**out, "label": reports.label(kind, reports.start_of(kind, period)),
            "highlights": notes("highlights"), "trends": notes("trends"), "ongoing": notes("ongoing"),
            "sections": [{"name": s["name"], "items": [{"id": item["event"], "headline": briefs[item["event"]]["headline"],
                                                        "text": item["text"]} for item in s["items"] if item["event"] in briefs]}
                         for s in c["sections"]],
            "prev": periods[i + 1] if i + 1 < len(periods) else None, "next": periods[i - 1] if i > 0 else None}


def _zh(store: Store, texts: list[str]):
    """把英文原文换成译文（见 pipeline/translate.py）的函数，还没翻到的原样返回。"""
    done = store.translations(texts)
    return lambda text: done.get(text, text)


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
            "questions": questions}


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
