from __future__ import annotations

import email.utils
import html
import re
from datetime import datetime, timezone


def parse_time(value: str | None) -> datetime | None:
    """解析 ISO 8601 或 RFC 822 时间，统一返回带时区的 UTC 时间；无法解析时返回 None。"""
    if not value:
        return None
    value = value.strip()
    try:
        t = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        try:
            t = email.utils.parsedate_to_datetime(value)
        except (TypeError, ValueError):
            return None
    if t.tzinfo is None:
        t = t.replace(tzinfo=timezone.utc)
    return t.astimezone(timezone.utc).replace(microsecond=0)


def to_iso(t: datetime | None) -> str | None:
    # 统一成同一格式，SQLite 里才能直接按字符串比较时间
    return t.astimezone(timezone.utc).replace(microsecond=0).isoformat() if t else None


def clean_text(value: str | None, limit: int = 500) -> str:
    """去掉 HTML 标签和多余空白，并截断。"""
    text = re.sub(r"<[^>]+>", " ", html.unescape(value or ""))
    return re.sub(r"\s+", " ", text).strip()[:limit]
