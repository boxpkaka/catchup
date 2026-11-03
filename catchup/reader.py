"""读原文：一般网页交给 DeepSeek fetch。它直接拒绝的几类链接（秒回 404）先换成读得到的地址：

- Google News：RSS 里给的是 Google 的跳转页，先向 Google 换出原文地址再读。
- Reddit 帖子：JSON 接口对服务器 IP 返回 403，帖子的 RSS（/comments/<id>/.rss）可以直接读，含正文和评论。
- Hugging Face 模型页：直接读模型卡 README.md；要申请才能下载的模型读不到（401），从模型页里渲染好的卡片取正文。
- X 帖子：用官方嵌入接口 oEmbed 取正文，免费、不需要 Key，但长帖会被截断。
- 论文（arXiv、HF Papers）：读 arXiv 的 HTML 全文版，按章节转成纯文本；没有 HTML 版的退回摘要页。
OpenAI 官网、YouTube、头条 DeepSeek 也读不了，暂时只用摘要。
"""
from __future__ import annotations

import json
import re
import urllib.error
import urllib.parse
from html.parser import HTMLParser

from . import http
from .sources.deepseek import tool, unescape_md
from .sources.feeds import parse_feed
from .util import clean_text

ARXIV = re.compile(r"https://(?:www\.)?(?:arxiv\.org/(?:abs|pdf|html)|huggingface\.co/papers)/(\d{4}\.\d{4,5})")
GOOGLE_NEWS = re.compile(r"https://news\.google\.com/(?:rss/)?articles/([\w-]+)")
REDDIT = re.compile(r"https://(?:www\.|old\.)?reddit\.com/r/(\w+)/comments/(\w+)")
HF_MODEL = re.compile(r"https://huggingface\.co/([\w.-]+/[\w.-]+)/?$")
HF_PAGES = {"papers", "blog", "datasets", "spaces", "docs", "collections", "posts", "organizations", "learn"}
X_POST = re.compile(r"https://(?:www\.|mobile\.)?(?:x|twitter)\.com/\w+/status/\d+")
MAX_COMMENTS = 20


def read(url: str) -> tuple[str, str]:
    """返回 (标题, 正文)。读不到就抛异常，由调用方退回摘要。"""
    if paper := arxiv_id(url):
        return _paper(paper)
    m = GOOGLE_NEWS.match(url)
    if m:
        url = _google_news(m.group(1))
    m = REDDIT.match(url)
    if m:
        return _reddit(*m.groups())
    m = HF_MODEL.match(url)
    if m and m.group(1).split("/")[0] not in HF_PAGES:
        return m.group(1), _model_card(m.group(1))
    m = X_POST.match(url)
    if m:
        return _x_post(m.group(0))
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


def _x_post(url: str) -> tuple[str, str]:
    """oEmbed 返回嵌入用的 <blockquote>，正文是其中的 <p>，后面是作者和日期。"""
    data = http.get_json(f"https://publish.x.com/oembed?omit_script=1&url={urllib.parse.quote(url, safe='')}")
    text = re.search(r"<p[^>]*>(.*?)</p>", data["html"], re.S)
    return data.get("author_name", ""), clean_text(text.group(1) if text else data["html"], 20000)


def _model_card(repo: str) -> str:
    try:
        text = http.get(f"https://huggingface.co/{repo}/raw/main/README.md").decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        if e.code not in (401, 403):
            raise
        page = http.get(f"https://huggingface.co/{repo}").decode("utf-8", "replace")
        start = page.find("model-card-content")
        if start < 0:
            raise
        return clean_text(page[start:].split(">", 1)[-1], 20000)
    return re.sub(r"\A---\n.*?\n---\n", "", text, flags=re.S).strip()  # 去掉开头的 YAML 元数据


def arxiv_id(url: str) -> str | None:
    """arXiv 和 HF Papers 链接里的论文编号（不带版本号），不是论文时为 None。"""
    m = ARXIV.match(url)
    return m.group(1) if m else None


def full_text_url(paper: str) -> str:
    """论文全文的地址：对话按它读原文、缓存，和简报读的摘要页（arXiv 链接本身）分开存。"""
    return f"https://arxiv.org/html/{paper}"


def _paper(paper: str) -> tuple[str, str]:
    """arXiv 的 HTML 全文版（LaTeXML 生成）转成按章节的纯文本；较早的和只交了 PDF 的论文没有 HTML 版（404），读摘要页。"""
    try:
        page = http.get(full_text_url(paper)).decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
        r = tool("fetch", {"url": f"https://arxiv.org/abs/{paper}"})
        return str(r.get("title") or ""), unescape_md(r.get("content") or "")
    parser = _PaperText()
    parser.feed(page)
    return parser.title.strip(), parser.text()


class _PaperText(HTMLParser):
    """LaTeXML 页面转纯文本：章节标题写成 ## 开头的一行，公式用 LaTeX 原文（alttext），表格一行一行用 | 隔开；
    去掉参考文献、脚注、作者单位和正文里的引用编号（免得和对话里标注出处的 [1] 混在一起）。"""
    SKIP_TAGS = {"script", "style", "nav", "header", "footer", "cite", "button"}
    SKIP_CLASSES = {"ltx_bibliography", "ltx_note", "ltx_authors", "ltx_page_header", "ltx_page_footer", "ltx_dates"}
    VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"}
    BLOCKS = {"p", "div", "section", "article", "li", "tr", "figcaption", "figure", "table", "ul", "ol", "blockquote"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.title = ""
        self.skip = 0  # 在要跳过的元素里的层数
        self.in_title = False

    def handle_starttag(self, tag, attrs):
        if tag in self.VOID:
            if tag == "br" and not self.skip:
                self.parts.append(" ")
            return
        if self.skip:
            self.skip += 1
            return
        a = dict(attrs)
        if tag in self.SKIP_TAGS or self.SKIP_CLASSES & set((a.get("class") or "").split()):
            self.skip = 1
        elif tag == "math":  # MathML 的文字是一个个符号，用 alttext 里的 LaTeX
            self.parts.append(f" ${a.get('alttext') or ''}$ ")
            self.skip = 1
        elif tag == "title":
            self.in_title = True
        elif re.fullmatch(r"h[1-6]", tag):
            self.parts.append("\n\n" + "#" * max(2, int(tag[1])) + " ")
        elif tag in ("td", "th"):
            self.parts.append(" | ")
        elif tag in self.BLOCKS:
            self.parts.append("\n")

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in self.VOID:
            self.handle_endtag(tag)

    def handle_endtag(self, tag):
        if tag in self.VOID:
            return
        if self.skip:
            self.skip -= 1
            return
        if tag == "title":
            self.in_title = False
        elif re.fullmatch(r"h[1-6]", tag) or tag in self.BLOCKS:
            self.parts.append("\n")

    def handle_data(self, data):
        if self.in_title:
            self.title += data
        elif not self.skip:
            self.parts.append(data)

    def text(self) -> str:
        """从论文标题（第一个 ## 行）开始，前面是 arXiv 页面自己的提示和表单。"""
        lines = (re.sub(r"[ \t ]+", " ", line).strip() for line in "".join(self.parts).split("\n"))
        text = re.sub(r"\n{3,}", "\n\n", "\n".join(lines))
        text = re.sub(r"^•\n+", "• ", text, flags=re.M)  # 列表的圆点和内容不在同一个元素里
        start = text.find("## ")
        return text[max(start, 0):].strip()
