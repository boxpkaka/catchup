"""导入各来源模块以触发 @register 注册。新增来源模块后在这里加一行导入。"""
from . import ainews, arxiv, deepseek, feeds, github, hackernews, huggingface, openrouter  # noqa: F401
from .base import REGISTRY, Source, build_sources, register

__all__ = ["REGISTRY", "SHARED", "Source", "build_sources", "register", "source_of"]

SHARED = "shared"  # 用户从别的 App 分享进来的链接，不属于任何配置的来源，见 catchup/share.py


def source_of(signal, labels: dict[str, Source]) -> tuple[str, str]:
    """来源的显示名和匹配标识。标识是排序时能在 source / channel / author 里找到的原文：
    Google News 的 channel 是我们的搜索词，真正的媒体在 author；显示名里没有 channel 的（Hacker News、arXiv）用来源编号。"""
    if signal.source == SHARED:
        return "你分享的", SHARED
    if signal.source == "google_news" and signal.author:
        return signal.author, signal.author
    src = labels.get(signal.source)
    label = src.label(signal.channel) if src else signal.source
    return label, signal.channel if signal.channel and signal.channel in label else signal.source
