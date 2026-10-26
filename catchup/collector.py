from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone

from .sources import Source
from .store import Store
from .util import parse_time

log = logging.getLogger(__name__)
# cron 每 10 分钟一轮；排在后面的来源开始得晚几分钟，下一轮差这几分钟也算到期，免得拖到再下一轮
TOLERANCE = timedelta(minutes=5)


def due(sources: dict[str, Source], store: Store, now: datetime) -> dict[str, Source]:
    """距上次开始采集（成功或失败）已满各自 interval 的来源，没采过的也算。"""
    last = store.last_runs()
    return {name: s for name, s in sources.items()
            if name not in last or now - parse_time(last[name]["started"]) >= timedelta(minutes=s.interval) - TOLERANCE}


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
        store.record_run(name, started, len(signals), new)
        log.info("[%s] %d items, %d new", name, len(signals), new)
    return ok
