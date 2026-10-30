"""SQLite 存储。表结构用 PRAGMA user_version 做顺序迁移：只在 MIGRATIONS 末尾追加，不要改已有条目。"""
from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Iterable

from .models import Signal
from .util import parse_time, to_iso

MIGRATIONS = [
    """
    create table signals (
        url text primary key, source text not null, channel text, title text, summary text, author text,
        published text not null, fetched text not null, tier text, kind text, heat real, extra text);
    create index signals_published on signals (published);
    create table runs (
        id integer primary key, started text not null, source text not null,
        fetched integer, new integer, error text);
    """,
    # 持久化事件：信号一旦归入某个事件就不再改动（合并除外），App、推送和反馈都引用 events.id
    """
    create table events (id integer primary key, key text not null, created text not null);
    alter table signals add column event_id integer;
    create index signals_event on signals (event_id);
    create table briefs (
        event_id integer primary key, created text not null, model text, signal_count integer,
        headline text, summary text, why text, importance integer);
    create table feedback (event_id integer primary key, value text not null, at text not null);
    create table pushes (event_id integer primary key, at text not null);
    create table devices (
        id integer primary key, name text, token_hash text not null unique,
        approved integer not null default 0, created text not null, last_seen text);
    """,
    # 个人记忆、对话、追踪、补课简报、回顾。memories 的 kind 见 personal.py 的 KINDS
    """
    create table memories (
        id integer primary key, kind text not null, text text not null, keywords text not null default '[]',
        origin text, thread_id integer, event_id integer, created text not null, updated text not null,
        expires text, deleted integer not null default 0);
    create table threads (id integer primary key, event_id integer, created text not null, updated text not null);
    create table messages (
        id integer primary key, thread_id integer not null, role text not null, content text not null,
        created text not null, meta text);
    create index messages_thread on messages (thread_id);
    create table pages (url text primary key, fetched text not null, content text);
    create table follows (
        event_id integer primary key, created text not null, active integer not null default 1,
        seen_signals integer not null default 0, searched text);
    create table event_updates (id integer primary key, event_id integer not null, at text not null, text text not null);
    create table settings (key text primary key, value text);
    create table digests (key text primary key, created text not null, text text not null);
    create table ratings (event_id integer primary key, would_miss integer not null, at text not null);
    create table misses (id integer primary key, text text not null, at text not null);
    alter table briefs add column memory_rev integer not null default 0;
    alter table briefs add column questions text;
    """,
    # 行为日志：反馈、撤回、追踪、提问、看原文、评分，按时间顺序供反馈学习归纳；feedback 等表只存当前状态
    """
    create table actions (id integer primary key, event_id integer not null, kind text not null, value text,
        at text not null);
    create index actions_at on actions (at);
    alter table feedback add column reason text;
    insert into actions (event_id, kind, at) select event_id, value, at from feedback order by at;
    insert into actions (event_id, kind, value, at) select event_id, 'rating', would_miss, at from ratings order by at;
    insert into actions (event_id, kind, at) select event_id, 'follow', created from follows where active = 1;
    insert into actions (event_id, kind, at) select t.event_id, 'ask', m.created from messages m
        join threads t on t.id = m.thread_id where m.role = 'user' and t.event_id is not null order by m.id;
    """,
    # 替你留意的问题：对话里暂时没有答案的问题，status 为 open / answered / dropped / expired，见 pipeline/answers.py
    """
    create table questions (
        id integer primary key, thread_id integer not null, event_id integer, text text not null,
        keywords text not null default '[]', created text not null, checked text, searched text,
        status text not null default 'open', answer text, answer_url text, answered text,
        notified integer not null default 0);
    """,
    # 简报要点：读原文后写的 3～5 条要点（JSON 数组）；NULL 表示写简报时还没读到原文，读到后会重写
    "alter table briefs add column points text;",
    # 英文原文的中文译文：来源标题、没写简报的事件摘要，见 pipeline/translate.py
    "create table translations (text text primary key, zh text not null);",
    # 日报、周报、月报：kind 为 daily / weekly / monthly，period 为 2026-10-26 / 2026-10-19（那周的周一）/ 2026-10；
    # content 是 JSON（要点、分领域、趋势、持续发展的事），见 pipeline/reports.py；notified 为 0 表示还要推送
    """
    create table reports (
        kind text not null, period text not null, created text not null, content text not null,
        notified integer not null default 1, primary key (kind, period));
    """,
    # 简报的主话题：配置里的兴趣名或“其他”，App 按它给卡片上色；简报重写后为 NULL，下一轮重新判断（brief.assign_topics）
    "alter table briefs add column topic text;",
    # 榜单来源（Source.board）的名次：每个榜（channel）最近一次抓到的才是当前榜单；first_seen 是这一轮上榜的开始
    """
    create table board_items (
        source text not null, channel text not null, url text not null, rank integer not null, title text,
        summary text, extra text, first_seen text not null, last_seen text not null,
        primary key (source, channel, url));
    """,
    # AI 解读：简报之外模型对这件事的看法（新在哪、影响、局限）；NULL 表示写简报时还没有这一项，会重写简报补上
    "alter table briefs add column insight text;",
]
BOARD_GAP = timedelta(days=2)  # 下榜超过这么久再上榜，算新的一轮

COLUMNS = "url, source, channel, title, summary, author, published, fetched, tier, kind, heat, extra"


def _signal(r) -> Signal:
    """把 select {COLUMNS}, event_id 的一行转成 Signal。"""
    return Signal(url=r[0], source=r[1], channel=r[2] or "", title=r[3] or "", summary=r[4] or "",
                  author=r[5] or "", published=parse_time(r[6]), tier=r[8] or "signal", kind=r[9] or "资讯",
                  heat=r[10] or 0.0, extra=json.loads(r[11] or "{}"), event_id=r[12])


class Store:
    def __init__(self, path: Path | str):
        path = Path(path)
        if str(path) != ":memory:":
            path.parent.mkdir(parents=True, exist_ok=True)
        self.con = sqlite3.connect(path, timeout=30)
        self._migrate()

    def _migrate(self) -> None:
        """在一个写事务里执行待做的迁移：先拿写锁再读版本号，多个进程或线程同时打开数据库时会排队，
        失败则整体回滚。不能用 executescript，它会先提交、逐句自动提交，并发时会留下半迁移状态。"""
        if self.con.execute("pragma user_version").fetchone()[0] >= len(MIGRATIONS):
            return
        self.con.isolation_level = None
        try:
            self.con.execute("begin immediate")
            version = self.con.execute("pragma user_version").fetchone()[0]
            for i, sql in enumerate(MIGRATIONS[version:], start=version + 1):
                for statement in (part.strip() for part in sql.split(";")):
                    if statement:
                        self.con.execute(statement)
                self.con.execute(f"pragma user_version = {i}")
            self.con.execute("commit")
        except Exception:
            self.con.execute("rollback")
            raise
        finally:
            self.con.isolation_level = ""

    def add(self, signals: Iterable[Signal], now: datetime | None = None) -> int:
        """写入信号并返回新增条数。URL 是主键，insert or ignore 同时完成写入和判新。"""
        now = now or datetime.now(timezone.utc)
        rows = ((s.url, s.source, s.channel, s.title, s.summary, s.author, to_iso(s.published or now),
                 to_iso(now), s.tier, s.kind, s.heat, json.dumps(s.extra, ensure_ascii=False))
                for s in signals if s.url)
        # executemany 的 rowcount 是实际插入行数之和，被 ignore 的重复 URL 不计入
        new = self.con.executemany(
            f"insert or ignore into signals ({COLUMNS}) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows).rowcount
        self.con.commit()
        return new

    def fetched_since(self, since: datetime) -> list[Signal]:
        """since 之后才采集到的信号（不管发布时间），用来判断“上次之后有什么新报道”。"""
        rows = self.con.execute(f"select {COLUMNS}, event_id from signals where fetched > ?", (to_iso(since),)).fetchall()
        return [_signal(r) for r in rows]

    def signal_event(self, url: str) -> int | None:
        r = self.con.execute("select event_id from signals where url = ?", (url,)).fetchone()
        return r[0] if r else None

    def recent(self, since: datetime, until: datetime) -> list[Signal]:
        rows = self.con.execute(f"select {COLUMNS}, event_id from signals where published >= ? and published <= ?",
                                (to_iso(since), to_iso(until))).fetchall()
        return [_signal(r) for r in rows]

    # ---------- 事件 ----------

    def new_event(self, key: str, now: datetime) -> int:
        return self.con.execute("insert into events (key, created) values (?, ?)", (key, to_iso(now))).lastrowid

    def assign(self, event_id: int, urls: Iterable[str]) -> None:
        self.con.executemany("update signals set event_id = ? where url = ?", [(event_id, u) for u in urls])

    def merge_events(self, target: int, others: Iterable[int]) -> None:
        """把 others 的信号并入 target；简报失效重写，反馈和推送记录保留在 target 上。"""
        for o in others:
            self.con.execute("update signals set event_id = ? where event_id = ?", (target, o))
            self.con.execute("delete from briefs where event_id in (?, ?)", (target, o))
            self.con.execute("update or ignore pushes set event_id = ? where event_id = ?", (target, o))
            self.con.execute("update or ignore feedback set event_id = ? where event_id = ?", (target, o))

    def event_created(self, ids: Iterable[int]) -> dict[int, datetime]:
        ids = list(ids)
        rows = self.con.execute(f"select id, created from events where id in ({','.join('?' * len(ids))})", ids)
        return {r[0]: parse_time(r[1]) for r in rows}

    def event_published(self, ids: Iterable[int]) -> dict[int, datetime]:
        """事件见报的时间：最早一条报道的发布时间。"""
        ids = list(ids)
        rows = self.con.execute(f"select event_id, min(published) from signals where event_id in ({','.join('?' * len(ids))}) "
                                "group by event_id", ids)
        return {r[0]: parse_time(r[1]) for r in rows}

    def first_fetched(self) -> datetime | None:
        """开始采集的时间。"""
        return parse_time(self.con.execute("select min(fetched) from signals").fetchone()[0])

    def event_signals(self, event_id: int) -> list[Signal]:
        rows = self.con.execute(f"select {COLUMNS}, event_id from signals where event_id = ? order by published",
                                (event_id,)).fetchall()
        return [_signal(r) for r in rows]

    def commit(self) -> None:
        self.con.commit()

    # ---------- 简报、反馈、推送 ----------

    def briefs(self, ids: Iterable[int]) -> dict[int, dict]:
        ids = list(ids)
        rows = self.con.execute("select event_id, created, model, signal_count, headline, summary, why, importance, "
                                f"memory_rev, questions, points, topic, insight from briefs where event_id in ({','.join('?' * len(ids))})",
                                ids)
        keys = ("created", "model", "signal_count", "headline", "summary", "why", "importance", "memory_rev")
        out = {}
        for r in rows:
            out[r[0]] = dict(zip(keys, r[1:9]))
            out[r[0]]["questions"] = json.loads(r[9] or "[]")
            out[r[0]]["points"] = None if r[10] is None else json.loads(r[10])
            out[r[0]]["topic"] = r[11]
            out[r[0]]["insight"] = r[12]
        return out

    def save_topics(self, topics: dict[int, str]) -> None:
        self.con.executemany("update briefs set topic = ? where event_id = ?", [(t, i) for i, t in topics.items()])
        self.con.commit()

    def save_brief(self, event_id: int, now: datetime, model: str, signal_count: int, brief: dict,
                   memory_rev: int = 0) -> None:
        self.con.execute("insert or replace into briefs (event_id, created, model, signal_count, headline, summary, "
                         "why, importance, memory_rev, questions, points, insight) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                         (event_id, to_iso(now), model, signal_count, brief["headline"], brief["summary"],
                          brief["why"], brief["importance"], memory_rev,
                          json.dumps(brief.get("questions", []), ensure_ascii=False),
                          None if brief.get("points") is None else json.dumps(brief["points"], ensure_ascii=False),
                          brief.get("insight")))
        self.con.commit()

    def feedback(self) -> dict[int, str]:
        return dict(self.con.execute("select event_id, value from feedback"))

    def set_feedback(self, event_id: int, value: str | None, now: datetime, reason: str | None = None) -> None:
        if value is None:
            self.con.execute("delete from feedback where event_id = ?", (event_id,))
        else:
            self.con.execute("insert or replace into feedback values (?, ?, ?, ?)",
                             (event_id, value, to_iso(now), reason))
        self.con.commit()

    def pushed(self) -> dict[int, str]:
        """推送过的事件 → 推送时间。"""
        return dict(self.con.execute("select event_id, at from pushes"))

    def pushes_since(self, since: datetime) -> int:
        return self.con.execute("select count(*) from pushes where at >= ?", (to_iso(since),)).fetchone()[0]

    def record_push(self, event_id: int, now: datetime) -> None:
        self.con.execute("insert or ignore into pushes values (?, ?)", (event_id, to_iso(now)))
        self.con.commit()

    def known_urls(self, urls: Iterable[str]) -> set[str]:
        urls = list(urls)
        rows = self.con.execute(f"select url from signals where url in ({','.join('?' * len(urls))})", urls)
        return {r[0] for r in rows}

    def feedback_since(self, since: str) -> list[tuple[int, str, str, str | None]]:
        return self.con.execute("select event_id, value, at, reason from feedback where at > ? order by at",
                                (since,)).fetchall()

    def add_action(self, event_id: int, kind: str, value: str | None, now: datetime) -> None:
        self.con.execute("insert into actions (event_id, kind, value, at) values (?, ?, ?, ?)",
                         (event_id, kind, value, to_iso(now)))
        self.con.commit()

    def actions(self, since: datetime) -> list[tuple[int, int, str, str | None, str]]:
        """(id, event_id, kind, value, at)，按发生顺序。"""
        return self.con.execute("select id, event_id, kind, value, at from actions where at >= ? order by id",
                                (to_iso(since),)).fetchall()

    def set_rating(self, event_id: int, would_miss: bool, now: datetime) -> None:
        self.con.execute("insert or replace into ratings values (?, ?, ?)", (event_id, int(would_miss), to_iso(now)))
        self.con.commit()

    def rating(self, event_id: int) -> bool | None:
        r = self.con.execute("select would_miss from ratings where event_id = ?", (event_id,)).fetchone()
        return None if r is None else bool(r[0])

    # ---------- 设置 ----------

    def setting(self, key: str, default: str | None = None) -> str | None:
        r = self.con.execute("select value from settings where key = ?", (key,)).fetchone()
        return r[0] if r else default

    def set_setting(self, key: str, value: str) -> None:
        self.con.execute("insert or replace into settings values (?, ?)", (key, value))
        self.con.commit()

    # ---------- 记忆 ----------

    def memories(self, include_deleted: bool = False) -> list[dict]:
        rows = self.con.execute("select id, kind, text, keywords, origin, thread_id, event_id, created, updated, "
                                "expires, deleted from memories" + ("" if include_deleted else " where deleted = 0")
                                + " order by updated desc").fetchall()
        keys = ("id", "kind", "text", "keywords", "origin", "thread_id", "event_id", "created", "updated", "expires",
                "deleted")
        out = [dict(zip(keys, r)) for r in rows]
        for m in out:
            m["keywords"] = json.loads(m["keywords"] or "[]")
            m["deleted"] = bool(m["deleted"])
        return out

    def add_memory(self, kind: str, text: str, keywords: list[str], origin: str, now: datetime,
                   expires: datetime | None = None, thread_id: int | None = None, event_id: int | None = None) -> int:
        cur = self.con.execute(
            "insert into memories (kind, text, keywords, origin, thread_id, event_id, created, updated, expires) "
            "values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (kind, text, json.dumps(keywords, ensure_ascii=False), origin, thread_id, event_id, to_iso(now),
             to_iso(now), to_iso(expires)))
        self.con.commit()
        return cur.lastrowid

    def update_memory(self, memory_id: int, now: datetime, **fields) -> bool:
        if "keywords" in fields:
            fields["keywords"] = json.dumps(fields["keywords"], ensure_ascii=False)
        if "expires" in fields:
            fields["expires"] = to_iso(fields["expires"])
        if "deleted" in fields:
            fields["deleted"] = int(fields["deleted"])
        sets = ", ".join(f"{k} = ?" for k in fields)
        n = self.con.execute(f"update memories set {sets}, updated = ? where id = ?",
                             (*fields.values(), to_iso(now), memory_id)).rowcount
        self.con.commit()
        return n > 0

    # ---------- 对话 ----------

    def new_thread(self, event_id: int | None, now: datetime) -> int:
        cur = self.con.execute("insert into threads (event_id, created, updated) values (?, ?, ?)",
                               (event_id, to_iso(now), to_iso(now)))
        self.con.commit()
        return cur.lastrowid

    def thread(self, thread_id: int) -> dict | None:
        r = self.con.execute("select id, event_id, created, updated from threads where id = ?", (thread_id,)).fetchone()
        return dict(zip(("id", "event_id", "created", "updated"), r)) if r else None

    def latest_thread(self, event_id: int | None) -> dict | None:
        where = "event_id = ?" if event_id is not None else "event_id is null"
        args = (event_id,) if event_id is not None else ()
        r = self.con.execute(f"select id from threads where {where} order by updated desc limit 1", args).fetchone()
        return self.thread(r[0]) if r else None

    def add_message(self, thread_id: int, role: str, content: str, now: datetime, meta: dict | None = None) -> int:
        cur = self.con.execute("insert into messages (thread_id, role, content, created, meta) values (?, ?, ?, ?, ?)",
                               (thread_id, role, content, to_iso(now), json.dumps(meta or {}, ensure_ascii=False)))
        self.con.execute("update threads set updated = ? where id = ?", (to_iso(now), thread_id))
        self.con.commit()
        return cur.lastrowid

    def messages(self, thread_id: int) -> list[dict]:
        rows = self.con.execute("select id, role, content, created, meta from messages where thread_id = ? order by id",
                                (thread_id,)).fetchall()
        return [{"id": r[0], "role": r[1], "content": r[2], "created": r[3], "meta": json.loads(r[4] or "{}")}
                for r in rows]

    def asked_since(self, event_id: int | None, since: datetime) -> list[dict]:
        """某件事下（event_id 为空时是随便问）最近问过的问题和紧接着的回答，新的在前；没拿到回答的不算。"""
        rows = self.con.execute(
            "select q.thread_id, q.content, a.content, a.created, a.meta from messages q "
            "join threads t on t.id = q.thread_id "
            "join messages a on a.id = (select min(id) from messages where thread_id = q.thread_id and id > q.id) "
            "where q.role = 'user' and a.role = 'assistant' and a.content != '' and t.event_id is ? and q.created >= ? "
            "order by q.id desc", (event_id, to_iso(since))).fetchall()
        return [{"thread_id": r[0], "question": r[1], "content": r[2], "created": r[3], "meta": json.loads(r[4] or "{}")}
                for r in rows]

    def thread_counts(self, event_ids: Iterable[int]) -> dict[int, int]:
        ids = list(event_ids)
        rows = self.con.execute("select t.event_id, count(m.id) from threads t join messages m on m.thread_id = t.id "
                                f"where t.event_id in ({','.join('?' * len(ids))}) group by t.event_id", ids)
        return dict(rows)

    def page(self, url: str) -> tuple[str, str] | None:
        """缓存的原文和抓取时间；抓取失败时 content 是空串。"""
        r = self.con.execute("select content, fetched from pages where url = ?", (url,)).fetchone()
        return (r[0] or "", r[1]) if r else None

    def save_page(self, url: str, content: str, now: datetime) -> None:
        self.con.execute("insert or replace into pages values (?, ?, ?)", (url, to_iso(now), content))
        self.con.commit()

    # ---------- 译文 ----------

    def translations(self, texts: Iterable[str]) -> dict[str, str]:
        texts = list(texts)
        return dict(self.con.execute(f"select text, zh from translations where text in ({','.join('?' * len(texts))})",
                                     texts).fetchall())

    def save_translations(self, pairs: dict[str, str]) -> None:
        self.con.executemany("insert or replace into translations values (?, ?)", pairs.items())
        self.con.commit()

    # ---------- 报告 ----------

    def report(self, kind: str, period: str) -> dict | None:
        r = self.con.execute("select period, created, content, notified from reports where kind = ? and period = ?",
                             (kind, period)).fetchone()
        return {"kind": kind, "period": r[0], "created": r[1], "content": json.loads(r[2]), "notified": bool(r[3])} if r else None

    def report_periods(self, kind: str) -> list[str]:
        """已有的报告，新的在前。"""
        return [r[0] for r in self.con.execute("select period from reports where kind = ? order by period desc", (kind,))]

    def save_report(self, kind: str, period: str, content: dict, now: datetime, notified: bool) -> None:
        self.con.execute("insert or replace into reports values (?, ?, ?, ?, ?)",
                         (kind, period, to_iso(now), json.dumps(content, ensure_ascii=False), int(notified)))
        self.con.commit()

    def unnotified_reports(self) -> list[dict]:
        rows = self.con.execute("select kind, period from reports where notified = 0 order by period").fetchall()
        return [self.report(kind, period) for kind, period in rows]

    def set_report_notified(self, kind: str, period: str) -> None:
        self.con.execute("update reports set notified = 1 where kind = ? and period = ?", (kind, period))
        self.con.commit()

    # ---------- 追踪 ----------

    def follows(self, active_only: bool = True) -> list[dict]:
        rows = self.con.execute("select event_id, created, active, seen_signals, searched from follows"
                                + (" where active = 1" if active_only else "")).fetchall()
        return [dict(zip(("event_id", "created", "active", "seen_signals", "searched"), r)) for r in rows]

    def set_follow(self, event_id: int, on: bool, seen_signals: int, now: datetime) -> None:
        if on:
            self.con.execute("insert into follows (event_id, created, active, seen_signals) values (?, ?, 1, ?) "
                             "on conflict(event_id) do update set active = 1", (event_id, to_iso(now), seen_signals))
        else:
            self.con.execute("update follows set active = 0 where event_id = ?", (event_id,))
        self.con.commit()

    def update_follow(self, event_id: int, **fields) -> None:
        sets = ", ".join(f"{k} = ?" for k in fields)
        self.con.execute(f"update follows set {sets} where event_id = ?", (*fields.values(), event_id))
        self.con.commit()

    def add_event_update(self, event_id: int, text: str, now: datetime) -> None:
        self.con.execute("insert into event_updates (event_id, at, text) values (?, ?, ?)", (event_id, to_iso(now), text))
        self.con.commit()

    def event_updates(self, event_ids: Iterable[int]) -> dict[int, list[dict]]:
        ids = list(event_ids)
        rows = self.con.execute("select event_id, at, text from event_updates "
                                f"where event_id in ({','.join('?' * len(ids))}) order by at desc", ids)
        out: dict[int, list[dict]] = {}
        for r in rows:
            out.setdefault(r[0], []).append({"at": r[1], "text": r[2]})
        return out

    # ---------- 漏报 ----------

    def add_miss(self, text: str, now: datetime) -> None:
        self.con.execute("insert into misses (text, at) values (?, ?)", (text, to_iso(now)))
        self.con.commit()

    # ---------- 替你留意的问题 ----------

    def add_question(self, thread_id: int, event_id: int | None, text: str, keywords: list[str], now: datetime) -> int:
        cur = self.con.execute("insert into questions (thread_id, event_id, text, keywords, created) values (?, ?, ?, ?, ?)",
                               (thread_id, event_id, text, json.dumps(keywords, ensure_ascii=False), to_iso(now)))
        self.con.commit()
        return cur.lastrowid

    def questions(self, status: str | None = None) -> list[dict]:
        keys = ("id", "thread_id", "event_id", "text", "keywords", "created", "checked", "searched", "status", "answer",
                "answer_url", "answered", "notified")
        rows = self.con.execute(f"select {', '.join(keys)} from questions" + (" where status = ?" if status else "")
                                + " order by id desc", (status,) if status else ()).fetchall()
        out = [dict(zip(keys, r)) for r in rows]
        for q in out:
            q["keywords"] = json.loads(q["keywords"] or "[]")
        return out

    def update_question(self, question_id: int, **fields) -> bool:
        if "keywords" in fields:
            fields["keywords"] = json.dumps(fields["keywords"], ensure_ascii=False)
        sets = ", ".join(f"{k} = ?" for k in fields)
        cur = self.con.execute(f"update questions set {sets} where id = ?", (*fields.values(), question_id))
        self.con.commit()
        return cur.rowcount > 0

    # ---------- 设备 ----------

    def add_device(self, name: str, token_hash: str, approved: bool, now: datetime) -> int:
        cur = self.con.execute("insert into devices (name, token_hash, approved, created) values (?, ?, ?, ?)",
                               (name, token_hash, int(approved), to_iso(now)))
        self.con.commit()
        return cur.lastrowid

    def device_by_token(self, token_hash: str) -> dict | None:
        r = self.con.execute("select id, name, approved from devices where token_hash = ?", (token_hash,)).fetchone()
        return {"id": r[0], "name": r[1], "approved": bool(r[2])} if r else None

    def devices(self) -> list[dict]:
        rows = self.con.execute("select id, name, approved, created, last_seen from devices order by id")
        return [dict(zip(("id", "name", "approved", "created", "last_seen"), r)) for r in rows]

    def set_device_approved(self, device_id: int, approved: bool) -> bool:
        n = self.con.execute("update devices set approved = ? where id = ?", (int(approved), device_id)).rowcount
        self.con.commit()
        return n > 0

    def touch_device(self, device_id: int, now: datetime) -> None:
        self.con.execute("update devices set last_seen = ? where id = ?", (to_iso(now), device_id))
        self.con.commit()

    # ---------- 榜单 ----------

    def update_board(self, source: str, signals: list[Signal], now: datetime) -> None:
        """榜单来源这次抓到的名次：同一个榜（channel）里按返回顺序排名。信号表按 URL 只记第一次，
        连续几天上榜、名次变化记在这里。"""
        ranks: dict[str, int] = {}
        rows = []
        for s in signals:
            ranks[s.channel] = ranks.get(s.channel, 0) + 1
            rows.append((source, s.channel, s.url, ranks[s.channel], s.title, s.summary,
                         json.dumps(s.extra, ensure_ascii=False), to_iso(now), to_iso(now - BOARD_GAP)))
        self.con.executemany(
            "insert into board_items (source, channel, url, rank, title, summary, extra, first_seen, last_seen) "
            "values (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?8) on conflict (source, channel, url) do update set "
            "rank = excluded.rank, title = excluded.title, summary = excluded.summary, extra = excluded.extra, "
            "first_seen = case when last_seen < ?9 then excluded.first_seen else first_seen end, "
            "last_seen = excluded.last_seen", rows)
        self.con.commit()

    def board(self, source: str) -> list[dict]:
        """榜单来源每个榜最近一次抓到的名次，按榜和名次排；first_seen 用来看新上榜、连续上榜了多久。"""
        rows = self.con.execute(
            "select channel, rank, url, title, summary, extra, first_seen from board_items b where source = ? "
            "and last_seen = (select max(last_seen) from board_items where source = b.source and channel = b.channel) "
            "order by channel, rank", (source,)).fetchall()
        return [{"channel": r[0], "rank": r[1], "url": r[2], "title": r[3], "summary": r[4],
                 "extra": json.loads(r[5] or "{}"), "first_seen": r[6]} for r in rows]

    def record_run(self, source: str, started: datetime, fetched: int, new: int, error: str | None = None) -> None:
        self.con.execute("insert into runs (started, source, fetched, new, error) values (?, ?, ?, ?, ?)",
                         (to_iso(started), source, fetched, new, error))
        self.con.commit()

    def last_runs(self) -> dict[str, dict]:
        rows = self.con.execute(
            "select source, started, fetched, new, error from runs where id in "
            "(select max(id) from runs group by source)").fetchall()
        return {r[0]: {"started": r[1], "fetched": r[2], "new": r[3], "error": r[4]} for r in rows}

    def failing_sources(self, since: datetime) -> dict[str, tuple[str | None, int]]:
        """since 以来最后一次采到内容（没报错且不是 0 条）的时间和之后连续失败的次数，只列最近一次失败了的来源。
        since 以来一次都没采到内容的，时间为 None。"""
        rows = self.con.execute(
            "select r.source, s.ok, count(*) from runs r join (select source, max(case when fetched > 0 and error "
            "is null then started end) as ok from runs where started >= ? group by source) s on r.source = s.source "
            "where r.started >= ? and r.started > coalesce(s.ok, '') group by r.source",
            (to_iso(since), to_iso(since))).fetchall()
        return {r[0]: (r[1], r[2]) for r in rows}
