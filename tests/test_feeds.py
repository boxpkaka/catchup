import unittest
from datetime import datetime, timedelta, timezone
from unittest import mock

from catchup.collector import collect, due, health
from catchup.sources import build_sources
from catchup.sources.feeds import parse_feed
from catchup.store import Store

RSS = b"""<?xml version="1.0"?><rss version="2.0"><channel><title>t</title>
<item><title>Hello &amp; world</title><link> https://example.com/a </link>
<description>&lt;p&gt;Body&lt;/p&gt;</description><pubDate>Fri, 23 Oct 2026 15:27:31 GMT</pubDate>
<source url="https://x.com">TechCrunch</source></item></channel></rss>"""

ATOM = b"""<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom">
<entry><title>Video</title><link rel="alternate" href="https://youtube.com/watch?v=1"/>
<author><name>Hung-yi Lee</name></author><published>2026-10-20T08:00:00+00:00</published></entry>
<entry><title>No rel</title><link href="https://example.com/b"/><updated>2026-10-21T08:00:00Z</updated></entry>
</feed>"""


class ParseFeedTest(unittest.TestCase):
    def test_rss(self):
        [e] = parse_feed(RSS)
        self.assertEqual(e["url"], " https://example.com/a ")
        self.assertEqual(e["author"], "TechCrunch")
        self.assertEqual(e["published"].isoformat(), "2026-10-23T15:27:31+00:00")

    def test_atom_link_without_children(self):
        # 回归：<link/> 没有子节点，布尔值为 False，曾因 `find() or find()` 丢失链接
        a, b = parse_feed(ATOM)
        self.assertEqual(a["url"], "https://youtube.com/watch?v=1")
        self.assertEqual(a["author"], "Hung-yi Lee")
        self.assertEqual(b["url"], "https://example.com/b")
        self.assertEqual(b["published"].isoformat(), "2026-10-21T08:00:00+00:00")


class FeedSourceTest(unittest.TestCase):
    def test_signal_fields_and_partial_failure(self):
        src = build_sources({"blogs": {"type": "feed", "tier": "official", "feeds": [
            {"name": "ok", "url": "https://ok"}, {"name": "bad", "url": "https://bad"}]}})["blogs"]

        def fake_get(url, headers=None):
            if url == "https://bad":
                raise OSError("boom")
            return RSS

        with mock.patch("catchup.http.get", fake_get), self.assertLogs("catchup.sources.feeds", "WARNING"):
            [s] = src.fetch()
        self.assertEqual((s.url, s.title, s.summary), ("https://example.com/a", "Hello & world", "Body"))
        self.assertEqual((s.source, s.channel, s.tier), ("blogs", "ok", "official"))

    def test_labels(self):
        sources = build_sources({
            "yt": {"type": "youtube", "channels": {"李宏毅": "UC1"}},
            "r": {"type": "reddit", "subreddits": ["LocalLLaMA"]},
            "gn": {"type": "google_news", "queries": [{"name": "AI", "q": "LLM", "lang": "zh"}]},
        })
        self.assertEqual(sources["yt"].label("李宏毅"), "YouTube · 李宏毅")
        self.assertEqual(sources["r"].label("LocalLLaMA"), "r/LocalLLaMA")
        self.assertEqual(sources["gn"].label("AI"), "Google News")
        self.assertIn("hl=zh-CN", sources["gn"].feeds()[0][1])

    def test_unknown_type(self):
        with self.assertRaisesRegex(ValueError, "unknown type"):
            build_sources({"x": {"type": "nope"}})

    def test_due_by_interval(self):
        store, now = Store(":memory:"), datetime(2026, 10, 26, 12, tzinfo=timezone.utc)
        sources = build_sources({"fast": {"type": "feed", "feeds": [], "interval": 10},
                                 "slow": {"type": "feed", "feeds": [], "interval": 360},
                                 "never": {"type": "feed", "feeds": []}})
        store.record_run("fast", now - timedelta(minutes=7), 1, 0)  # 上一轮晚开始了几分钟，也算到期
        store.record_run("slow", now - timedelta(hours=2), 0, 0, "boom")  # 失败了也等满间隔再试
        self.assertEqual(set(due(sources, store, now)), {"fast", "never"})
        store.record_run("fast", now - timedelta(minutes=4), 1, 0)
        self.assertEqual(set(due(sources, store, now)), {"never"})

    def test_health(self):
        store, now = Store(":memory:"), datetime(2026, 10, 26, 12, tzinfo=timezone.utc)
        sources = build_sources({"a": {"type": "feed", "feeds": [], "title": "YouTube"}, "b": {"type": "feed", "feeds": []},
                                 "c": {"type": "feed", "feeds": []}, "w": {"type": "page_watch", "pages": []}})

        def run(name, hours, fetched=0, error=None):
            store.record_run(name, now + timedelta(hours=hours), fetched, 0, error)

        for name in "abcw":
            run(name, -4, 0 if name == "w" else 5)
        for hours in (-3, -2, -1):
            run("a", hours)  # 连续失败三轮，但上次采到内容才是 4 小时前：YouTube 每天都会这样
            run("w", hours)  # 页面监控没变化本来就是 0 条
        run("b", -2, error="boom")
        run("b", -1)
        self.assertEqual(health(sources, store, now), "")
        run("c", -2)
        run("c", -1)  # 三个来源同时连续失败两轮，多半是断网了
        self.assertEqual(health(sources, store, now), "服务疑似故障：YouTube 等 3 个来源采不到内容")
        for name in "abcw":
            run(name, 1, 0 if name in "aw" else 5)
        self.assertEqual(health(sources, store, now + timedelta(hours=2)), "服务疑似故障：YouTube 采不到内容")  # 6 小时没采到
        self.assertEqual(health(sources, store, now + timedelta(hours=3)), "服务疑似故障：采集停了")


AINEWS = b"""<?xml version="1.0"?><rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel>
<item><title>[AINews] Kimi</title><link>https://latent.space/p/ainews-kimi/</link><content:encoded><![CDATA[
<h1>AI Twitter Recap</h1><ul><li><p><b>The model:</b> 2.8T MoE.
<a href="https://twitter.com/Kimi_Moonshot/status/2113680701770068067?utm_source=latent.space">@Kimi_Moonshot</a></p>
<ul><li><p>nested, cites nothing</p></li></ul></li></ul>
<div><p><b>Top tweets (by engagement)</b></p></div><ul>
<li><p><a href="https://x.com/Kimi_Moonshot/status/2113680701770068067?utm_source=latent.space">30.4K \xe2\x80\x94 Kimi: K3.1 Pro</a></p></li>
<li><p><a href="https://x.com/NSACyber/status/2112903220209098897">NSA: patch PHP</a> \xe2\x80\x94 <b>80,000</b></p></li>
<li><p><a href="https://x.com/claudeai/status/2107894039626277339">Introducing Claude Haiku 5.5 (~44K)</a></p></li>
<li><p>no link here</p></li></ul>
<h1>AI Reddit Recap</h1><ul><li>reddit</li></ul>]]></content:encoded></item>
<item><title>[AINews] quiet</title><link>https://latent.space/p/ainews-quiet/</link>
<content:encoded><![CDATA[<h1>AI Twitter Recap</h1>]]></content:encoded></item>
</channel></rss>"""


class AINewsTest(unittest.TestCase):
    def test_top_tweets_become_signals(self):
        src = build_sources({"ainews": {"type": "ainews"}})["ainews"]
        with mock.patch("catchup.http.get", return_value=AINEWS):
            kimi, nsa, haiku = src.fetch()  # 没有热帖一节的期次跳过，没有链接的条目跳过
        self.assertEqual((kimi.url, kimi.title, kimi.channel), (
            "https://x.com/Kimi_Moonshot/status/2113680701770068067", "Kimi: K3.1 Pro", "Kimi_Moonshot"))
        self.assertEqual(kimi.summary, "The model: 2.8T MoE. @Kimi_Moonshot")  # 正文里的详述，不含嵌套子项
        self.assertEqual(kimi.published.isoformat(), "2026-10-23T17:15:24+00:00")  # 来自帖子 id
        self.assertEqual(kimi.extra, {"likes": 30400, "issue": "https://latent.space/p/ainews-kimi/"})
        self.assertEqual((nsa.title, nsa.heat, nsa.summary), ("NSA: patch PHP", 8.0, ""))
        self.assertEqual((haiku.title, haiku.heat), ("Introducing Claude Haiku 5.5", 4.4))
        self.assertEqual(src.label(nsa.channel), "X · @NSACyber")


TECHMEME = b"""<?xml version="1.0"?><rss version="2.0"><channel>
<item><title>Amazon Bee review: a $75 AI wearable (M.G. Siegler/Spyglass)</title>
<link>https://www.techmeme.com/261026/p2#a261026p2</link>
<description><![CDATA[<A HREF="https://spyglass.org/amazon-bee/"><IMG SRC="http://www.techmeme.com/261026/i2.jpg"></A>
<P><A HREF="https://www.techmeme.com/261026/p2#a261026p2" TITLE="Techmeme permalink"></A> M.G. Siegler: review</P>]]></description>
<pubDate>Mon, 26 Oct 2026 01:05:01 -0400</pubDate></item></channel></rss>"""


TRENDING = """<article class="Box-row">
  <h2 class="h3 lh-condensed">
    <a data-hydro-click="{&quot;payload&quot;:1}" href="/acme/voice" class="Link"><svg class="octicon"><path d="M2"></path></svg>
      <span class="text-normal">acme /</span> voice</a></h2>
  <p class="col-9 color-fg-muted my-1">Local <g-emoji>🎙</g-emoji> TTS &amp; ASR</p>
  <div class="f6"><span itemprop="programmingLanguage">Python</span>
    <a href="/acme/voice/stargazers" class="Link"><svg><path d="M8"></path></svg>
      12,345</a>
    <a href="/acme/voice/forks" class="Link"><svg></svg> 678</a>
    <span class="d-inline-block float-sm-right"><svg></svg> 1,500 stars today</span></div>
</article>
<article class="Box-row"><h2 class="h3"><a href="/x/y" class="Link">x / y</a></h2>
  <div class="f6"><a href="/x/y/stargazers" class="Link">9</a> 1 star today</div></article>""".encode()


PRODUCT_HUNT = b"""<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom">
<entry><id>tag:www.producthunt.com,2005:Post/1294903</id><published>2026-10-27T10:20:52-07:00</published>
<link rel="alternate" type="text/html" href="https://www.producthunt.com/products/lfm2"/><title>LFM3 by Liquid AI</title>
<content type="html">&lt;p&gt; Six 2B-class models for phones &lt;/p&gt; &lt;p&gt;
&lt;a href="https://www.producthunt.com/products/lfm2?utm_campaign=x&amp;amp;utm_medium=rss-feed"&gt;Discussion&lt;/a&gt;
| &lt;a href="https://www.producthunt.com/r/p/1294903?app_id=339"&gt;Link&lt;/a&gt; &lt;/p&gt;</content>
<author><name>Hunter</name></author></entry></feed>"""


class RealtimeSourcesTest(unittest.TestCase):
    def test_product_hunt_uses_launch_link(self):
        src = build_sources({"ph": {"type": "product_hunt"}})["ph"]
        with mock.patch("catchup.http.get", return_value=PRODUCT_HUNT) as get:
            [s] = src.fetch()
        self.assertIn("category=artificial-intelligence", get.call_args[0][0])
        self.assertEqual((s.url, s.title, s.summary, s.kind), ("https://www.producthunt.com/r/p/1294903",
                                                               "LFM3 by Liquid AI", "Six 2B-class models for phones", "产品"))
        self.assertEqual(s.extra, {"product": "https://www.producthunt.com/products/lfm2"})

    def test_github_trending_parses_page_and_keeps_ranks(self):
        src = build_sources({"gt": {"type": "github_trending", "languages": ["", "rust"]}})["gt"]
        urls = []

        def fake_get(url, headers=None):
            urls.append(url)
            if "/rust" in url:
                raise OSError("boom")
            return TRENDING

        store = Store(":memory:")
        with mock.patch("catchup.http.get", fake_get), self.assertLogs("catchup.sources.github", "WARNING"):
            collect({"gt": src}, store)
        self.assertEqual(urls, ["https://github.com/trending?since=daily", "https://github.com/trending/rust?since=daily"])
        voice, small = store.recent(datetime(2000, 1, 1, tzinfo=timezone.utc), datetime.now(timezone.utc))
        self.assertEqual((voice.url, voice.title, voice.summary, voice.channel, voice.heat),
                         ("https://github.com/acme/voice", "acme/voice", "Local 🎙 TTS & ASR", "全部", 1.5))
        self.assertEqual(voice.extra, {"stars_today": 1500, "stars": 12345, "forks": 678, "language": "Python"})
        self.assertEqual((small.summary, small.extra["stars"], small.extra["stars_today"]), ("", 9, 1))
        self.assertEqual([(b["rank"], b["title"]) for b in store.board("gt")], [(1, "acme/voice"), (2, "x/y")])

    def test_techmeme_uses_original_link_and_media(self):
        src = build_sources({"techmeme": {"type": "techmeme"}})["techmeme"]
        with mock.patch("catchup.http.get", return_value=TECHMEME):
            [s] = src.fetch()
        self.assertEqual((s.url, s.title, s.author), (
            "https://spyglass.org/amazon-bee/", "Amazon Bee review: a $75 AI wearable", "Spyglass"))
        self.assertEqual(s.extra["techmeme"], "https://www.techmeme.com/261026/p2#a261026p2")

    def test_openrouter_newest_models_with_price(self):
        src = build_sources({"or": {"type": "openrouter", "limit": 1}})["or"]
        data = {"data": [
            {"id": "old/model", "name": "Old", "created": 1700000000},
            {"id": "moonshotai/kimi-k3.1-pro", "name": "MoonshotAI: Kimi K3.1 Pro", "created": 1792778188,
             "description": "Flagship.", "context_length": 1048576,
             "pricing": {"prompt": "0.000002", "completion": "0.00001"}}]}
        with mock.patch("catchup.http.get_json", return_value=data):
            [s] = src.fetch()
        self.assertEqual((s.url, s.channel, s.tier), ("https://openrouter.ai/moonshotai/kimi-k3.1-pro", "moonshotai", "primary"))
        self.assertEqual(s.summary, "每百万 token：输入 $2，输出 $10。Flagship.")
        self.assertEqual(s.published.isoformat(), "2026-10-23T17:56:28+00:00")

    def test_hf_new_models_per_author_and_partial_failure(self):
        src = build_sources({"hf": {"type": "hf_new", "authors": ["deepseek-ai", "bad"]}})["hf"]

        def fake_get_json(url, headers=None):
            if "author=bad" in url:
                raise OSError("boom")
            return [{"id": "deepseek-ai/DeepSeek-V4.1-Flash-1017", "createdAt": "2026-10-17T02:40:00.000Z"}]

        with mock.patch("catchup.http.get_json", fake_get_json), \
                self.assertLogs("catchup.sources.huggingface", "WARNING"):
            [s] = src.fetch()
        self.assertEqual((s.url, s.channel), ("https://huggingface.co/deepseek-ai/DeepSeek-V4.1-Flash-1017", "deepseek-ai"))
        self.assertEqual(s.published.isoformat(), "2026-10-17T02:40:00+00:00")


if __name__ == "__main__":
    unittest.main()


REDDIT_POST = b"""<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom">
<entry><author><name>/u/op</name></author><title>Lure-TTS: 600M voice model</title>
<link href="https://www.reddit.com/r/LocalLLaMA/comments/1xf14j2/lure/"/>
<content type="html">&lt;div class="md"&gt;&lt;p&gt;I finetuned Qwen3-TTS.&lt;/p&gt;&lt;/div&gt; submitted by /u/op [link] [comments]</content></entry>
<entry><author><name>/u/fan</name></author><title>/u/fan on Lure-TTS</title>
<link href="https://www.reddit.com/r/LocalLLaMA/comments/1xf14j2/lure/c1/"/>
<content type="html">&lt;p&gt;Online demo?&lt;/p&gt;</content></entry>
</feed>"""


class ReaderTest(unittest.TestCase):
    def test_google_news_link_resolved_before_reading(self):
        from catchup import reader
        page = b'<c-wiz><div jscontroller="x" data-n-a-sg="SIG" data-n-a-ts="1761400000"></div></c-wiz>'
        reply = b')]}\'\n\n[["wrb.fr","Fbv4je","[\\"garturlres\\",\\"https://gigazine.net/a/\\",1]",null,null,null,"generic"]]'
        with mock.patch("catchup.reader.http.get", return_value=page), \
                mock.patch("catchup.reader.http.post_form", return_value=reply) as post, \
                mock.patch("catchup.reader.tool", return_value={"title": "OmniVoice", "content": "a\\.b"}) as tool:
            self.assertEqual(reader.read("https://news.google.com/rss/articles/CBMiYkFV?oc=5"), ("OmniVoice", "a.b"))
        self.assertIn("SIG", post.call_args[0][1]["f.req"])
        tool.assert_called_once_with("fetch", {"url": "https://gigazine.net/a/"})

    def test_reddit_post_read_from_its_feed(self):
        from catchup import reader
        with mock.patch("catchup.reader.http.get", return_value=REDDIT_POST) as get:
            title, text = reader.read("https://www.reddit.com/r/LocalLLaMA/comments/1xf14j2/lure/")
        get.assert_called_once_with("https://www.reddit.com/r/LocalLLaMA/comments/1xf14j2/.rss")
        self.assertEqual((title, text), ("Lure-TTS: 600M voice model", "I finetuned Qwen3-TTS.\n\n评论：\n- fan：Online demo?"))

    def test_hugging_face_model_reads_card(self):
        from catchup import reader
        card = b"---\nlicense: apache-2.0\n---\n# Raon-Speech\n\nA 9B speech model."
        with mock.patch("catchup.reader.http.get", return_value=card) as get:
            self.assertEqual(reader.read("https://huggingface.co/KRAFTON/Raon-Speech-9B"),
                             ("KRAFTON/Raon-Speech-9B", "# Raon-Speech\n\nA 9B speech model."))
        get.assert_called_once_with("https://huggingface.co/KRAFTON/Raon-Speech-9B/raw/main/README.md")
        with mock.patch("catchup.reader.tool", return_value={"content": "x"}) as tool:
            reader.read("https://huggingface.co/papers/2610.31066")  # 论文页不是模型
        tool.assert_called_once()

    def test_x_post_read_via_oembed(self):
        from catchup import reader
        embed = {"author_name": "Artificial Analysis", "html": '<blockquote class="twitter-tweet"><p lang="en">'
                 'Kimi K3.1 Pro scores 55 &amp; ranks 4th<a href="https://t.co/x">pic.twitter.com/x</a></p>'
                 '&mdash; Artificial Analysis (@ArtificialAnlys) <a href="https://x.com/a">October 23, 2026</a></blockquote>'}
        with mock.patch("catchup.reader.http.get_json", return_value=embed) as get:
            self.assertEqual(reader.read("https://twitter.com/ArtificialAnlys/status/2113688083145314416?s=46"),
                             ("Artificial Analysis", "Kimi K3.1 Pro scores 55 & ranks 4th pic.twitter.com/x"))
        get.assert_called_once_with("https://publish.x.com/oembed?omit_script=1"
                                    "&url=https%3A%2F%2Ftwitter.com%2FArtificialAnlys%2Fstatus%2F2113688083145314416")
