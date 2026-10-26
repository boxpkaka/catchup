from __future__ import annotations

import logging

from .. import http
from ..util import parse_time
from .base import Source, register

log = logging.getLogger(__name__)
HF = "https://huggingface.co"


@register
class HFModels(Source):
    """各任务类型的 trending 模型榜。"""
    type = "hf_models"
    default_tier = "primary"
    default_kind = "模型"
    label_format = "HF 模型"

    def fetch(self):
        limit = self.options.get("limit", 20)
        signals = []
        for tag in self.options["pipeline_tags"]:
            for m in http.get_json(f"{HF}/api/models?sort=trendingScore&limit={limit}&pipeline_tag={tag}"):
                signals.append(self.signal(
                    f"{HF}/{m['id']}", m["id"], channel=tag, author=m["id"].split("/")[0],
                    published=parse_time(m.get("createdAt")),
                    extra={"likes": m.get("likes"), "downloads": m.get("downloads"),
                           "trending": m.get("trendingScore")}))
        return signals


@register
class HFNewModels(Source):
    """指定机构最新建的模型仓库：开源权重一公开就能发现，比 trending 榜早一两天。URL 和 hf_models 相同，同一模型只记一次。"""
    type = "hf_new"
    default_tier = "primary"
    default_kind = "模型"
    label_format = "HF 新模型"

    def fetch(self):
        limit = self.options.get("limit", 10)
        signals = []
        for org in self.options["authors"]:
            try:
                models = http.get_json(f"{HF}/api/models?author={org}&sort=createdAt&direction=-1&limit={limit}")
            except Exception as e:
                log.warning("%s/%s failed: %r", self.name, org, e)
                continue
            for m in models:
                signals.append(self.signal(
                    f"{HF}/{m['id']}", m["id"], channel=org, author=org, published=parse_time(m.get("createdAt")),
                    extra={"likes": m.get("likes"), "downloads": m.get("downloads")}))
        return signals


@register
class HFDailyPapers(Source):
    type = "hf_papers"
    default_tier = "primary"
    default_kind = "论文"
    label_format = "HF Papers"

    def fetch(self):
        signals = []
        for item in http.get_json(f"{HF}/api/daily_papers?limit={self.options.get('limit', 50)}"):
            paper = item["paper"]
            upvotes = paper.get("upvotes") or 0
            signals.append(self.signal(
                f"{HF}/papers/{paper['id']}", paper["title"], paper.get("summary") or "",
                published=parse_time(paper.get("publishedAt")), heat=upvotes / 20,
                extra={"upvotes": upvotes, "arxiv": paper["id"]}))
        return signals
