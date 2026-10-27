"""把 App 上会出现的英文原文翻成中文：来源标题，以及还没写简报的事件直接显示的标题和摘要。

有简报的事件标题、概要、要点本来就是中文。译文按原文缓存在 translations 表，每轮推送之后只翻新出现的；
接口返回时换成译文（见 web/app_api.py），还没翻到的先显示原文。
"""
from __future__ import annotations

import json
import logging
import re

from ..models import Event

log = logging.getLogger(__name__)

SUMMARY_CHARS = 160  # 没有简报的事件，App 显示主报道摘要的前这么多字
BATCH = 25
LIMIT = 200  # 每轮最多翻这么多条，积压时分几轮翻完，不拖到下一轮采集
CJK = re.compile(r"[一-鿿]")

SYSTEM = """把输入 JSON 数组里的每一条翻译成简体中文，输出 JSON：{"items": ["译文", ...]}，条数和顺序与输入一致。
公司、产品、模型、人名等专有名词保留原文；只翻译，不增删内容。"""


def originals(store, events: list[Event], briefs: dict) -> list[str]:
    """App 上可能直接显示的原文，排名靠前的事件在前。"""
    out = []
    for e in events:
        out += [s.title for s in store.event_signals(e.id)]  # 详情页列出事件的全部报道，包括时间窗之前的
        if e.id not in briefs:
            out.append(e.lead.summary[:SUMMARY_CHARS])
    return out


def run(store, llm, events: list[Event]) -> int:
    """翻译还没有译文的英文原文，返回新翻的条数。"""
    texts = [t for t in dict.fromkeys(originals(store, events, store.briefs(e.id for e in events)))
             if t.strip() and not CJK.search(t)]
    done = store.translations(texts)
    pending = [t for t in texts if t not in done][:LIMIT]
    count = 0
    for i in range(0, len(pending), BATCH):
        batch = pending[i:i + BATCH]
        try:
            items = llm.json(SYSTEM, json.dumps(batch, ensure_ascii=False), max_tokens=5000).get("items")
        except Exception as e:
            log.warning("translate failed: %r", e)
            continue
        if not isinstance(items, list) or len(items) != len(batch):  # 对不上就整批丢掉，下一轮再翻
            log.warning("translate: %d items back for %d", len(items) if isinstance(items, list) else -1, len(batch))
            continue
        pairs = {src: str(zh).strip() for src, zh in zip(batch, items) if str(zh).strip()}
        store.save_translations(pairs)
        count += len(pairs)
    return count
