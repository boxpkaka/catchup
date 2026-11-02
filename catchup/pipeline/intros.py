"""给只有名字的条目写一句中文简介：HF 模型的来源只给仓库名，读模型卡来写。

写进信号的 summary，榜单、话题专题里榜单上的条目、没写简报的事件显示的都是它。只写还没有简介的；
模型卡不存在的按机构写一句，不再重试。
"""
from __future__ import annotations

import json
import logging
import re
import urllib.error
from datetime import datetime, timedelta

from .. import reader
from ..sources import Source

log = logging.getLogger(__name__)

TYPES = {"hf_models", "hf_new"}
LIMIT = 40  # 每轮最多读这么多张模型卡，积压时分几轮写完
BATCH = 5
CARD_CHARS = 3000

SYSTEM = """给每个 Hugging Face 模型写一句中文简介，输出 JSON：{"items": [{"id": "输入里的 id", "intro": "简介"}]}。
简介 1～2 句，不超过 80 个字：谁做的、是什么模型（规模、架构、基于哪个模型）、能做什么、和同类相比特别在哪。
只写模型卡里有的信息，不要编造数字；模型、公司等专有名词保留原文；不要写“模型卡没有提到”这类说明缺了什么的话。"""


def run(store, llm, sources: dict[str, Source], now: datetime) -> int:
    """给还没有简介的 HF 模型写简介，返回写了几条。榜单上的按名次在前，其次是最近一天新发现的。"""
    names = {n for n, s in sources.items() if s.type in TYPES}
    rows = sorted((r for n in names if sources[n].board for r in store.board(n)), key=lambda r: r["rank"])
    pending = list(dict.fromkeys([(r["url"], r["title"]) for r in rows if not r["summary"]] +
                                 [(s.url, s.title) for s in store.fetched_since(now - timedelta(days=1))
                                  if s.source in names and not s.summary]))[:LIMIT]
    intros, cards = {}, []
    for url, repo in pending:
        try:
            card = _clean(reader.read(url)[1])[:CARD_CHARS]
        except urllib.error.HTTPError as e:
            if e.code not in (401, 403, 404):
                log.warning("intro %s: %r", repo, e)
                continue
            card = ""
        except Exception as e:
            log.warning("intro %s: %r", repo, e)
            continue
        if card:
            cards.append({"id": repo, "url": url, "card": card})
        else:
            intros[url] = f"{repo.split('/')[0]} 发布的模型"
    for i in range(0, len(cards), BATCH):
        batch = {c["id"]: c["url"] for c in cards[i:i + BATCH]}
        try:
            items = llm.json(SYSTEM, json.dumps([{"id": c["id"], "card": c["card"]} for c in cards[i:i + BATCH]],
                                                ensure_ascii=False), max_tokens=1500).get("items")
        except Exception as e:
            log.warning("intros failed: %r", e)
            continue
        for x in items if isinstance(items, list) else []:  # 没写出来的下一轮再写
            if isinstance(x, dict) and x.get("id") in batch and str(x.get("intro") or "").strip():
                intros[batch[x["id"]]] = str(x["intro"]).strip()[:200]
    store.set_summaries(intros)
    return len(intros)


def _clean(card: str) -> str:
    """模型卡开头常是徽章、图片和 HTML，去掉之后正文才排得进前 CARD_CHARS 个字。"""
    card = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", card)
    card = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", card)
    card = re.sub(r"<[^>]+>", "", card)
    return re.sub(r"\n\s*\n+", "\n\n", card).strip()
