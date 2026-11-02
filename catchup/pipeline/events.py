"""把信号合并成事件。

两步：先按稳定标识合并（同一篇 arXiv 论文、同一个 GitHub 仓库、同一个 HF 模型），
再按标题相似度合并不同媒体对同一件事的报道。这是规则版实现，之后可替换为向量或大模型判断。
相似是传递的（A 像 B、B 像 C 就是一组），所以已经是两个事件的不再连起来，否则一篇点名好几家的汇总稿
就能把几件事串成一大组。
"""
from __future__ import annotations

import re

from ..models import Event, Signal

STOPWORDS = {"the", "and", "for", "with", "new", "its", "how", "what", "this", "that", "from", "into", "your",
             "are", "now", "can", "has", "all", "model", "models", "launches", "unveils", "releases", "announces"}
RARE_DF = 6          # 专有名词在窗口内出现的标题数不超过这个值才算“少见”
MIN_JACCARD = 0.2    # 共享少见专有名词时，标题用词重合度的下限
HIGH_JACCARD = 0.5   # 不看专有名词，仅凭用词重合即可合并的阈值
# Reddit 分享模型页的帖子，标题是 HF 页面的标题“机构/模型 · Hugging Face”。后缀不算标题用词：
# 这种短标题只靠“Hugging”“Face”两个词就够合并，曾把六个不相干的模型串成一件事
HF_PAGE = re.compile(r"(?:([\w.-]+/[\w.-]+)\s+)?·\s+Hugging Face$")


def stable_key(url: str) -> str:
    if m := re.search(r"arxiv\.org/abs/([\d.]+)|huggingface\.co/papers/([\d.]+)", url):
        return "paper:" + (m.group(1) or m.group(2))
    if m := re.match(r"https://github\.com/([^/]+/[^/#?]+)", url):
        return "repo:" + m.group(1).lower()
    if m := re.match(r"https://huggingface\.co/(?!papers/|blog/)([^/]+/[^/#?]+)", url):
        return "model:" + m.group(1).lower()
    return "url:" + url


def identity(s: Signal) -> str:
    """合并用的标识：稳定标识；链接到别处、标题是“机构/模型 · Hugging Face”的帖子算那个模型。"""
    key = stable_key(s.url)
    if key.startswith("url:") and (m := HF_PAGE.match(s.title.strip())) and m.group(1):
        return "model:" + m.group(1).lower()
    return key


def distinct(a: Event, b: Event) -> bool:
    """两件事各有同一类的稳定标识但没有一个相同：两篇论文、两个仓库或两个模型，不是同一件事。"""
    ka, kb = ({identity(s) for s in e.signals} for e in (a, b))
    for kind in ("paper:", "repo:", "model:"):
        x, y = {k for k in ka if k.startswith(kind)}, {k for k in kb if k.startswith(kind)}
        if x and y and not x & y:
            return True
    return False


def title_tokens(title: str) -> tuple[set[str], set[str]]:
    """返回 (全部有效词, 其中的专有名词)。专有名词按首字母大写或含数字粗略判断。
    中文按相邻两个字算一个词：只看夹在中文里的英文名，两条都只提到 DeepSeek 的中文标题用词完全重合，什么新闻都会并到一起。"""
    title = re.sub(r"\s+-\s+[^-]+$", "", title)  # 去掉 Google News 标题末尾的 " - 媒体名"
    title = HF_PAGE.sub(lambda m: m.group(1) or "", title.strip())
    words = re.findall(r"[A-Za-z][\w.]*\w|\d[\w.]*[A-Za-z]\w*", title, re.ASCII)
    words = [w for w in words if len(w) >= 3 and w.lower() not in STOPWORDS]
    han = {run[i:i + 2] for run in re.findall(r"[\u4e00-\u9fff]{2,}", title) for i in range(len(run) - 1)}
    return {w.lower() for w in words} | han, {w.lower() for w in words if w[0].isupper() or any(c.isdigit() for c in w)}


def _similar(a: tuple[set, set], b: tuple[set, set], df: dict[str, int]) -> bool:
    if not a[0] or not b[0]:
        return False
    jaccard = len(a[0] & b[0]) / len(a[0] | b[0])
    rare = any(df[w] <= RARE_DF for w in a[1] & b[1])
    return (rare and jaccard >= MIN_JACCARD) or jaccard >= HIGH_JACCARD


def cluster(signals: list[Signal]) -> list[Event]:
    groups: dict[str, list[Signal]] = {}
    for s in signals:
        groups.setdefault(identity(s), []).append(s)
    keys = list(groups)
    kinds = [k.split(":", 1)[0] for k in keys]
    tokens = [title_tokens(max(groups[k], key=lambda s: len(s.title)).title) for k in keys]
    # 倒排索引：只有共享至少一个词的标题才可能相似，避免两两比较所有事件
    index: dict[str, list[int]] = {}
    for i, (words, _) in enumerate(tokens):
        for w in words:
            index.setdefault(w, []).append(i)
    df = {w: len(ids) for w, ids in index.items()}

    parent = list(range(len(keys)))
    # 每组已经归属的事件，连起来以后记在根上
    owner = [min((s.event_id for s in groups[k] if s.event_id is not None), default=None) for k in keys]

    def find(i: int) -> int:
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for i in range(len(keys)):
        kind_i = kinds[i]
        for j in sorted({j for w in tokens[i][0] for j in index[w] if j > i}):
            # 两篇不同论文 / 两个不同仓库 / 两个不同模型，标识已经说明它们不是同一件事
            if kind_i != "url" and kind_i == kinds[j]:
                continue
            ri, rj = find(i), find(j)
            if ri == rj or (None not in (owner[ri], owner[rj]) and owner[ri] != owner[rj]):
                continue
            if _similar(tokens[i], tokens[j], df):
                parent[rj] = ri
                owner[ri] = owner[rj] if owner[ri] is None else owner[ri]

    merged: dict[int, list[Signal]] = {}
    for i, key in enumerate(keys):
        merged.setdefault(find(i), []).extend(groups[key])
    return [Event(key=keys[root], signals=sorted(sigs, key=lambda s: s.published))
            for root, sigs in merged.items()]


def assign_events(store, signals: list[Signal], now) -> int:
    """把窗口内尚未归属的信号归入持久化事件，返回新建的事件数。

    已归属的信号不再移动（只有 merge_events 会移动），所以事件 id 稳定：规则聚类里只要有一个成员已有事件，
    其余新信号就并进那个事件；整组都是新信号才新建事件。
    """
    created = 0
    for e in cluster(signals):
        owners = sorted({s.event_id for s in e.signals if s.event_id is not None})
        fresh = [s for s in e.signals if s.event_id is None]
        if not fresh:
            continue
        target = owners[0] if owners else store.new_event(e.key, now)
        created += not owners
        store.assign(target, [s.url for s in fresh])
        for s in fresh:
            s.event_id = target
    store.commit()
    return created


def group_events(signals: list[Signal]) -> list[Event]:
    """按持久化的 event_id 把信号分组成事件；未归属的信号忽略。"""
    groups: dict[int, list[Signal]] = {}
    for s in signals:
        if s.event_id is not None:
            groups.setdefault(s.event_id, []).append(s)
    return [Event(key=f"event:{i}", id=i, signals=sorted(sigs, key=lambda s: s.published))
            for i, sigs in groups.items()]
