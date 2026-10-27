"""对话：在事件下面问（先读来源原文，回答标注出处 [1]），或者直接问自己的事件库（回答标注事件 [E12]）。
两种都会同时联网搜索，原文和事件库里没有的用网页补充；回答里引用到的来源列在最后，点开是原网页。
同一个问题 CACHE_HOURS 内再问，直接给上次的回答，App 上可以要求重新回答（body 带 fresh）。

chat() 是生成器，逐条产出给 App 的流式消息：
  {"type": "thread", "id"}       对话编号，App 继续对话时带上
  {"type": "status", "text"}     比如“正在阅读 3 篇原文”
  {"type": "delta", "text"}      回复的增量文字
  {"type": "refs", "events"}     随便问时引用到的事件，App 显示成可点的卡片
  {"type": "cached", "at"}       这次给的是之前的回答，at 是那次回答的时间
  {"type": "memory", ...}        这轮对话新记住 / 改写 / 删除的记忆
  {"type": "watch", "id", "text"} 暂时没有答案、之后替他留意的问题
  {"type": "done"} 或 {"type": "error", "text"}
每轮回答后都会尝试从对话里提取记忆，记忆变化后在后台按新记忆重写前排简报。
"""
from __future__ import annotations

import logging
import re
import threading
from concurrent.futures import Future, ThreadPoolExecutor, wait
from datetime import datetime, timedelta, timezone

from . import personal, reader
from .config import Config
from .pipeline.events import group_events
from .sources import build_sources, source_of
from .sources.deepseek import tool
from .util import clean_text, parse_time

log = logging.getLogger(__name__)

HISTORY = 12
SOURCE_CHARS = 3500
MAX_SOURCES = 3
ASK_DAYS = 14
CHAT_WAIT = 10  # 对话里最多等原文这么多秒，没读到的来源先用摘要回答
FAIL_RETRY = timedelta(hours=6)  # 抓取失败的链接隔这么久再重试
CACHE_HOURS = 24
SEARCH_RESULTS = 6
SEARCH_WAIT = 15  # 最多等联网搜索这么多秒，超时就只用已有的材料回答

# 正在抓取的原文，同一进程里的预读和对话共用，避免同一个链接抓两遍；结果由等到它的线程写回缓存
_pool = ThreadPoolExecutor(max_workers=8, thread_name_prefix="fetch")
_lock = threading.Lock()
_inflight: dict[str, Future] = {}

EVENT_SYSTEM = """你是 Catchup，用户的个人 AI 情报助手。用户正在看一件 AI 领域的事，下面给出这件事的简报、来源原文和网页搜索结果（编号 [1]、[2]…）。
回答要求：中文，直接、具体，先给结论；基于来源回答，用到的句子末尾标注 [编号]；原文没写到的用网页搜索结果补充，同样标注编号；
都没有提到的要说明没有找到，可以补充你的背景知识但要说明是背景知识；结合用户的情况给出和他相关的判断。不要寒暄，不要复述问题，篇幅够用就好。

用户是谁：
{focus}

事件简报：{headline}。{summary}

来源：
{sources}"""

ASK_SYSTEM = """你是 Catchup，用户的个人 AI 情报助手。下面是你最近两周替用户整理过的事件（每条以 [E编号] 开头），以及针对这个问题的网页搜索结果（编号 [1]、[2]…）。
回答用户的问题：中文，直接，先给结论；优先用事件库，提到某个事件时在句末标注 [E编号]；事件库里没有或不够的用网页搜索结果补充，句末标注 [编号]；
都没有相关内容就直说没有，并建议他可以追踪什么。不要寒暄，不要复述问题。

用户是谁：
{focus}

事件：
{events}

网页搜索结果：
{web}"""


def chat(config: Config, store, llm, body: dict, now: datetime):
    message = str(body.get("message") or "").strip()[:2000]
    if not message:
        yield {"type": "error", "text": "消息是空的"}
        return
    thread = store.thread(int(body["thread_id"])) if body.get("thread_id") else None
    event_id = thread["event_id"] if thread else (int(body["event_id"]) if body.get("event_id") else None)
    if thread is None and event_id is not None:
        thread = store.latest_thread(event_id)  # 每件事一个连续的对话
    if thread is None:
        thread = store.thread(store.new_thread(event_id, now))
    yield {"type": "thread", "id": thread["id"]}
    if not body.get("fresh") and (prev := _asked(store, event_id, message, now)):
        yield from _reuse(store, thread, event_id, message, prev, now)
        return

    history = store.messages(thread["id"])[-HISTORY:]
    focus = personal.focus(config, store, now)
    headline = None
    if event_id is not None:
        brief = store.briefs([event_id]).get(event_id) or {}
        signals = store.event_signals(event_id)
        headline = brief.get("headline") or (signals[0].title if signals else "")
        search = _pool.submit(_search, f"{headline} {message}")  # 和读原文同时进行
        reading = f"正在阅读 {len(_pick(signals))} 篇原文、" if needs_reading(store, signals, now) else "正在"
        yield {"type": "status", "text": reading + "搜索网页"}
        sources = _sources(config, store, signals, now)
        refs = [(s.title, s.url) for s in _pick(signals)]
        found = _wait(search)
        if found:
            sources += "\n\n网页搜索结果：\n" + _web(found, len(refs) + 1)
        system = EVENT_SYSTEM.format(focus=focus, headline=headline, summary=brief.get("summary", ""), sources=sources)
    else:
        search = _pool.submit(_search, message)
        yield {"type": "status", "text": "正在搜索网页"}
        candidates = _retrieve(config, store, message, now)
        found = _wait(search)
        refs = []
        system = ASK_SYSTEM.format(focus=focus, events="\n".join(
            f"[E{e['id']}] {e['headline']}：{e['summary']}（{e['time'][:10]}）" for e in candidates) or "（暂无）",
            web=_web(found, 1) or "（没有搜到）")
    refs += [(r["title"], r["url"]) for r in found]
    store.add_message(thread["id"], "user", message, now)
    if event_id is not None:
        store.add_action(event_id, "ask", None, now)

    messages = [{"role": "system", "content": system}]
    messages += [{"role": m["role"], "content": m["content"]} for m in history]
    messages.append({"role": "user", "content": message})
    reply = []
    try:
        for text in llm.stream(messages):
            reply.append(text)
            yield {"type": "delta", "text": text}
    except Exception as e:
        log.warning("chat failed: %r", e)
        yield {"type": "error", "text": "回答中断了，请重试"}
        if not reply:
            return
    answer = "".join(reply)
    footer = _footer(store, answer, refs)
    if footer:
        yield {"type": "delta", "text": footer}
        answer += footer
    events = []
    if event_id is None:
        cited = [int(i) for i in dict.fromkeys(re.findall(r"\[E(\d+)\]", answer))]
        briefs = store.briefs(cited)
        events = [{"id": i, "headline": briefs[i]["headline"]} for i in cited if i in briefs]
        if events:
            yield {"type": "refs", "events": events}
    store.add_message(thread["id"], "assistant", answer, now, {"refs": events})

    changes, pending = personal.extract_from_chat(store, llm, message, answer, headline, now, thread["id"], event_id)
    if any(changes.values()):
        yield {"type": "memory", **changes}
        personal.rebrief_async(config)
    if pending:  # 暂时没有答案的问题，之后每次采集都替他看看有没有答案，见 pipeline/answers.py
        qid = store.add_question(thread["id"], event_id, pending["text"], pending["keywords"], now)
        yield {"type": "watch", "id": qid, "text": pending["text"]}
    yield {"type": "done"}


def _key(text: str) -> str:
    """判断是不是同一个问题：去掉空白和标点再比。"""
    return re.sub(r"[\s，,。.？?！!、]+", "", text).lower()


def _asked(store, event_id: int | None, message: str, now: datetime) -> dict | None:
    """CACHE_HOURS 内在同一件事下（或随便问里）问过的同一个问题和当时的回答。"""
    since = now - timedelta(hours=CACHE_HOURS)
    for x in store.asked_since(event_id, since):
        generated = parse_time(x["meta"].get("generated") or x["created"])
        if _key(x["question"]) == _key(message) and generated and generated >= since:
            return x
    return None


def _reuse(store, thread: dict, event_id: int | None, message: str, prev: dict, now: datetime):
    """同一个问题最近问过：直接给上次的回答，不再调用模型。"""
    generated = prev["meta"].get("generated") or prev["created"]
    yield {"type": "cached", "at": generated}
    yield {"type": "delta", "text": prev["content"]}
    if prev["meta"].get("refs"):
        yield {"type": "refs", "events": prev["meta"]["refs"]}
    if prev["thread_id"] != thread["id"]:  # 在新对话里问的：把这一问一答记进来，接着追问时有上下文
        store.add_message(thread["id"], "user", message, now)
        store.add_message(thread["id"], "assistant", prev["content"], now, {**prev["meta"], "generated": generated})
    if event_id is not None:
        store.add_action(event_id, "ask", None, now)
    yield {"type": "done"}


def _search(query: str) -> list[dict]:
    """联网搜索，返回 [{title, url, site, snippet}]；失败时返回空列表，回答照样基于已有的材料。"""
    try:
        res = tool("search", {"query": query[:200], "count": SEARCH_RESULTS + 2})
    except Exception as e:
        log.info("chat search failed: %r", e)
        return []
    return [{"title": clean_text(r.get("title"), 120) or r["url"], "url": r["url"].strip(), "site": r.get("site_name") or "",
             "snippet": clean_text(r.get("snippet"), 400)}
            for r in res.get("results", []) if r.get("url") and r.get("rerank_score", 0) >= 1.0][:SEARCH_RESULTS]


def _wait(search: Future) -> list[dict]:
    try:
        return search.result(timeout=SEARCH_WAIT)
    except Exception:  # 超时
        return []


def _web(results: list[dict], start: int) -> str:
    """网页搜索结果，编号接在原文后面。"""
    return "\n\n".join(f"[{i}] {r['title']}（{r['site']}，{r['url']}）\n{r['snippet']}" for i, r in enumerate(results, start))


def _footer(store, answer: str, refs: list[tuple[str, str]]) -> str:
    """回答里引用到的来源列在最后，标题是中文（英文标题用译文，见 pipeline/translate.py），点开是原网页。"""
    cited = sorted({int(n) for group in re.findall(r"\[(\d+(?:[,，]\s*\d+)*)\]", answer) for n in re.findall(r"\d+", group)})
    cited = [n for n in cited if 1 <= n <= len(refs)]
    if not cited:
        return ""
    zh = store.translations([refs[n - 1][0] for n in cited])
    lines = []
    for n in cited:
        title, url = refs[n - 1]
        title = zh.get(title, title).replace("[", "［").replace("]", "］")
        title = title[:60] + "…" if len(title) > 60 else title
        lines.append(f"{n}. [{title}]({url.replace(')', '%29')})")
    return "\n\n**来源**\n" + "\n".join(lines)


def _pick(signals) -> list:
    """要读原文的来源：官方源优先，每个网站只取一篇，最多 MAX_SOURCES 篇。"""
    ordered = sorted(signals, key=lambda s: (s.tier != "official", s.tier != "primary"))
    picked, seen_hosts = [], set()
    for s in ordered:
        host = re.sub(r"^https?://([^/]+).*", r"\1", s.url)
        if host not in seen_hosts:
            picked.append(s)
            seen_hosts.add(host)
        if len(picked) >= MAX_SOURCES:
            break
    return picked


def _fetch(url: str) -> str:
    try:
        return reader.read(url)[1][:SOURCE_CHARS * 2]
    except Exception as e:
        log.info("fetch %s failed: %r", url, e)
        return ""


def _cached(store, url: str, now: datetime) -> str | None:
    """缓存的原文；最近抓取失败过的返回空串（先不重抓），没缓存或失败已久返回 None。"""
    page = store.page(url)
    if page is None:
        return None
    content, fetched = page
    if content or now - parse_time(fetched) < FAIL_RETRY:
        return content or ""
    return None


def _start(url: str) -> Future:
    with _lock:
        if url not in _inflight:
            _inflight[url] = _pool.submit(_fetch, url)
        return _inflight[url]


def _read(store, signals, now: datetime, timeout: float | None) -> dict[str, str | None]:
    """所选来源的原文，见 read_pages。"""
    return read_pages(store, [s.url for s in _pick(signals)], now, timeout)


def read_pages(store, urls: list[str], now: datetime, timeout: float | None) -> dict[str, str | None]:
    """取一批链接的原文：先查缓存，缺的并行抓取（已在抓的直接等它），最多等 timeout 秒；timeout 为 0 时只查缓存。
    抓完的写回缓存（数据库只在当前线程写）；读不到的是空串，没缓存也没等到的是 None，留给还在等它的线程写回。"""
    texts = {u: _cached(store, u, now) for u in urls}
    if timeout == 0:
        return texts
    futures = {u: _start(u) for u, t in texts.items() if t is None}
    if futures:
        wait(futures.values(), timeout=timeout)
    for url, future in futures.items():
        if future.done():
            texts[url] = future.result()
            store.save_page(url, texts[url], now)
            with _lock:
                if _inflight.get(url) is future:
                    del _inflight[url]
    return texts


def prefetch(config: Config, event_id: int) -> None:
    """打开事件详情时在后台先把原文读好，用户提问时就不用再等。"""
    def run():
        from .store import Store
        store = Store(config.db_path)
        try:
            _read(store, store.event_signals(event_id), datetime.now(timezone.utc), timeout=None)
        except Exception as e:
            log.info("prefetch %d failed: %r", event_id, e)
        finally:
            store.con.close()

    threading.Thread(target=run, name=f"prefetch-{event_id}", daemon=True).start()


def needs_reading(store, signals, now: datetime) -> bool:
    return any(_cached(store, s.url, now) is None for s in _pick(signals))


def _sources(config: Config, store, signals, now: datetime) -> str:
    """来源原文，每个来源截取前 SOURCE_CHARS 字；抓不到就用摘要。原文缓存在 pages 表。"""
    labels = build_sources(config.sources)
    picked = _pick(signals)
    texts = _read(store, signals, now, timeout=CHAT_WAIT)
    parts = []
    for i, s in enumerate(picked, 1):
        text = texts.get(s.url) or ""
        label = source_of(s, labels)[0]
        body = text[:SOURCE_CHARS] if text.strip() else s.summary
        parts.append(f"[{i}] {s.title}（{label}，{s.url}）\n{body}")
    rest = [s for s in signals if s not in picked][:10]
    if rest:
        parts.append("其他报道标题：\n" + "\n".join(f"- {s.title}" for s in rest))
    return "\n\n".join(parts)


def _retrieve(config: Config, store, question: str, now: datetime, limit: int = 12) -> list[dict]:
    """在最近两周有简报的事件里按词重合找相关事件；一个都不相关时退回最重要的几件。"""
    signals = store.recent(now - timedelta(days=ASK_DAYS), now + timedelta(hours=1))
    events = personal.build_ranker(config, store, now).rank(group_events(signals), now)
    briefs = store.briefs(e.id for e in events)
    terms = _terms(question)
    scored = []
    for e in events:
        b = briefs.get(e.id)
        if not b:
            continue
        text = " ".join([b["headline"], b["summary"]] + [s.title for s in e.signals[:6]]).lower()
        hits = sum(1 for t in terms if t in text)
        scored.append((hits, b["importance"], e, b))
    relevant = [x for x in scored if x[0] > 0]
    pool = sorted(relevant or scored, key=lambda x: (-x[0], -x[1]))[:limit]
    return [{"id": e.id, "headline": b["headline"], "summary": b["summary"],
             "time": max(s.published for s in e.signals).isoformat()} for _, _, e, b in pool]


def _terms(question: str) -> set[str]:
    q = question.lower()
    words = {w for w in re.findall(r"[a-z0-9][a-z0-9.\-]+", q) if len(w) >= 2}
    cjk = re.sub(r"[^一-鿿]", " ", q)
    stop = {"最近", "什么", "有没", "没有", "哪些", "一下", "帮我", "进展", "怎么", "这个", "那个", "一个"}
    bigrams = {seg[i:i + 2] for seg in cjk.split() for i in range(len(seg) - 1)} - stop
    return words | bigrams
