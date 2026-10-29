from __future__ import annotations

import logging
import os
import re
import urllib.parse
from datetime import date, timedelta

from .. import http
from ..util import parse_time
from .base import Source, register

log = logging.getLogger(__name__)
API = "https://api.github.com"
TRENDING = "https://github.com/trending"


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


def _count(m) -> int:
    return int(m.group(1).replace(",", "")) if m else 0


def parse_trending(page: str) -> list[dict]:
    """Trending 网页里的仓库，按名次排列：仓库名、简介、语言、总星数、Fork 数和当天新增星数。"""
    repos = []
    for block in re.findall(r'<article class="Box-row">(.*?)</article>', page, re.S):
        block = re.sub(r"<svg.*?</svg>", "", block, flags=re.S)
        name = re.search(r'<h2[^>]*>\s*<a[^>]*href="/([^"/]+/[^"/]+)"', block)
        if not name:
            continue
        desc = re.search(r'<p class="[^"]*">(.*?)</p>', block, re.S)
        lang = re.search(r'itemprop="programmingLanguage">([^<]+)<', block)
        repos.append({"repo": name.group(1), "description": desc.group(1) if desc else "",
                      "language": lang.group(1).strip() if lang else "",
                      "stars": _count(re.search(r'/stargazers"[^>]*>\s*([\d,]+)', block)),
                      "forks": _count(re.search(r'/forks"[^>]*>\s*([\d,]+)', block)),
                      "stars_today": _count(re.search(r"([\d,]+) stars? today", block))})
    return repos


@register
class GitHubTrending(Source):
    """GitHub Trending 每日榜。没有官方 API，解析网页；languages 里的空串是不分语言的总榜。
    热度按当天新增星数算，一千星约为明显热门。榜单是热度信号，等级和 HN、Reddit 一样算弱信号：
    仓库简介里常堆着 agent、claude 之类的词，再按一手源加分就会挤满前排。"""
    type = "github_trending"
    default_tier = "signal"
    default_kind = "代码"
    label_format = "GitHub Trending"
    board = True

    def fetch(self):
        signals = []
        for lang in self.options.get("languages", [""]):
            channel = lang or "全部"
            try:
                repos = parse_trending(http.get(TRENDING + (f"/{lang}" if lang else "") + "?since=daily").decode())
            except Exception as e:
                log.warning("%s/%s failed: %r", self.name, channel, e)
                continue
            if not repos:  # 网页改版时解析不出来，别悄悄当成空榜
                log.warning("%s/%s: no repos parsed", self.name, channel)
            for r in repos:
                signals.append(self.signal(
                    f"https://github.com/{r['repo']}", r["repo"], r["description"], channel=channel,
                    author=r["repo"].split("/")[0], heat=r["stars_today"] / 1000,
                    extra={k: r[k] for k in ("stars_today", "stars", "forks", "language")}))
        return signals


@register
class GitHubSearch(Source):
    """近 N 天新建、星数达到门槛的仓库：按关键词找，能补到还没上 Trending 的新仓库。"""
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
