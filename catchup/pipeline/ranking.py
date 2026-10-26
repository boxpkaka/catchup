"""事件打分。分数越高越值得打扰用户，每一项加分都会留下一条可解释的理由。

当前是规则版：兴趣关键词 + 来源等级 + 多源 + 热度 + 时间衰减。
兴趣画像来自 config.json 的 interests，之后应由自动学习的 Interest Graph 替代。
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime

from ..models import Event

DEFAULTS = {
    "tier_weights": {"official": 2.0, "primary": 1.0, "signal": 0.0},
    "half_life_hours": 36,
    "multi_source_bonus": 1.5,
    "per_report_bonus": 0.4,
    "max_report_bonus": 2.0,
    "max_heat": 3.0,
    "off_topic_factor": 0.4,   # 与任何兴趣都无关的事件乘以这个系数
    "avoid_factor": 0.25,      # 命中“不想看”的记忆
    "source_bonus": 1.0,       # 来自偏好的来源
}


@dataclass
class Interest:
    name: str
    weight: float
    keywords: list[str]
    said: str = ""   # 来自记忆时是用户说过的原话，理由里直接引用

    def hits(self, text: str) -> list[str]:
        return sorted(k for k in self.keywords if keyword_in(k.lower(), text))


def keyword_in(keyword: str, text: str) -> bool:
    if keyword not in text:  # 先用子串快速排除，绝大多数关键词走不到正则
        return False
    if not keyword.isascii():
        return True
    # 英文按整词匹配（允许复数），避免 tts 命中 watts 这类子串
    return re.search(rf"(?<![a-z0-9]){re.escape(keyword)}(?:s|es)?(?![a-z0-9])", text) is not None


class Ranker:
    def __init__(self, interests: list[Interest], avoid: list[Interest] | None = None,
                 sources: list[Interest] | None = None, avoid_sources: list[Interest] | None = None, **options):
        self.interests = interests
        self.avoid = avoid or []       # 不想看：命中即大幅降权
        self.sources = sources or []   # 偏好的来源：关键词匹配来源名和频道
        self.avoid_sources = avoid_sources or []  # 不想看的来源：同样匹配来源名和频道，命中即大幅降权
        self.opt = {**DEFAULTS, **options}

    @classmethod
    def from_config(cls, interests: list[dict], ranking: dict) -> Ranker:
        keys = DEFAULTS.keys()
        return cls([Interest(i["name"], i.get("weight", 1.0), i["keywords"]) for i in interests],
                   **{k: v for k, v in ranking.items() if k in keys})

    def score(self, event: Event, now: datetime) -> Event:
        opt = self.opt
        lead = event.lead
        text = " ".join([lead.title, lead.summary] + [s.title for s in event.signals]).lower()
        score, reasons, topics = 0.0, [], []

        for interest in self.interests:
            if hits := interest.hits(text):
                score += interest.weight + 0.3 * (len(hits) - 1)
                topics.append(interest.name)
                reasons.append(f"和你说过的「{interest.said}」有关" if interest.said
                               else f"命中你关注的「{interest.name}」：{'、'.join(hits[:3])}")

        origin = " ".join(f"{s.source} {s.channel} {s.author}" for s in event.signals).lower()
        for pref in self.sources:
            if pref.hits(origin):
                score += opt["source_bonus"]
                reasons.append(f"来自你偏好的来源：{pref.said or pref.name}")
                break

        score += opt["tier_weights"].get(lead.tier, 0.0)
        if lead.tier == "official":
            reasons.append("来自官方一级源")

        n_sources, n_reports = len(event.sources), len(event.signals)
        score += opt["multi_source_bonus"] * (n_sources - 1)
        score += min(opt["per_report_bonus"] * (n_reports - 1), opt["max_report_bonus"])
        if n_reports > 1:
            reasons.append(f"{n_reports} 条报道 / {n_sources} 个来源")

        heat = sum(s.heat for s in event.signals)
        score += min(heat, opt["max_heat"])
        if heat >= 1:
            reasons.append("社区热度高")

        age_hours = max((now - event.time).total_seconds() / 3600, 0)
        score *= 0.5 ** (age_hours / opt["half_life_hours"])
        if not topics:
            score *= opt["off_topic_factor"]
        for a in self.avoid:
            if a.hits(text):
                score *= opt["avoid_factor"]
                reasons.append(f"{a.said}，已降低优先级" if a.said else f"你说过不想看「{a.name}」，已降低优先级")
                break
        for a in self.avoid_sources:
            if a.hits(origin):
                score *= opt["avoid_factor"]
                reasons.append(f"{a.said}，已降低优先级" if a.said else f"来自你不想看的来源：{a.name}，已降低优先级")
                break

        event.score, event.topics, event.reasons = round(score, 2), topics, reasons
        return event

    def rank(self, events: list[Event], now: datetime) -> list[Event]:
        return sorted((self.score(e, now) for e in events), key=lambda e: -e.score)
