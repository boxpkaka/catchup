from __future__ import annotations

import logging
import re

from .. import http
from ..util import parse_time
from .base import Source, register

log = logging.getLogger(__name__)
HF = "https://huggingface.co"
# 列表接口用 expand 时只返回点名的字段；参数量在 safetensors（GGUF 仓库在 gguf）里，许可证和基座模型在 tags 里
EXPAND = "".join(f"&expand[]={f}" for f in ("createdAt", "likes", "downloads", "trendingScore", "safetensors", "gguf",
                                             "tags"))
SIZE_IN_NAME = re.compile(r"(?<![\w.])(\d+(?:\.\d+)?[BMT])(?![a-z])", re.I)  # Kokoro-82M、Large-4-1T、asr-0.6b


def _card(m: dict) -> dict:
    """App 模型页显示的信息：参数量、精度、许可证、基座模型和它们的关系（微调、量化、适配器、合并），没有的不写。
    权重不是 safetensors / GGUF 的（.pth、.nemo 之类）HF 算不出参数量，名字里写了大小就记下名字里的。"""
    out = {}
    weights = m.get("safetensors") or {}
    if weights.get("total"):
        out["params"] = weights["total"]
        dtypes = weights.get("parameters") or {}
        if dtypes:
            out["precision"] = max(dtypes, key=dtypes.get)  # 混合精度时取参数最多的那种
    elif (m.get("gguf") or {}).get("total"):
        out["params"], out["precision"] = m["gguf"]["total"], "GGUF"
    elif size := SIZE_IN_NAME.search(m["id"].split("/")[-1]):
        out["params_name"] = size.group(1).upper()
    for tag in m.get("tags") or []:
        key, _, value = tag.partition(":")
        if key == "license" and "license" not in out:
            out["license"] = value
        elif key == "base_model" and "base_model" not in out:
            relation, _, model = value.partition(":")
            if model:  # base_model:finetune:Qwen/Qwen3-8B-Base；只有 base_model:<id> 的不知道关系，等带关系的那条
                out["base_model"], out["base_relation"] = model, relation
    return out


@register
class HFModels(Source):
    """各任务类型的 trending 模型榜。"""
    type = "hf_models"
    default_tier = "primary"
    default_kind = "模型"
    label_format = "HF 模型"
    board = True

    def fetch(self):
        limit = self.options.get("limit", 20)
        signals = []
        for tag in self.options["pipeline_tags"]:
            for m in http.get_json(f"{HF}/api/models?sort=trendingScore&limit={limit}&pipeline_tag={tag}{EXPAND}"):
                signals.append(self.signal(
                    f"{HF}/{m['id']}", m["id"], channel=tag, author=m["id"].split("/")[0],
                    published=parse_time(m.get("createdAt")),
                    extra={"likes": m.get("likes"), "downloads": m.get("downloads"),
                           "trending": m.get("trendingScore"), **_card(m)}))
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
    paper = True

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
