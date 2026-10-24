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
]

COLUMNS = "url, source, channel, title, summary, author, published, fetched, tier, kind, heat, extra"


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
        rows = self.con.execute(f"select {COLUMNS} from signals where published >= ? and published <= ?",
                                (to_iso(since), to_iso(until))).fetchall()
        return [Signal(url=r[0], source=r[1], channel=r[2] or "", title=r[3] or "", summary=r[4] or "",
                       author=r[5] or "", published=parse_time(r[6]), tier=r[8] or "signal", kind=r[9] or "资讯",
                       heat=r[10] or 0.0, extra=json.loads(r[11] or "{}")) for r in rows]

    def record_run(self, source: str, started: datetime, fetched: int, new: int, error: str | None = None) -> None:
        self.con.execute("insert into runs (started, source, fetched, new, error) values (?, ?, ?, ?, ?)",
                         (to_iso(started), source, fetched, new, error))
        self.con.commit()

    def last_runs(self) -> dict[str, dict]:
        rows = self.con.execute(
            "select source, started, fetched, new, error from runs where id in "
            "(select max(id) from runs group by source)").fetchall()
        return {r[0]: {"started": r[1], "fetched": r[2], "new": r[3], "error": r[4]} for r in rows}
