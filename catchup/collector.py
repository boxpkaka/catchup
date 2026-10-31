from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone

from .sources import Source
from .store import Store
from .util import parse_time

log = logging.getLogger(__name__)
# cron 每 10 分钟一轮；排在后面的来源开始得晚几分钟，下一轮差这几分钟也算到期，免得拖到再下一轮
TOLERANCE = timedelta(minutes=5)
# 上次失败了的来源不等满 interval，隔这么久就再试，断网恢复后尽快补采；不每轮都试，免得被限流的来源越试越久
RETRY = timedelta(minutes=20)
# 疑似故障（health）。失败指报错或采到 0 条：多数来源吞掉单个地址的错误，断网时只表现为 0 条
STALL = timedelta(hours=1)  # 所有来源都超过间隔这么久还没再采，就是采集停了
DOWN = timedelta(hours=6)   # 单个来源连续失败这么久才算坏了：YouTube 的 RSS 每天会 404 几个小时
OUTAGE = 3                  # 同时有这么多来源连续失败两轮，多半是网络或代理断了，不用等 DOWN


def due(sources: dict[str, Source], store: Store, now: datetime) -> dict[str, Source]:
    """距上次开始采集已满各自 interval 的来源，没采过的也算；上次失败了（报错或 0 条，quiet 的来源 0 条不算）的满 RETRY
    就算到期。"""
    last = store.last_runs()
    out = {}
    for name, s in sources.items():
        run, wait = last.get(name), timedelta(minutes=s.interval)
        if run and (run["error"] or (run["fetched"] == 0 and not s.quiet)):
            wait = min(wait, RETRY)
        if run is None or now - parse_time(run["started"]) >= wait - TOLERANCE:
            out[name] = s
    return out


def health(sources: dict[str, Source], store: Store, now: datetime) -> str:
    """采集是否疑似故障：正常时返回空字符串，否则返回 App 首页显示的一句话。"""
    last = store.last_runs()
    late = [now - parse_time(last[name]["started"]) - timedelta(minutes=s.interval)
            for name, s in sources.items() if name in last]
    if late and min(late) >= STALL:
        return "服务疑似故障：采集停了"
    failing = store.failing_sources(now - timedelta(days=2))
    shaky = {}  # 连续失败两轮以上的来源 → 是否已经坏了很久
    for name, s in sources.items():
        ok, misses = failing.get(name, ("", 0))
        if not s.quiet and misses >= 2:
            shaky[s.title] = misses >= 3 and (ok is None or now - parse_time(ok) >= DOWN)
    down = list(shaky) if len(shaky) >= OUTAGE else [title for title, long in shaky.items() if long]
    if not down:
        return ""
    names = "、".join(down) if len(down) <= 2 else f"{down[0]} 等 {len(down)} 个来源"
    return f"服务疑似故障：{names}{' ' if names[-1].isascii() else ''}采不到内容"


def collect(sources: dict[str, Source], store: Store) -> bool:
    """依次运行各来源，一个来源失败不影响其他来源。全部成功返回 True。"""
    ok = True
    for name, source in sources.items():
        started = datetime.now(timezone.utc)
        try:
            signals = list(source.fetch())
        except Exception as e:
            ok = False
            log.error("[%s] failed: %r", name, e)
            store.record_run(name, started, 0, 0, repr(e))
            continue
        new = store.add(signals)
        if source.board:
            store.update_board(name, signals, started)
        store.record_run(name, started, len(signals), new)
        log.info("[%s] %d items, %d new", name, len(signals), new)
    return ok
