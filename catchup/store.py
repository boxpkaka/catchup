"""SQLite 存储。表结构用 PRAGMA user_version 做顺序迁移：只在 MIGRATIONS 末尾追加，不要改已有条目。"""
from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timezone
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
]

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
        self.con = sqlite3.connect(path)
        self._migrate()

    def _migrate(self) -> None:
        version = self.con.execute("pragma user_version").fetchone()[0]
        for i, sql in enumerate(MIGRATIONS[version:], start=version + 1):
            self.con.executescript(sql)
            self.con.execute(f"pragma user_version = {i}")
        self.con.commit()

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

    def event_signals(self, event_id: int) -> list[Signal]:
        rows = self.con.execute(f"select {COLUMNS}, event_id from signals where event_id = ? order by published",
                                (event_id,)).fetchall()
        return [_signal(r) for r in rows]

    def commit(self) -> None:
        self.con.commit()

    # ---------- 简报、反馈、推送 ----------

    def briefs(self, ids: Iterable[int]) -> dict[int, dict]:
        ids = list(ids)
        rows = self.con.execute("select event_id, created, model, signal_count, headline, summary, why, importance "
                                f"from briefs where event_id in ({','.join('?' * len(ids))})", ids)
        keys = ("created", "model", "signal_count", "headline", "summary", "why", "importance")
        return {r[0]: dict(zip(keys, r[1:])) for r in rows}

    def save_brief(self, event_id: int, now: datetime, model: str, signal_count: int, brief: dict) -> None:
        self.con.execute("insert or replace into briefs values (?, ?, ?, ?, ?, ?, ?, ?)",
                         (event_id, to_iso(now), model, signal_count, brief["headline"], brief["summary"],
                          brief["why"], brief["importance"]))
        self.con.commit()

    def feedback(self) -> dict[int, str]:
        return dict(self.con.execute("select event_id, value from feedback"))

    def set_feedback(self, event_id: int, value: str | None, now: datetime) -> None:
        if value is None:
            self.con.execute("delete from feedback where event_id = ?", (event_id,))
        else:
            self.con.execute("insert or replace into feedback values (?, ?, ?)", (event_id, value, to_iso(now)))
        self.con.commit()

    def pushed(self) -> set[int]:
        return {r[0] for r in self.con.execute("select event_id from pushes")}

    def pushes_since(self, since: datetime) -> int:
        return self.con.execute("select count(*) from pushes where at >= ?", (to_iso(since),)).fetchone()[0]

    def record_push(self, event_id: int, now: datetime) -> None:
        self.con.execute("insert or ignore into pushes values (?, ?)", (event_id, to_iso(now)))
        self.con.commit()

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

    def record_run(self, source: str, started: datetime, fetched: int, new: int, error: str | None = None) -> None:
        self.con.execute("insert into runs (started, source, fetched, new, error) values (?, ?, ?, ?, ?)",
                         (to_iso(started), source, fetched, new, error))
        self.con.commit()

    def last_runs(self) -> dict[str, dict]:
        rows = self.con.execute(
            "select source, started, fetched, new, error from runs where id in "
            "(select max(id) from runs group by source)").fetchall()
        return {r[0]: {"started": r[1], "fetched": r[2], "new": r[3], "error": r[4]} for r in rows}
