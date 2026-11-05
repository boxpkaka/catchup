import unittest
from datetime import timedelta
from unittest import mock

from catchup.models import Signal
from catchup.pipeline import brief, novelty, push
from catchup.pipeline.events import assign_events
from catchup.pipeline.update import ranked, update
from catchup.store import Store
from tests.test_app import NOW, FakeLLM, config, sig

OPUS = "https://www.anthropic.com/news/claude-opus-4-8"


class Judge(FakeLLM):
    """简报带上名字和发生日期；查旧时按 verdict 回答（是异常就抛出），记下给它看的材料。"""

    def __init__(self, verdict=None, entities=("Claude Opus 4.8",), happened="", **kw):
        super().__init__(**kw)
        self.verdict, self.entities, self.happened, self.judged = verdict, list(entities), happened, []

    def json(self, system, user, max_tokens=800, **options):
        if system == novelty.SYSTEM:
            self.judged.append(user)
            if isinstance(self.verdict, Exception):
                raise self.verdict
            return self.verdict or {"verdict": "new", "match": None, "reason": ""}
        out = super().json(system, user, max_tokens, **options)
        names = {"subject": self.entities[0], "aliases": self.entities[1:]} if self.entities else {"subject": ""}
        return {**out, **names, "happened": self.happened} if "headline" in out else out


class NoveltyTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(":memory:")

    def event(self, *signals, now=NOW, llm=None):
        """采到这些信号、归入事件、写简报，返回这件事的 id。"""
        self.store.add(signals, now=now)
        assign_events(self.store, ranked(config(), self.store, NOW)[0], now)
        eid = self.store.signal_event(signals[0].url)
        brief.write_briefs(self.store, [e for e in self.events() if e.id == eid], "", llm or Judge(), {}, now)
        return eid

    def events(self):
        return ranked(config(), self.store, NOW)[1]

    def check(self, llm):
        return novelty.run(self.store, self.events(), llm, {}, NOW)

    def verdict(self, eid):
        return self.store.novelty([eid]).get(eid, {}).get("verdict")

    def old_launch(self):
        """五个月前的发布，时间窗外才采到，没有归入事件。"""
        self.store.add([Signal(url=OPUS, title="Introducing Claude Opus 4.8", source="news",
                               published=NOW - timedelta(days=160))], now=NOW - timedelta(days=10))

    def test_old_news_found_in_the_index_is_stale(self):
        self.old_launch()
        repost = sig("https://github.com/acme/mj/blob/1/reports/2026-06-01-weekly-report.md",
                     "AI Model & Vendor Weekly Intelligence Report", hours_ago=0)  # 搜索结果没有发布时间，记成采到的时间
        repost.summary = "Gemini 3.5 Pro | TBD ... - Claude Opus 4.8"
        eid = self.event(repost)
        llm = Judge({"verdict": "same", "match": 1, "reason": "5 月的发布"})
        self.assertEqual(self.check(llm), ({"checked": 1, "stale": 1, "deduped": 0}, set()))
        self.assertIn("Introducing Claude Opus 4.8", llm.judged[0])
        self.assertEqual({k: v for k, v in self.store.novelty([eid])[eid].items() if k != "checked"},
                         {"verdict": "stale", "ref_event": None, "ref_url": OPUS, "reason": "5 月的发布"})
        self.assertEqual(self.events(), [])  # 信息流、专题、推送都看不到
        # 旧闻不再吸收新报道：标题一样的另一篇自成一件事，再查一次
        self.store.add([sig("https://c/weekly", "AI Model & Vendor Weekly Intelligence Report", hours_ago=0)], now=NOW)
        assign_events(self.store, ranked(config(), self.store, NOW)[0], NOW)
        self.assertNotIn(self.store.signal_event("https://c/weekly"), (None, eid))

    def test_an_old_date_is_evidence_for_the_model_not_a_verdict(self):
        month_ago = (NOW - timedelta(days=30)).date().isoformat()
        llm = Judge({"verdict": "old", "reason": "九月的发布"}, entities=["Foo 2"], happened=month_ago)
        eid = self.event(sig("https://a/1", "Acme ships Foo 2"), llm=llm)
        self.check(llm)  # 检索库里没有更早的，但材料写明是一个月前的事，还是要问
        self.assertEqual((self.verdict(eid), len(llm.judged)), ("stale", 1))
        self.assertIn(f"材料里写的发生日期：{month_ago}", llm.judged[0])
        self.assertIn("检索库里更早的内容：\n（没有）", llm.judged[0])
        llm = Judge({"verdict": "new"}, entities=["Bar 3"], happened=month_ago)  # 日期写错了也不会被挡掉
        wrong = self.event(sig("https://b/2", "Beta ships Bar 3"), llm=llm)
        self.check(llm)
        self.assertEqual(self.verdict(wrong), "new")
        llm = Judge({"verdict": "old"}, entities=["Baz 4"], happened=(NOW - timedelta(days=2)).date().isoformat())
        late = self.event(sig("https://c/3", "Gamma ships Baz 4"), llm=llm)  # 几天前的事，第一次见到，不用问
        self.check(llm)
        self.assertEqual((self.verdict(late), llm.judged), ("new", []))

    def test_news_about_an_old_product_is_not_old_news(self):
        self.old_launch()  # 5 月的发布；这次的报道把发生日期写成了发布日
        llm = Judge({"verdict": "update", "match": 1, "reason": "被曝出新问题"},
                    happened=(NOW - timedelta(days=160)).date().isoformat())
        eid = self.event(sig("https://a/flaw", "Claude Opus 4.8 found leaking system prompts"), llm=llm)
        self.check(llm)
        self.assertEqual(self.verdict(eid), "update")
        self.assertEqual([e.id for e in push.select({}, self.store, self.events(), NOW)], [eid])

    def test_nothing_earlier_is_new_without_asking(self):
        self.old_launch()
        llm = Judge(entities=["Dessa"])
        eid = self.event(sig("https://a/1", "Block unveils Dessa"), llm=llm)
        self.check(llm)
        self.assertEqual((self.verdict(eid), llm.judged), ("new", []))
        self.assertEqual([e.id for e in push.select({}, self.store, self.events(), NOW)], [eid])

    def test_a_few_days_old_match_is_old_news_only_if_you_were_told(self):
        # 五天前那篇（时间窗外、一周以内）没推送过的，你还不知道，照常推；推送过的，或材料写明是两个月前的事，是旧闻
        for told, happened, expected in ((False, "", "new"), (True, "", "stale"),
                                         (False, (NOW - timedelta(days=60)).date().isoformat(), "stale")):
            self.store = Store(":memory:")
            url, days = "https://a/1", 5
            self.store.add([sig(url, "Claude watermark rollout leaves a loophole", hours_ago=24 * days)],
                           now=NOW - timedelta(days=days))
            assign_events(self.store, self.store.recent(NOW - timedelta(days=days + 1), NOW), NOW - timedelta(days=days))
            if told:
                self.store.record_push(self.store.signal_event(url), NOW - timedelta(days=days))
            llm = Judge({"verdict": "same", "match": 1, "reason": "同一个水印漏洞"}, entities=["Claude watermark"],
                        happened=happened)
            late = self.event(sig("https://b/2", "Claude watermark is easy to bypass"), llm=llm)
            self.check(llm)
            self.assertEqual(self.verdict(late), expected, (told, happened))
            if expected == "new":
                self.assertIn("没推送过", self.store.novelty([late])[late]["reason"])

    def test_same_as_a_live_event_merges_into_it(self):
        first = self.event(sig("https://a/1", "Claude Opus 4.8 launches", hours_ago=10), now=NOW - timedelta(hours=10))
        self.check(Judge())
        second = self.event(sig("https://b/2", "Anthropic's newest model is here"))  # 标题不像，规则聚类并不到一起
        self.assertNotEqual(first, second)
        stats, rebrief = self.check(Judge({"verdict": "same", "match": 1, "reason": "同一次发布"}))
        self.assertEqual((stats["deduped"], rebrief), (1, {first}))
        self.assertEqual(self.store.signal_event("https://b/2"), first)
        self.assertEqual(self.store.briefs([first]), {})  # 多了一篇报道，简报这一轮重写
        self.assertEqual(self.verdict(first), "new")

    def test_only_the_subject_is_searched(self):
        self.old_launch()
        llm = Judge({"verdict": "same", "match": 1}, entities=["CloudOpsBench", "云运维基准"])
        eid = self.event(sig("https://a/bench", "New benchmark tests Claude Opus 4.8 on cloud ops"), llm=llm)
        self.check(llm)  # 标题里提到的 Opus 4.8 只是评测用的模型，不拿来检索
        self.assertEqual((self.verdict(eid), llm.judged), ("new", []))
        self.assertEqual(self.store.briefs([eid])[eid]["entities"], ["CloudOpsBench", "云运维基准"])
        llm = Judge({"verdict": "same", "match": 1}, entities=[])  # 没有明确主角的（行业分析）不检索
        other = self.event(sig("https://b/analysis", "Why Claude Opus 4.8 pricing matters for the market"), llm=llm)
        self.check(llm)
        self.assertEqual((self.verdict(other), llm.judged), ("new", []))

    def test_different_repos_are_never_merged(self):
        first = self.event(sig("https://github.com/a/opus-tools", "opus-tools", hours_ago=10), now=NOW - timedelta(hours=10))
        second = self.event(sig("https://github.com/b/opus-kit", "opus-kit"))
        self.check(Judge({"verdict": "same", "match": 1, "reason": "都是 Opus 工具"}))
        self.assertEqual((self.store.signal_event("https://github.com/b/opus-kit"), self.verdict(second)), (second, "new"))
        self.assertEqual(self.verdict(first), "new")

    def test_update_is_pushed_and_remembers_what_it_follows(self):
        self.old_launch()
        eid = self.event(sig("https://a/price", "Anthropic cuts Claude Opus 4.8 prices"))
        self.check(Judge({"verdict": "update", "match": 1, "reason": "降价"}))
        self.assertEqual((self.verdict(eid), self.store.novelty([eid])[eid]["ref_url"]), ("update", OPUS))
        self.assertEqual([e.id for e in push.select({}, self.store, self.events(), NOW)], [eid])

    def test_unchecked_events_wait_and_failed_checks_retry(self):
        self.old_launch()
        eid = self.event(sig("https://a/1", "Opus 4.8 roundup"))
        self.assertEqual(push.select({}, self.store, self.events(), NOW), [])  # 没查过的先不推
        self.assertEqual(self.check(Judge(OSError("offline")))[0]["checked"], 0)
        self.assertIsNone(self.verdict(eid))
        self.assertEqual(self.check(Judge({"verdict": "same", "match": 9}))[0]["checked"], 1)  # 编号对不上当 new
        self.assertEqual(self.verdict(eid), "new")

    def test_papers_and_shares_are_not_checked(self):
        self.old_launch()
        paper = self.event(sig("https://arxiv.org/abs/2611.00001", "Claude Opus 4.8 as a judge", source="arxiv"))
        shared = self.event(sig("https://x/share", "随手存一下", source="shared"))
        llm = Judge({"verdict": "same", "match": 1})
        self.check(llm)
        self.assertEqual((self.verdict(paper), self.verdict(shared), llm.judged), ("new", None, []))

    def test_briefs_without_entities_search_names_in_the_headline(self):
        self.assertEqual(novelty.names_in("Anthropic 发布 Claude Opus 4.8，价格不变", "OpenAI Ships GPT-5. New vLLM Release"),
                         ["Opus 4.8", "OpenAI", "GPT-5", "vLLM"])  # 英文标题每个词大写开头，不能整句当名字
        self.old_launch()
        eid = self.event(sig("https://a/1", "Claude Opus 4.8 review"))
        self.store.con.execute("update briefs set entities = null")  # 加查旧之前写的简报
        llm = Judge({"verdict": "same", "match": 1})
        self.check(llm)
        self.assertEqual(self.verdict(eid), "stale")

    def test_update_does_not_push_old_news(self):
        self.old_launch()
        self.store.add([sig("https://github.com/acme/mj/blob/1/weekly.md", "Weekly Report: Claude Opus 4.8", hours_ago=0)],
                       now=NOW)
        llm = Judge({"verdict": "same", "match": 1, "reason": "5 月的发布"})
        with mock.patch("catchup.pipeline.push._send", return_value=True) as send, \
                mock.patch("catchup.reader.tool", side_effect=OSError("offline")), \
                mock.patch("catchup.images.cover"):
            stats = update(config(), self.store, NOW, llm)
        self.assertEqual((stats["checked"], stats["stale"], stats["pushed"]), (1, 1, 0))
        send.assert_not_called()


if __name__ == "__main__":
    unittest.main()
