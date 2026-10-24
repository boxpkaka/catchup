"""导入各来源模块以触发 @register 注册。新增来源模块后在这里加一行导入。"""
from . import arxiv, feeds, github, hackernews, huggingface  # noqa: F401
from .base import REGISTRY, Source, build_sources, register

__all__ = ["REGISTRY", "Source", "build_sources", "register"]
