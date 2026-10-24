from __future__ import annotations

import logging
import os
import urllib.parse
from datetime import date, timedelta

from .. import http
from ..util import parse_time
from .base import Source, register

log = logging.getLogger(__name__)
API = "https://api.github.com"


def api(path: str):
    headers = {"Accept": "application/vnd.github+json"}
    if token := os.environ.get("GITHUB_TOKEN"):
        headers["Authorization"] = f"Bearer {token}"
    return http.get_json(API + path, headers)


@register
class GitHubReleases(Source):
    """关注仓库的 release；同一仓库的多个版本会在事件层合并成一条时间线。"""
    type = "github_releases"
    default_tier = "official"
    default_kind = "代码"
    label_format = "GitHub Release"

    def fetch(self):
        per_page = self.options.get("per_repo", 5)
        signals = []
        for repo in self.options["repos"]:
            try:
                releases = api(f"/repos/{repo}/releases?per_page={per_page}")
            except Exception as e:
                log.warning("%s/%s failed: %r", self.name, repo, e)
                continue
            for r in releases:
                signals.append(self.signal(
                    r["html_url"], f"{repo} {r['tag_name']}", r.get("body") or "", channel=repo, author=repo,
                    published=parse_time(r.get("published_at")), extra={"prerelease": r["prerelease"]}))
        return signals


@register
class GitHubSearch(Source):
    """近 N 天新建、星数达到门槛的仓库，作为 Trending 的替代（GitHub 没有官方 Trending API）。"""
    type = "github_search"
    default_tier = "primary"
    default_kind = "代码"
    label_format = "GitHub 新仓库"

    def fetch(self):
        since = (date.today() - timedelta(days=self.options.get("days", 7))).isoformat()
        min_stars = self.options.get("min_stars", 50)
        signals = []
        for q in self.options["queries"]:
            query = urllib.parse.quote(f"{q} created:>{since} stars:>={min_stars}")
            for r in api(f"/search/repositories?sort=stars&per_page=20&q={query}")["items"]:
                signals.append(self.signal(
                    r["html_url"], r["full_name"], r.get("description") or "", channel=q,
                    author=r["owner"]["login"], published=parse_time(r["created_at"]),
                    heat=r["stargazers_count"] / 300, extra={"stars": r["stargazers_count"]}))
        return signals
