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

    def test_different_papers_never_merge(self):
        events = cluster([sig("https://arxiv.org/abs/2610.1", "Fine-Grained Speech Benchmark"),
                          sig("https://arxiv.org/abs/2610.2", "Fine-Grained Speech Benchmark v2")])
        self.assertEqual(len(events), 2)

    def test_lead_prefers_higher_tier(self):
        [e] = cluster([sig("https://github.com/a/b/releases/tag/v2", "a/b v2", "gh", "official", hours_ago=1),
                       sig("https://github.com/a/b", "a/b", "hn", "signal", hours_ago=5)])
        self.assertEqual(e.lead.tier, "official")
        self.assertEqual(e.time, NOW - timedelta(hours=5))


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
