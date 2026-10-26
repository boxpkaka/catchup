"""来源插件的基类与注册表。

新增一种来源：继承 Source，设置 type，实现 fetch()，再用 @register 注册；
然后在 config.json 的 sources 里加一个 {"type": "<type>", ...} 的实例即可。
"""
from __future__ import annotations

import logging
from typing import ClassVar, Iterable

from ..models import Signal
from ..util import clean_text

log = logging.getLogger(__name__)

REGISTRY: dict[str, type[Source]] = {}


def register(cls: type[Source]) -> type[Source]:
    REGISTRY[cls.type] = cls
    return cls


class Source:
    type: ClassVar[str]
    default_tier: ClassVar[str] = "signal"
    default_kind: ClassVar[str] = "资讯"
    label_format: ClassVar[str] = "{channel}"   # 页面上显示的来源名，可用 {channel} 和 {title}

    def __init__(self, name: str, options: dict):
        self.name = name
        self.options = options
        self.title = options.get("title", name)
        self.tier = options.get("tier", self.default_tier)
        self.kind = options.get("kind", self.default_kind)
        self.interval = options.get("interval", 60)  # 采样间隔（分钟），见 collector.due

    def fetch(self) -> Iterable[Signal]:
        raise NotImplementedError

    def label(self, channel: str) -> str:
        return self.options.get("label", self.label_format).format(channel=channel or self.title, title=self.title)

    def signal(self, url: str, title: str, summary: str = "", **fields) -> Signal:
        fields.setdefault("tier", self.tier)
        fields.setdefault("kind", self.kind)
        return Signal(url=url.strip(), title=clean_text(title, 300), summary=clean_text(summary),
                      source=self.name, **fields)


def build_sources(config: dict[str, dict]) -> dict[str, Source]:
    sources = {}
    for name, options in config.items():
        if options.get("enabled", True) is False:
            continue
        cls = REGISTRY.get(options.get("type", ""))
        if cls is None:
            raise ValueError(f"source {name!r}: unknown type {options.get('type')!r}, "
                             f"available: {', '.join(sorted(REGISTRY))}")
        sources[name] = cls(name, options)
    return sources
