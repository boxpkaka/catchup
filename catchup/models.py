"""核心数据模型：Signal 是某个来源抓到的一条原始内容，Event 是若干 Signal 合并成的“一件事”。"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime

# 来源等级：official 是官方一级源，primary 是一手技术源，signal 是社区/新闻等弱信号
TIER_ORDER = {"official": 2, "primary": 1, "signal": 0}


@dataclass
class Signal:
    url: str
    title: str
    source: str               # 来源实例名，即配置里 sources 的 key
    channel: str = ""         # 来源内的细分：feed 名、版块、仓库、查询词等
    summary: str = ""
    author: str = ""
    published: datetime | None = None
    tier: str = "signal"
    kind: str = "资讯"
    heat: float = 0.0         # 来源自己归一化的热度，约 1 表示“明显热门”
    extra: dict = field(default_factory=dict)
    event_id: int | None = None  # 归属的持久化事件，见 pipeline/events.py 的 assign_events


@dataclass
class Event:
    key: str
    signals: list[Signal]
    id: int | None = None     # 持久化后的事件 id，App 和推送靠它引用同一件事
    score: float = 0.0
    topics: list[str] = field(default_factory=list)
    reasons: list[str] = field(default_factory=list)

    @property
    def lead(self) -> Signal:
        """代表这件事的信号：等级最高者优先，同级取最早出现的。"""
        return max(self.signals, key=lambda s: (TIER_ORDER.get(s.tier, 0), -s.published.timestamp()))

    @property
    def time(self) -> datetime:
        return min(s.published for s in self.signals)

    @property
    def sources(self) -> list[str]:
        return sorted({s.source for s in self.signals})
