"""读原文：一般网页交给 DeepSeek fetch。它直接拒绝的几类链接（秒回 404）先换成读得到的地址：

- Google News：RSS 里给的是 Google 的跳转页，先向 Google 换出原文地址再读。
- Reddit 帖子：JSON 接口对服务器 IP 返回 403，帖子的 RSS（/comments/<id>/.rss）可以直接读，含正文和评论。
- Hugging Face 模型页：直接读模型卡 README.md。
OpenAI 官网、YouTube、头条 DeepSeek 也读不了，暂时只用摘要。
"""
from __future__ import annotations

import json
import re

from . import http
from .sources.deepseek import tool, unescape_md
from .sources.feeds import parse_feed
from .util import clean_text

GOOGLE_NEWS = re.compile(r"https://news\.google\.com/(?:rss/)?articles/([\w-]+)")
REDDIT = re.compile(r"https://(?:www\.|old\.)?reddit\.com/r/(\w+)/comments/(\w+)")
HF_MODEL = re.compile(r"https://huggingface\.co/([\w.-]+/[\w.-]+)/?$")
HF_PAGES = {"papers", "blog", "datasets", "spaces", "docs", "collections", "posts", "organizations", "learn"}
MAX_COMMENTS = 20


def read(url: str) -> tuple[str, str]:
    """返回 (标题, 正文)。读不到就抛异常，由调用方退回摘要。"""
    m = GOOGLE_NEWS.match(url)
    if m:
        url = _google_news(m.group(1))
    m = REDDIT.match(url)
    if m:
        return _reddit(*m.groups())
    m = HF_MODEL.match(url)
    if m and m.group(1).split("/")[0] not in HF_PAGES:
        return m.group(1), _model_card(m.group(1))
    r = tool("fetch", {"url": url})
    return str(r.get("title") or ""), unescape_md(r.get("content") or "")


def _google_news(article: str) -> str:
    """文章编号要带上跳转页里的签名和时间戳去换原文地址，和网页版跳转时发的请求一样。"""
    page = http.get(f"https://news.google.com/rss/articles/{article}").decode("utf-8", "replace")
    sig = re.search(r'data-n-a-sg="([^"]+)"', page)
    ts = re.search(r'data-n-a-ts="(\d+)"', page)
    if not (sig and ts):
        raise ValueError(f"google news {article}: no signature")
    req = json.dumps(["garturlreq", [["X", "X", ["X", "X"], None, None, 1, 1, "US:en", None, 1, None, None, None,
                                      None, None, 0, 1], "X", "X", 1, [1, 1, 1], 1, 1, None, 0, 0, None, 0],
                      article, int(ts.group(1)), sig.group(1)])
    raw = http.post_form("https://news.google.com/_/DotsSplashUi/data/batchexecute",
                         {"f.req": json.dumps([[["Fbv4je", req, None, "generic"]]])}).decode("utf-8", "replace")
    # 返回 )]}' 开头，空一行后是 [["wrb.fr", "Fbv4je", "[\"garturlres\", \"<原文地址>\", 1]", ...], ...]
    return json.loads(json.loads(raw.split("\n\n", 1)[1])[0][2])[1]


def _reddit(sub: str, post: str) -> tuple[str, str]:
    """帖子的 RSS 第一条是正文，后面是评论。"""
    entries = parse_feed(http.get(f"https://www.reddit.com/r/{sub}/comments/{post}/.rss"))
    if not entries:
        raise ValueError(f"reddit {post}: empty feed")
    first, comments = entries[0], entries[1:MAX_COMMENTS + 1]
    body = re.sub(r"\s*submitted by /u/\S+ \[link\] \[comments\]$", "", clean_text(first["summary"], 20000))
    lines = [f"- {c['author'].removeprefix('/u/')}：{clean_text(c['summary'], 1000)}" for c in comments]
    return first["title"], (body + ("\n\n评论：\n" + "\n".join(lines) if lines else "")).strip()


def _model_card(repo: str) -> str:
    text = http.get(f"https://huggingface.co/{repo}/raw/main/README.md").decode("utf-8", "replace")
    return re.sub(r"\A---\n.*?\n---\n", "", text, flags=re.S).strip()  # 去掉开头的 YAML 元数据
