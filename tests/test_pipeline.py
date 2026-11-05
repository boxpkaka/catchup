import unittest
from datetime import datetime, timedelta, timezone

from catchup.models import Signal
from catchup.pipeline.events import cluster, stable_key
from catchup.pipeline.ranking import Ranker, keyword_in

NOW = datetime(2026, 10, 24, 12, tzinfo=timezone.utc)


def sig(url, title, source="news", tier="signal", hours_ago=1, heat=0.0):
    return Signal(url=url, title=title, source=source, tier=tier, heat=heat,
                  published=NOW - timedelta(hours=hours_ago))


class EventTest(unittest.TestCase):
    def test_stable_keys(self):
        self.assertEqual(stable_key("https://arxiv.org/abs/2610.30949"), "paper:2610.30949")
        self.assertEqual(stable_key("https://huggingface.co/papers/2610.30949"), "paper:2610.30949")
        self.assertEqual(stable_key("https://github.com/OpenAI/codex/releases/tag/v1"), "repo:openai/codex")
        self.assertEqual(stable_key("https://huggingface.co/Qwen/Qwen3-TTS"), "model:qwen/qwen3-tts")
        self.assertTrue(stable_key("https://huggingface.co/blog/foo").startswith("url:"))

    def test_same_paper_across_sources(self):
        [e] = cluster([sig("https://arxiv.org/abs/2610.1", "Paper", "arxiv"),
                       sig("https://huggingface.co/papers/2610.1", "Paper", "hf_papers")])
        self.assertEqual(e.sources, ["arxiv", "hf_papers"])

    def test_news_about_same_thing_merge(self):
        events = cluster([
            sig("https://a.com/1", "Block unveils Dessa, a full-duplex voice model - TechCrunch"),
            sig("https://b.com/2", "Block announces Dessa voice model trained on speech - The Verge"),
            sig("https://c.com/3", "OpenAI ships new reasoning benchmark"),
        ])
        self.assertEqual(sorted(len(e.signals) for e in events), [1, 2])

    def test_chinese_titles_need_more_than_a_shared_name(self):
        events = cluster([sig("https://a.com/1", "腾讯混元同声传译大模型 Hy-SimulMT 开源 - DoNews"),
                          sig("https://b.com/2", "腾讯混元同声传译大模型Hy-SimulMT发布并开源 - 东方财富"),
                          sig("https://c.com/3", "DeepSeek 开源了推理引擎，最值钱的三样却没给"),
                          sig("https://d.com/4", "DeepSeek，全球第一！ - 银柿财经")])
        self.assertEqual(sorted([s.url[8] for s in e.signals] for e in events), [["a", "b"], ["c"], ["d"]])

    def test_different_papers_never_merge(self):
        events = cluster([sig("https://arxiv.org/abs/2610.1", "Fine-Grained Speech Benchmark"),
                          sig("https://arxiv.org/abs/2610.2", "Fine-Grained Speech Benchmark v2")])
        self.assertEqual(len(events), 2)

    def test_hf_page_posts_join_their_model_not_each_other(self):
        events = cluster([sig("https://huggingface.co/Nanbeige/Nanbeige4.3-33B", "Nanbeige/Nanbeige4.3-33B", "hf_models"),
                          sig("https://reddit.com/r/LocalLLaMA/1", "Nanbeige/Nanbeige4.3-33B · Hugging Face", "reddit"),
                          sig("https://reddit.com/r/LocalLLaMA/2", "tencent/Hy-SimulMT-30B-A3B · Hugging Face", "reddit"),
                          sig("https://reddit.com/r/LocalLLaMA/3", "baidu/Unlimited-OCR-2 · Hugging Face", "reddit")])
        self.assertEqual(sorted(e.sources for e in events), [["hf_models", "reddit"], ["reddit"], ["reddit"]])

    def test_lead_prefers_higher_tier(self):
        [e] = cluster([sig("https://github.com/a/b/releases/tag/v2", "a/b v2", "gh", "official", hours_ago=1),
                       sig("https://github.com/a/b", "a/b", "hn", "signal", hours_ago=5)])
        self.assertEqual(e.lead.tier, "official")
        self.assertEqual(e.time, NOW - timedelta(hours=5))


class BriefTest(unittest.TestCase):
    def test_unstated_year_is_the_latest_past_one(self):
        from catchup.pipeline.brief import _day
        now = datetime(2026, 11, 5, 7, tzinfo=timezone.utc)
        said = "科技博主在10月31日发布的视频里演示了样机。"
        self.assertEqual(_day("2025-10-31", now, said), "2026-10-31")  # 材料里没有 2025，模型把年份推成了去年
        self.assertEqual(_day("2026-12-03", now, "12月3日"), "2025-12-03")  # 还没到的月日是去年的
        self.assertEqual(_day("2026-05-28", now, "Released 2026-05-28"), "2026-05-28")
        self.assertEqual(_day("2025-10-31", now), "2025-10-31")  # 没给材料不核对
        self.assertIsNone(_day("2026-12-03", now))  # 写明的年份在未来，不可信
        self.assertIsNone(_day("5月28日", now))

    def test_original_starts_at_the_title(self):
        from catchup.pipeline.brief import _from_title
        page = "Introducing Eleven v4 Meet Eleven v4, our most emotive model yet.\n# Fix speech to text accuracy problems\nBody"
        self.assertEqual(_from_title(page, "Fix speech to text accuracy problems - ElevenLabs"),
                         "Fix speech to text accuracy problems\nBody")  # 站内横幅不给模型
        self.assertEqual(_from_title(page, "Something else entirely"), page)
        self.assertIsNone(_from_title(None, "Fix speech to text accuracy problems"))


class RankingTest(unittest.TestCase):
    ranker = Ranker.from_config(
        [{"name": "AI 语音", "weight": 3, "keywords": ["tts", "voice", "语音"]}], {"half_life_hours": 36})

    def test_keyword_word_boundary(self):
        self.assertTrue(keyword_in("tts", "new tts model"))
        self.assertTrue(keyword_in("agent", "ai agents are here"))
        self.assertFalse(keyword_in("tts", "1000 watts"))
        self.assertTrue(keyword_in("语音", "语音大模型发布"))

    def test_relevant_official_ranks_first(self):
        events = cluster([sig("https://x.com/1", "Kubernetes 2.0 released", tier="official"),
                          sig("https://y.com/2", "New TTS voice model", tier="official")])
        top, other = self.ranker.rank(events, NOW)
        self.assertEqual(top.topics, ["AI 语音"])
        self.assertIn("来自官方一级源", top.reasons)
        self.assertGreater(top.score, other.score)

    def test_time_decay(self):
        fresh, = self.ranker.rank(cluster([sig("https://x.com/1", "TTS", hours_ago=0)]), NOW)
        old, = self.ranker.rank(cluster([sig("https://x.com/1", "TTS", hours_ago=36)]), NOW)
        self.assertAlmostEqual(old.score, fresh.score / 2, places=1)


if __name__ == "__main__":
    unittest.main()
