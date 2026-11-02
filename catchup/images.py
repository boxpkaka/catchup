"""事件封面和网站图标：App 不直连外网，由服务器去原网站取回来，存在 data/images 下，同一张只取一次。

- 封面：文章页里的 og:image / twitter:image（Google News 先换出原文地址），用 cwebp（系统包 webp）缩到 720px 宽的
  WebP，每张约 20KB。太小的（多半是图标）和很扁的（多半是 logo 横幅）不要。
- 网站图标：Google 的 favicon 服务，64px，约 1KB；它不认识的网站返回 404。
取不到就记一个 .none，以后直接回答没有；封面的 .none 记着当时有几条报道，事件有了新报道再找一次。
超时、断网这类不算取不到，下次再试。
"""
from __future__ import annotations

import logging
import os
import re
import subprocess
import tempfile
import urllib.error
from html import unescape
from pathlib import Path
from urllib.parse import urljoin

from . import http, reader
from .config import Config
from .models import Signal

log = logging.getLogger(__name__)

BROWSER = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                         "Chrome/126.0 Safari/537.36"}  # 不少新闻站不给不像浏览器的请求返回页面
OG_IMAGE = re.compile(r'<meta[^>]+(?:property|name)=["\'](?:og:image|twitter:image)(?::src)?["\'][^>]*>', re.I)
WIDTH = 720
MIN_WIDTH = 300
MAX_RATIO = 2.5  # 宽是高的这么多倍以上，多半是 logo 横幅
TRIES = 3  # 一个事件最多看最新的几条报道
TIMEOUT = 10


def cover(config: Config, event_id: int, signals: list[Signal]) -> bytes | None:
    """事件封面：从最新的几条报道里找第一张能用的图。"""
    path = config.db_path.parent / "images" / "covers" / f"{event_id}.webp"
    none = path.with_suffix(".none")
    if path.exists():
        return path.read_bytes()
    if none.exists() and none.read_text() == str(len(signals)):
        return None
    answered = True
    for s in sorted(signals, key=lambda s: s.published, reverse=True)[:TRIES]:
        try:
            url = _page_image(s.url)
            data = url and _shrink(http.get(url, BROWSER, TIMEOUT))
        except urllib.error.HTTPError as e:  # 网站明确拒绝或没有这一页
            log.info("cover %d: %s %s", event_id, s.url, e)
            continue
        except Exception as e:
            log.info("cover %d: %s %r", event_id, s.url, e)
            answered = False
            continue
        if data:
            _save(path, data)
            return data
    if answered:
        _save(none, str(len(signals)).encode())
    return None


def icon(config: Config, host: str) -> bytes | None:
    path = config.db_path.parent / "images" / "icons" / host
    none = path.with_name(host + ".none")
    if path.exists():
        return path.read_bytes()
    if none.exists():
        return None
    try:
        data = http.get(f"https://www.google.com/s2/favicons?domain={host}&sz=64", timeout=TIMEOUT)
    except urllib.error.HTTPError:  # 不认识的网站返回 404 和一个地球图标，当作没有
        _save(none, b"")
        return None
    except Exception as e:
        log.info("icon %s: %r", host, e)
        return None
    _save(path, data)
    return data


def mime(data: bytes) -> str:
    return "image/webp" if data[:4] == b"RIFF" else "image/png" if data[:4] == b"\x89PNG" else "image/jpeg"


def _page_image(url: str) -> str | None:
    m = reader.GOOGLE_NEWS.match(url)
    if m:
        url = reader._google_news(m.group(1))
    tag = OG_IMAGE.search(http.get(url, BROWSER, TIMEOUT).decode("utf-8", "replace"))
    content = tag and re.search(r'content=["\']([^"\']+)', tag.group(0))
    return urljoin(url, unescape(content.group(1))) if content else None


def _shrink(raw: bytes) -> bytes | None:
    """缩成不超过 WIDTH 宽的 WebP；cwebp 读不了的格式、太小或太扁的图返回 None。"""
    probe = _webp(raw, "-m", "0")  # 先最快地编一遍，看原图尺寸
    if not probe or probe[1] < MIN_WIDTH or probe[1] > probe[2] * MAX_RATIO:
        return None
    out = _webp(raw, "-resize", str(min(probe[1], WIDTH)), "0")
    return out and out[0]


def _webp(raw: bytes, *args: str) -> tuple[bytes, int, int] | None:
    """返回 (WebP, 宽, 高)，尺寸取自 cwebp 输出里的 Dimension 一行（缩放之后的）。"""
    p = subprocess.run(["cwebp", "-q", "75", *args, "-o", "-", "--", "-"], input=raw, capture_output=True,
                       timeout=30)
    m = re.search(rb"Dimension: (\d+) x (\d+)", p.stderr)
    return (p.stdout, int(m[1]), int(m[2])) if p.returncode == 0 and m else None


def _save(path: Path, data: bytes) -> None:
    """先写临时文件再改名，同时来读的请求不会读到半张图。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent)
    with os.fdopen(fd, "wb") as f:
        f.write(data)
    os.replace(tmp, path)
