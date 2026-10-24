from __future__ import annotations

import logging
from datetime import datetime, timezone

from .sources import Source
from .store import Store

log = logging.getLogger(__name__)


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
