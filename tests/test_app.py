import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock

from catchup.config import Config
from catchup.models import Signal
from catchup.pipeline import brief, push
from catchup.pipeline.events import assign_events, group_events
from catchup.pipeline.ranking import Ranker
from catchup.sources import build_sources
from catchup.store import Store
from catchup.web import app_api

NOW = datetime(2026, 10, 24, 4, tzinfo=timezone.utc)  # 北京时间 12:00，不在免打扰时段
INTERESTS = [{"name": "AI 语音", "weight": 3, "keywords": ["tts", "voice"]}]
SOURCES = {"news": {"type": "feed", "title": "News", "feeds": []}}


def sig(url, title, hours_ago=1, source="news"):
    return Signal(url=url, title=title, source=source, published=NOW - timedelta(hours=hours_ago))


class FakeLLM:
    model = "fake"

    def __init__(self, groups=None, importance=4):
        self.groups, self.importance, self.calls = groups or [], importance, 0

    def json(self, system, user, max_tokens=800):
        self.calls += 1
        if "分组" in system:
            return {"groups": self.groups}
        return {"headline": "Block 发布语音模型 Dessa", "summary": "只用语音训练。", "why": "和你关注的 AI 语音直接相关",
                "importance": self.importance}


def config():
    return Config(root=Path("."), sources=SOURCES, interests=INTERESTS, ranking={"window_hours": 72})


class EventTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(":memory:")

    def add(self, *signals):
        self.store.add(signals, now=NOW)
        return self.store.recent(NOW - timedelta(days=3), NOW)

    def test_ids_are_stable_and_new_reports_join(self):
        recent = self.add(sig("https://a/1", "Block unveils Dessa voice model"))
        self.assertEqual(assign_events(self.store, recent, NOW), 1)
        [first] = group_events(self.store.recent(NOW - timedelta(days=3), NOW))
        recent = self.add(sig("https://b/2", "Block announces Dessa voice model trained on speech"))
        self.assertEqual(assign_events(self.store, recent, NOW), 0)
        [again] = group_events(self.store.recent(NOW - timedelta(days=3), NOW))
        self.assertEqual((again.id, len(again.signals)), (first.id, 2))

    def test_llm_merge_moves_signals_to_smallest_id(self):
        recent = self.add(sig("https://a/1", "Block unveils Dessa"), sig("https://c/3", "Totally different TTS news"))
        assign_events(self.store, recent, NOW)
        events = group_events(self.store.recent(NOW - timedelta(days=3), NOW))
        ids = sorted(e.id for e in events)
        merged = brief.merge_duplicates(self.store, events, FakeLLM(groups=[[str(i) for i in ids], [999]]), {})
        self.assertEqual(merged, 1)
        [e] = group_events(self.store.recent(NOW - timedelta(days=3), NOW))
        self.assertEqual((e.id, len(e.signals)), (ids[0], 2))

    def test_briefs_skip_unchanged_events(self):
        assign_events(self.store, self.add(sig("https://a/1", "Block unveils Dessa voice")), NOW)
        events = group_events(self.store.recent(NOW - timedelta(days=3), NOW))
        llm = FakeLLM()
        self.assertEqual(brief.write_briefs(self.store, events, INTERESTS, llm, {}, NOW), 1)
        self.assertEqual(brief.write_briefs(self.store, events, INTERESTS, llm, {}, NOW), 0)
        self.assertEqual(llm.calls, 1)
        self.assertEqual(self.store.briefs([events[0].id])[events[0].id]["importance"], 4)


class PushTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(":memory:")
        self.store.add([sig(f"https://github.com/org{i}/tts", f"Voice TTS model {i} released") for i in range(5)], now=NOW)
        assign_events(self.store, self.store.recent(NOW - timedelta(days=3), NOW), NOW)
        ranker = Ranker.from_config(INTERESTS, {})
        self.events = ranker.rank(group_events(self.store.recent(NOW - timedelta(days=3), NOW)), NOW)
        brief.write_briefs(self.store, self.events, INTERESTS, FakeLLM(), {}, NOW)

    def test_caps_quiet_hours_and_feedback(self):
        opts = {"min_score": 0, "per_run": 2, "per_day": 3}
        self.assertEqual(len(push.select(opts, self.store, self.events, NOW)), 2)
        self.assertEqual(push.select(opts, self.store, self.events, NOW.replace(hour=16)), [])  # 北京时间 0 点
        for e in self.events[:3]:
            self.store.record_push(e.id, NOW)
        self.assertEqual(push.select(opts, self.store, self.events, NOW), [])  # 24 小时上限
        self.store.con.execute("delete from pushes")
        self.store.set_feedback(self.events[0].id, "not_needed", NOW)
        picked = push.select({**opts, "per_run": 9, "per_day": 9}, self.store, self.events, NOW)
        self.assertNotIn(self.events[0].id, [e.id for e in picked])

    def test_low_importance_is_not_pushed(self):
        self.store.con.execute("update briefs set importance = 3")
        self.assertEqual(push.select({"min_score": 0}, self.store, self.events, NOW), [])

    def test_deliver_records_only_successes(self):
        with mock.patch.dict("os.environ", {"FEED_URL": "http://hub/publish", "FEED_TOKEN": "t"}), \
                mock.patch("catchup.http.post_json", side_effect=[{"id": 1}, OSError("down")]) as post:
            self.assertEqual(push.deliver(self.store, self.events[:2], NOW), 1)
        self.assertTrue(post.call_args_list[0].args[1]["click"].startswith("catchup://event/"))
        self.assertEqual(self.store.pushed(), {self.events[0].id})


class AppApiTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(":memory:")
        self.store.add([sig("https://a/1", "Block unveils Dessa voice model")], now=NOW)
        assign_events(self.store, self.store.recent(NOW - timedelta(days=3), NOW), NOW)

    def call(self, method, path, token=None, body=None):
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        return app_api.handle(config(), self.store, method, path, headers, body)

    def test_first_device_auto_approved_then_pending(self):
        status, first = self.call("POST", "/v1/devices", body={"name": "V2505A"})
        self.assertEqual((status, first["approved"]), (200, True))
        _, second = self.call("POST", "/v1/devices", body={"name": "other"})
        self.assertFalse(second["approved"])
        self.assertEqual(self.call("GET", "/v1/feed", second["token"])[0], 403)
        self.assertEqual(self.call("GET", "/v1/feed")[0], 401)
        self.assertEqual(self.call("GET", "/v1/me", second["token"])[1]["approved"], False)

    def test_feed_detail_and_feedback(self):
        token = self.call("POST", "/v1/devices", body={"name": "p"})[1]["token"]
        with mock.patch("catchup.web.app_api.datetime") as dt:
            dt.now.return_value = NOW
            status, feed = self.call("GET", "/v1/feed", token)
            [event] = feed["events"]
            self.assertEqual((status, event["ai"], event["source_count"]), (200, False, 1))
            status, detail = self.call("GET", f"/v1/events/{event['id']}", token)
            self.assertEqual((status, detail["signals"][0]["url"]), (200, "https://a/1"))
            self.assertEqual(self.call("POST", f"/v1/events/{event['id']}/feedback", token, {"value": "bad"})[0], 400)
            self.call("POST", f"/v1/events/{event['id']}/feedback", token, {"value": "not_needed"})
            self.assertEqual(self.call("GET", "/v1/feed", token)[1]["events"], [])
            self.assertEqual(self.call("GET", "/v1/events/999", token)[0], 404)


class DeepSeekSourceTest(unittest.TestCase):
    def test_search_filters_by_score(self):
        src = build_sources({"ds": {"type": "deepseek_search", "queries": [{"name": "q", "query": "x"}]}})["ds"]
        results = {"results": [{"url": "https://a", "title": "A", "snippet": "s", "site_name": "新浪", "rerank_score": 1.2},
                               {"url": "https://b", "title": "B", "rerank_score": 0.3}]}
        with mock.patch.dict("os.environ", {"DEEPSEEK_API_KEY": "k"}), \
                mock.patch("catchup.http.post_json", return_value=results):
            [s] = src.fetch()
        self.assertEqual((s.url, s.channel), ("https://a", "新浪"))

    def test_page_watch_baseline_then_added_lines(self):
        with tempfile.TemporaryDirectory() as d:
            src = build_sources({"pw": {"type": "page_watch", "state_dir": d,
                                        "pages": [{"name": "日志", "url": "https://p"}]}})["pw"]
            pages = [{"content": "a\\.b\nold"}, {"content": "a\\.b\nold"}, {"content": "a\\.b\nold\nnew line"}]
            with mock.patch.dict("os.environ", {"DEEPSEEK_API_KEY": "k"}), \
                    mock.patch("catchup.http.post_json", side_effect=pages):
                self.assertEqual(src.fetch(), [])   # 首次只存基线
                self.assertEqual(src.fetch(), [])   # 没变化
                [s] = src.fetch()
        self.assertEqual((s.title, s.summary, s.tier), ("日志 有更新", "new line", "official"))
        self.assertTrue(s.url.startswith("https://p#"))


if __name__ == "__main__":
    unittest.main()
