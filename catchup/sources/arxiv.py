from __future__ import annotations

import re
import time
import urllib.parse
import xml.etree.ElementTree as ET

from .. import http
from ..util import parse_time
from .base import Source, register

ATOM = "{http://www.w3.org/2005/Atom}"
API = "https://export.arxiv.org/api/query"


@register
class Arxiv(Source):
    type = "arxiv"
    default_tier = "primary"
    default_kind = "论文"
    label_format = "arXiv"

    def fetch(self):
        max_results = self.options.get("max_results", 100)
        signals = []
        for i, query in enumerate(self.options["queries"]):
            if i:
                time.sleep(3)  # arXiv API 要求请求间隔 3 秒
            url = (f"{API}?sortBy=submittedDate&sortOrder=descending&max_results={max_results}"
                   f"&search_query={urllib.parse.quote(query)}")
            for e in ET.fromstring(http.get(url)).iter(f"{ATOM}entry"):
                # 去掉版本号，同一篇论文的 v1/v2 视为同一条
                link = re.sub(r"v\d+$", "", e.findtext(f"{ATOM}id", "")).replace("http://", "https://")
                authors = ", ".join(a.findtext(f"{ATOM}name", "") for a in e.iter(f"{ATOM}author"))
                signals.append(self.signal(
                    link, e.findtext(f"{ATOM}title", ""), e.findtext(f"{ATOM}summary", ""), channel=query,
                    author=authors[:200], published=parse_time(e.findtext(f"{ATOM}published"))))
        return signals
