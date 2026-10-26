import unittest
from unittest import mock

from catchup.sources import build_sources
from catchup.sources.feeds import parse_feed

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
