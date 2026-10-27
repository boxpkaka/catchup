import json
import re
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock

from catchup.config import Config
from catchup.models import Signal
from catchup.pipeline import brief, push, reports, translate
from catchup.pipeline.events import assign_events, group_events
from catchup.pipeline.ranking import Ranker
from catchup.sources import build_sources
from catchup.store import Store
from catchup.web import app_api
from catchup.util import to_iso

NOW = datetime(2026, 10, 24, 4, tzinfo=timezone.utc)  # 北京时间 12:00，不在免打扰时段
INTERESTS = [{"name": "AI 语音", "weight": 3, "keywords": ["tts", "voice"]}]
SOURCES = {"news": {"type": "feed", "title": "News", "feeds": []}}


def sig(url, title, hours_ago=1, source="news"):
    return Signal(url=url, title=title, source=source, published=NOW - timedelta(hours=hours_ago))


class FakeLLM:
    model = "fake"

    def __init__(self, groups=None, importance=4, memory=None, reply="Dessa 只用语音训练 [1]。", progress=None):
        self.groups, self.importance, self.calls = groups or [], importance, 0
        self.memory = memory or {"add": [], "update": [], "remove": []}
        self.reply, self.progress, self.prompts = reply, progress, []

    def json(self, system, user, max_tokens=800):
        self.calls += 1
        self.prompts.append(user)
        if "的编辑，给一名 AI 从业者写" in system:  # 日报、周报、月报
            first = re.findall(r"\[E(\d+)\]", user)[0]
            return {"highlights": [{"text": "Block 发布 Dessa", "events": [first]}], "trends": [], "ongoing": [],
                    "sections": [{"name": "AI 语音", "items": [{"event": f"E{first}", "text": "只用语音训练"}]}]}
        if "分组" in system:
            return {"groups": self.groups}
        if system.startswith("为下面这条"):
            return {"keywords": ["funasr", "asr"]}
        if system.startswith("你在维护"):
            return self.memory
        if "实质进展" in system:
            return {"substantive": self.progress is not None, "update": self.progress or ""}
        if system.startswith("把输入"):
            return {"items": ["译：" + t for t in json.loads(user)]}
        return {"headline": "Block 发布语音模型 Dessa", "summary": "只用语音训练。",
                "importance": self.importance, "questions": ["能本地跑吗？", "和 Moshi 比如何？"], "points": ["全双工"]}

    def stream(self, messages):
        self.prompts.append(messages[0]["content"])
        yield from (self.reply[:4], self.reply[4:])


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
        self.assertEqual(brief.write_briefs(self.store, events, "关注语音", llm, {}, NOW), 1)
        self.assertEqual(brief.write_briefs(self.store, events, "关注语音", llm, {}, NOW), 0)
        self.assertEqual(llm.calls, 1)
        saved = self.store.briefs([events[0].id])[events[0].id]
        self.assertEqual((saved["importance"], len(saved["questions"])), (4, 2))
        self.assertEqual(brief.write_briefs(self.store, events, "关注语音", llm, {}, NOW, memory_rev=1), 1)  # 记忆变了就重写

    def test_briefs_are_rewritten_once_the_original_is_read(self):
        assign_events(self.store, self.add(sig("https://a/1", "Block unveils Dessa voice")), NOW)
        events = group_events(self.store.recent(NOW - timedelta(days=3), NOW))
        eid, llm = events[0].id, FakeLLM()
        brief.write_briefs(self.store, events, "关注语音", llm, {}, NOW)
        self.assertIsNone(self.store.briefs([eid])[eid]["points"])  # 还没读到原文，先用报道摘要写
        self.store.save_page("https://a/1", "Dessa 是只用语音对话训练的全双工模型。", NOW)
        self.assertEqual(brief.write_briefs(self.store, events, "关注语音", llm, {}, NOW), 1)
        self.assertIn("只用语音对话训练", llm.prompts[-1])
        self.assertEqual(self.store.briefs([eid])[eid]["points"], ["全双工"])
        self.assertEqual(brief.write_briefs(self.store, events, "关注语音", llm, {}, NOW), 0)


class PushTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(":memory:")
        self.store.add([sig(f"https://github.com/org{i}/tts", f"Voice TTS model {i} released") for i in range(5)], now=NOW)
        assign_events(self.store, self.store.recent(NOW - timedelta(days=3), NOW), NOW)
        ranker = Ranker.from_config(INTERESTS, {})
        self.events = ranker.rank(group_events(self.store.recent(NOW - timedelta(days=3), NOW)), NOW)
        brief.write_briefs(self.store, self.events, "关注语音", FakeLLM(), {}, NOW)

    def test_quiet_hours_pushed_and_feedback(self):
        opts = {}
        self.assertEqual(len(push.select(opts, self.store, self.events, NOW)), 5)  # 过了门槛的都推，不限条数
        self.assertEqual(push.select(opts, self.store, self.events, NOW.replace(hour=16)), [])  # 北京时间 0 点
        for e in self.events[:2]:
            self.store.record_push(e.id, NOW - timedelta(minutes=30))
        self.assertEqual(len(push.select(opts, self.store, self.events, NOW)), 3)  # 推过的不再推
        self.store.set_feedback(self.events[2].id, "not_needed", NOW)
        picked = push.select(opts, self.store, self.events, NOW)
        self.assertNotIn(self.events[2].id, [e.id for e in picked])

    def test_low_importance_is_not_pushed(self):
        self.store.con.execute("update briefs set importance = 2")  # 边缘信息不推，一般动态就推
        self.assertEqual(push.select({}, self.store, self.events, NOW), [])

    def test_deliver_records_only_successes(self):
        with mock.patch.dict("os.environ", {"FEED_URL": "http://hub/publish", "FEED_TOKEN": "t"}), \
                mock.patch("catchup.http.post_json", side_effect=[{"id": 1}, OSError("down")]) as post:
            self.assertEqual(push.deliver(self.store, self.events[:2], NOW), 1)
        self.assertTrue(post.call_args_list[0].args[1]["click"].startswith("catchup://event/"))
        self.assertEqual(set(self.store.pushed()), {self.events[0].id})


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
        with mock.patch("catchup.web.app_api.datetime") as dt, mock.patch("catchup.chat.prefetch"):
            dt.now.return_value = NOW
            status, feed = self.call("GET", "/v1/feed", token)
            [event] = feed["events"]
            self.assertEqual((status, event["ai"], event["source_count"], event["pushed_at"]), (200, False, 1, None))
            self.store.record_push(event["id"], NOW)
            self.assertEqual(self.call("GET", "/v1/feed", token)[1]["events"][0]["pushed_at"], to_iso(NOW))
            status, detail = self.call("GET", f"/v1/events/{event['id']}", token)
            self.assertEqual((status, detail["signals"][0]["url"]), (200, "https://a/1"))
            self.assertEqual(self.call("POST", f"/v1/events/{event['id']}/feedback", token, {"value": "bad"})[0], 400)
            self.assertEqual(self.call("POST", f"/v1/events/{event['id']}/feedback", token,
                                       {"value": "useful", "reason": "source"})[0], 400)
            self.call("POST", f"/v1/events/{event['id']}/feedback", token, {"value": "not_needed", "reason": "topic"})
            self.assertEqual(self.call("GET", "/v1/feed", token)[1]["events"], [])
            [hidden] = self.call("GET", "/v1/dismissed", token)[1]["events"]
            self.assertEqual((hidden["id"], hidden["reason"]), (event["id"], "不想看这类"))
            self.call("POST", f"/v1/events/{event['id']}/feedback", token, {"value": None})  # 恢复
            self.assertEqual([a[2:4] for a in self.store.actions(NOW - timedelta(days=1))],
                             [("not_needed", "topic"), ("restore", None)])
            # 不想看这个来源：当场记下，撤回时一起删
            with mock.patch("catchup.personal.rebrief_async") as rebrief:
                _, r = self.call("POST", f"/v1/events/{event['id']}/feedback", token,
                                 {"value": "not_needed", "reason": "source"})
                self.assertEqual(r["memory"]["added"][0]["text"], f"你不想看「{event['source']}」的内容")
                self.assertEqual(self.store.memories()[0]["kind"], "avoid_source")
                _, r = self.call("POST", f"/v1/events/{event['id']}/feedback", token, {"value": None})
                self.assertEqual((len(r["memory"]["removed"]), self.store.memories()), (1, []))
                self.assertEqual(rebrief.call_count, 2)
            self.assertEqual(len(self.call("GET", "/v1/feed", token)[1]["events"]), 1)
            self.assertEqual(self.call("GET", "/v1/dismissed", token)[1]["events"], [])
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


class PersonalTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(":memory:")

    def test_apply_ops_add_update_remove(self):
        from catchup import personal
        ops = {"add": [{"kind": "task", "text": "你在评估 FunASR", "keywords": ["funasr"]},
                       {"kind": "bogus", "text": "x"}]}
        changes = personal.apply_ops(self.store, ops, "对话", NOW)
        [mem] = self.store.memories()
        self.assertEqual((len(changes["added"]), mem["kind"], personal.memory_rev(self.store)), (1, "task", 1))
        self.assertIsNotNone(mem["expires"])  # 正在做的事会过期
        self.assertEqual(personal.apply_ops(self.store, ops, "对话", NOW)["added"], [])  # 同样的话不重复记
        personal.apply_ops(self.store, {"update": [{"id": str(mem["id"]), "text": "你在用 FunASR 替换 Whisper"}],
                                        "remove": []}, "对话", NOW)
        self.assertEqual(self.store.memories()[0]["text"], "你在用 FunASR 替换 Whisper")
        personal.apply_ops(self.store, {"remove": [mem["id"]]}, "对话", NOW)
        self.assertEqual(self.store.memories(), [])

    def test_memory_drives_ranking_and_can_be_turned_off(self):
        from catchup import personal
        self.store.add([sig("https://a/1", "FunASR 2.0 released"), sig("https://b/2", "Crypto coin voice launch")],
                       now=NOW)
        assign_events(self.store, self.store.recent(NOW - timedelta(days=3), NOW), NOW)
        personal.apply_ops(self.store, {"add": [{"kind": "task", "text": "你在评估 FunASR", "keywords": ["funasr"]},
                                                {"kind": "avoid", "text": "你不想看加密货币", "keywords": ["crypto"]}]},
                           "对话", NOW)
        cfg = config()
        events = {e.lead.url: e for e in personal.build_ranker(cfg, self.store, NOW).rank(
            group_events(self.store.recent(NOW - timedelta(days=3), NOW)), NOW)}
        self.assertIn("和你说过的「你在评估 FunASR」有关", events["https://a/1"].reasons)
        self.assertTrue(any("不想看" in r for r in events["https://b/2"].reasons))
        self.assertIn("正在做：你在评估 FunASR", personal.focus(cfg, self.store, NOW))
        self.store.set_setting("personalize", "0")
        plain = personal.build_ranker(cfg, self.store, NOW).rank(
            group_events(self.store.recent(NOW - timedelta(days=3), NOW)), NOW)
        self.assertFalse(any("你说过" in r for e in plain for r in e.reasons))


    def test_learning_uses_actions_and_only_touches_its_own_memories(self):
        from catchup import personal
        self.store.add([sig("https://a/1", "Moshi 2 full-duplex release"), sig("https://b/2", "Crypto coin launch"),
                        sig("https://c/3", "FunASR 2.0 released"), sig("https://d/4", "Qwen3-TTS open weights")], now=NOW)
        assign_events(self.store, self.store.recent(NOW - timedelta(days=3), NOW), NOW)
        ids = {e.lead.url: e.id for e in group_events(self.store.recent(NOW - timedelta(days=3), NOW))}
        a, b, c, d = (ids[u] for u in ("https://a/1", "https://b/2", "https://c/3", "https://d/4"))
        for event_id, kind, value in [(b, "not_needed", "topic"), (c, "not_needed", None), (c, "restore", None),
                                      (d, "not_needed", "known"), (a, "follow", None), (a, "ask", None)]:
            self.store.add_action(event_id, kind, value, NOW)
        add = lambda kind, text, origin: personal.apply_ops(self.store, {"add": [
            {"kind": kind, "text": text, "keywords": ["x"]}]}, origin, NOW)["added"][0]["id"]
        said, learned = add("interest", "你关注全双工", "对话"), add("avoid", "你不想看 FunASR", personal.FEEDBACK_ORIGIN)
        llm = mock.Mock()
        llm.json.return_value = {"add": [
            {"kind": "avoid", "text": "你不想看加密货币", "keywords": ["crypto"], "evidence": [1, 4]},
            {"kind": "interest", "text": "你关注 Moshi", "keywords": ["moshi"], "evidence": [4]},   # 单条行为，不下结论
            {"kind": "interest", "text": "你关注 FunASR", "keywords": ["funasr"], "evidence": [2, 3]}],  # 都不算证据
            "update": [{"id": said, "text": "改掉"}], "remove": [said, learned]}
        changes = personal.learn_from_feedback(config(), self.store, llm, NOW)
        prompt = llm.json.call_args[0][1]
        for tag in ("1. [不需要：不想看这类]", "2. [误点了不需要，已撤回]", "3. [不需要：已经知道了]", "4. [追踪，提问 1 次]"):
            self.assertIn(tag, prompt)
        self.assertEqual([m["text"] for m in changes["added"]], ["你不想看加密货币"])
        self.assertEqual(([m["id"] for m in changes["removed"]], changes["updated"]), ([learned], []))  # 对话里说的不动
        personal.learn_from_feedback(config(), self.store, llm, NOW)
        self.assertEqual(llm.json.call_count, 1)  # 没有新行为不再归纳

    def test_avoid_source_matches_source_name_only(self):
        from catchup.pipeline.ranking import Interest
        self.store.add([Signal(url="https://r/1", title="New TTS model", source="reddit", channel="LocalLLaMA",
                               published=NOW - timedelta(hours=1))], now=NOW)
        assign_events(self.store, self.store.recent(NOW - timedelta(days=3), NOW), NOW)
        [event] = group_events(self.store.recent(NOW - timedelta(days=3), NOW))
        mute = [Interest("x", 1, ["localllama"], said="你不想看「LocalLLaMA」的内容")]
        self.assertFalse(any("不想看" in r for r in Ranker([Interest("AI 语音", 3, ["tts"])], avoid=mute)
                             .score(event, NOW).reasons))  # “不想看”的话题不匹配来源名
        self.assertIn("你不想看「LocalLLaMA」的内容，已降低优先级",
                      Ranker([Interest("AI 语音", 3, ["tts"])], avoid_sources=mute).score(event, NOW).reasons)


class ChatTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(":memory:")
        self.store.add([sig("https://a/1", "Block unveils Dessa voice model")], now=NOW)
        assign_events(self.store, self.store.recent(NOW - timedelta(days=3), NOW), NOW)
        self.eid = group_events(self.store.recent(NOW - timedelta(days=3), NOW))[0].id
        brief.write_briefs(self.store, group_events(self.store.recent(NOW - timedelta(days=3), NOW)), "", FakeLLM(), {},
                           NOW)

    def run_chat(self, llm, body):
        from catchup import chat
        with mock.patch("catchup.reader.tool", return_value={"content": "Dessa 原文：只用语音训练。"}), \
                mock.patch("catchup.personal.rebrief_async") as rebrief:
            out = list(chat.chat(config(), self.store, llm, body, NOW))
        return out, rebrief

    def test_event_chat_reads_sources_streams_and_remembers(self):
        llm = FakeLLM(memory={"add": [{"kind": "task", "text": "你在做全双工语音", "keywords": ["full-duplex"]}]})
        out, rebrief = self.run_chat(llm, {"event_id": self.eid, "message": "我在做全双工语音，这个能借鉴吗？"})
        types = [e["type"] for e in out]
        self.assertEqual(types[:2], ["thread", "status"])
        self.assertEqual("".join(e["text"] for e in out if e["type"] == "delta"), llm.reply)
        self.assertEqual(out[-2]["added"][0]["text"], "你在做全双工语音")
        self.assertEqual(types[-1], "done")
        self.assertIn("Dessa 原文", llm.prompts[0])  # 回答基于抓取的原文
        rebrief.assert_called_once()
        thread = self.store.latest_thread(self.eid)
        self.assertEqual([m["role"] for m in self.store.messages(thread["id"])], ["user", "assistant"])
        # 继续同一个对话：不再重复读原文，历史会带上
        out, _ = self.run_chat(FakeLLM(), {"thread_id": thread["id"], "message": "那延迟呢？"})
        self.assertNotIn("status", [e["type"] for e in out])
        self.assertEqual(len(self.store.messages(thread["id"])), 4)

    def test_failed_fetch_retried_later(self):
        from catchup import chat
        signals = self.store.event_signals(self.eid)
        with mock.patch("catchup.reader.tool", side_effect=OSError("504")) as tool:
            self.assertEqual(chat._read(self.store, signals, NOW, timeout=5), {"https://a/1": ""})
            chat._read(self.store, signals, NOW + timedelta(hours=1), timeout=5)
            self.assertEqual(tool.call_count, 1)  # 刚失败过，先用摘要，不重抓
            self.assertTrue(chat.needs_reading(self.store, signals, NOW + chat.FAIL_RETRY))

    def test_global_ask_cites_events(self):
        out, _ = self.run_chat(FakeLLM(reply=f"Block 发布了 Dessa [E{self.eid}]。"), {"message": "最近有什么语音模型？"})
        refs = [e for e in out if e["type"] == "refs"]
        self.assertEqual(refs[0]["events"][0]["id"], self.eid)

    def test_follow_finds_substantive_updates(self):
        from catchup.pipeline import follow
        self.store.set_follow(self.eid, True, 1, NOW)
        found = {"results": [{"url": "https://c/3", "title": "Dessa weights released", "rerank_score": 1.5}]}
        with mock.patch("catchup.pipeline.follow.tool", return_value=found):
            updates = follow.run(self.store, FakeLLM(progress="Block 开源了 Dessa 权重"), NOW)
        self.assertEqual(updates[0]["text"], "Block 开源了 Dessa 权重")
        self.assertEqual(len(self.store.event_signals(self.eid)), 2)  # 搜到的报道并入了这个事件
        with mock.patch("catchup.pipeline.follow.tool", return_value=found) as tool:
            self.assertEqual(follow.run(self.store, FakeLLM(progress="x"), NOW), [])  # 没有新报道就不再判断
            follow.run(self.store, FakeLLM(progress="x"), NOW + follow.SEARCH_EVERY)
        self.assertEqual(tool.call_count, 1)  # 刚搜过的一小时内不再搜


class MorningDigestTest(unittest.TestCase):
    def test_overnight_events_become_one_push(self):
        store = Store(":memory:")
        store.add([sig(f"https://github.com/o{i}/tts", f"Voice TTS {i}") for i in range(3)], now=NOW)
        assign_events(store, store.recent(NOW - timedelta(days=3), NOW), NOW)
        events = Ranker.from_config(INTERESTS, {}).rank(group_events(store.recent(NOW - timedelta(days=3), NOW)), NOW)
        brief.write_briefs(store, events, "", FakeLLM(), {}, NOW)
        at8 = NOW.replace(hour=0, minute=37)  # 北京时间 8:37
        picked = push.select({}, store, events, at8)
        self.assertEqual(len(picked), 3)
        with mock.patch.dict("os.environ", {"FEED_URL": "u", "FEED_TOKEN": "t"}), \
                mock.patch("catchup.http.post_json") as post:
            self.assertEqual(push.deliver(store, picked, at8, {}), 3)
        post.assert_called_once()
        self.assertEqual(post.call_args.args[1]["click"], "catchup://home")
        self.assertEqual(post.call_args.args[1]["title"], "Block 发布语音模型 Dessa")  # 最重要的那件做标题
        self.assertEqual(len(post.call_args.args[1]["message"].splitlines()), 2)  # 其余两件列在正文
        self.assertFalse(push.morning({}, store, at8 + timedelta(minutes=10)))  # 这一小时后面几轮不再发早报


class AppApiMoreTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(":memory:")
        self.store.add([sig("https://a/1", "Block unveils Dessa voice model")], now=NOW)
        assign_events(self.store, self.store.recent(NOW - timedelta(days=3), NOW), NOW)
        brief.write_briefs(self.store, group_events(self.store.recent(NOW - timedelta(days=3), NOW)), "", FakeLLM(), {},
                           NOW)
        self.token = app_api.register(self.store, "p", NOW)[1]["token"]
        self.patches = [mock.patch("catchup.web.app_api.datetime"), mock.patch("catchup.web.app_api.from_config",
                                                                               return_value=FakeLLM()),
                        mock.patch("catchup.personal.rebrief_async"), mock.patch("catchup.chat.prefetch")]
        dt = self.patches[0].start()
        dt.now.return_value, dt.fromtimestamp = NOW, datetime.fromtimestamp
        for p in self.patches[1:]:
            p.start()

    def tearDown(self):
        for p in self.patches:
            p.stop()

    def call(self, method, path, body=None, query=None):
        return app_api.handle(config(), self.store, method, path, {"Authorization": f"Bearer {self.token}"}, body, query)

    def test_memories_crud_and_personalize_switch(self):
        self.assertEqual(self.call("POST", "/v1/memories", {"kind": "task", "text": "你在评估 FunASR"})[0], 200)
        [m] = self.call("GET", "/v1/memories")[1]["memories"]
        self.assertEqual((m["kind_label"], m["days_left"], m["origin"]), ("正在做", 21, "你手动添加"))
        self.call("POST", f"/v1/memories/{m['id']}", {"text": "你在用 FunASR"})
        self.assertEqual(self.store.memories()[0]["keywords"], ["funasr", "asr"])
        self.call("POST", f"/v1/memories/{m['id']}", {"delete": True})
        self.assertEqual(self.call("GET", "/v1/memories")[1]["memories"], [])
        self.assertFalse(self.call("POST", "/v1/settings", {"personalize": False})[1]["personalize"])

    def test_share_link_becomes_event_and_is_never_pushed_back(self):
        from catchup.pipeline.update import ranked
        dessa = self.call("GET", "/v1/feed")[1]["events"][0]["id"]
        page = {"title": "Qwen3-TTS 技术报告", "content": "导航\n\nQwen3-TTS 是一个支持流式合成的开源语音合成模型，首包延迟 97 毫秒，支持十种语言。"}
        with mock.patch("catchup.reader.tool", return_value=page):
            status, r = self.call("POST", "/v1/shares", {"text": "看看这个 https://qwen.ai/tts-report。"})
        [s] = self.store.event_signals(r["event_id"])
        self.assertEqual((status, s.url, s.source, s.title), (200, "https://qwen.ai/tts-report", "shared", "Qwen3-TTS 技术报告"))
        self.assertIn("流式合成", s.summary)
        self.assertEqual(self.store.page(s.url)[0], page["content"])  # 原文已缓存，之后提问不用再读
        self.assertIn(r["event_id"], self.store.briefs([r["event_id"]]))
        self.assertEqual(self.call("POST", "/v1/shares", {"text": s.url})[1]["event_id"], r["event_id"])  # 再分享一次
        self.assertEqual([a[2] for a in self.store.actions(NOW - timedelta(days=1))], ["share", "share"])
        picked = push.select({"min_importance": 0}, self.store, ranked(config(), self.store, NOW)[1], NOW)
        self.assertEqual([e.id for e in picked], [dessa])  # 自己分享的不会再推给自己
        self.assertEqual(self.call("POST", "/v1/shares", {"text": "没有链接"})[0], 400)

    def test_unanswered_question_is_watched_then_answered_in_thread(self):
        from catchup import chat
        from catchup.pipeline import answers
        eid = self.call("GET", "/v1/feed")[1]["events"][0]["id"]
        llm = FakeLLM(memory={"add": [], "pending": {"text": "Dessa 什么时候开放权重？", "keywords": ["dessa", "weights"]}})
        with mock.patch("catchup.reader.tool", return_value={"content": "原文"}):
            out = list(chat.chat(config(), self.store, llm, {"event_id": eid, "message": "权重会开源吗？"}, NOW))
        self.assertEqual([e["text"] for e in out if e["type"] == "watch"], ["Dessa 什么时候开放权重？"])
        later = NOW + timedelta(hours=2)
        self.store.add([sig("https://b/2", "Dessa weights now on Hugging Face")], now=later)
        judge = mock.Mock()
        judge.json.return_value = {"answered": True, "answer": "已在 Hugging Face 开放权重。", "source": 1}
        with mock.patch("catchup.pipeline.answers.tool", return_value={"results": []}):
            self.assertEqual(len(answers.run(self.store, judge, later)), 1)
        thread = self.store.latest_thread(eid)
        self.assertIn("现在有答案了", self.store.messages(thread["id"])[-1]["content"])  # 答案写回原来的对话
        self.assertEqual(self.call("GET", "/v1/feed")[1]["answers"][0]["thread_id"], thread["id"])
        with mock.patch("catchup.pipeline.push._send", return_value=True) as send:
            self.assertEqual((push.deliver_answers({}, self.store, later), push.deliver_answers({}, self.store, later)),
                             (1, 0))  # 只推一次
        self.assertEqual(send.call_args[0][2], f"catchup://thread/{thread['id']}")
        qid = self.store.add_question(thread["id"], eid, "价格多少？", ["price"], NOW)
        self.call("POST", f"/v1/questions/{qid}", {"drop": True})
        self.assertEqual([q["status"] for q in self.call("GET", "/v1/memories")[1]["questions"]], ["answered"])

    def test_feed_says_what_was_skipped(self):
        self.store.add([Signal(url="https://c/3", title="UK invests in biosecurity lab", source="news", heat=1.5,
                               published=NOW - timedelta(hours=2))], now=NOW)
        assign_events(self.store, self.store.recent(NOW - timedelta(days=3), NOW), NOW)
        [s] = self.call("GET", "/v1/feed")[1]["skipped"]  # 和你有关的 Dessa 不在里面
        self.assertEqual((s["headline"], s["why"]), ("UK invests in biosecurity lab", "和你的关注无关"))
        events = group_events(self.store.recent(NOW - timedelta(days=3), NOW))
        # 排在简报范围之外但正热门的，也让模型判断重要性
        self.assertEqual([e.lead.url for e in brief.candidates(events, 0, build_sources(SOURCES))], ["https://c/3"])
        # 翻译之后，没写简报的标题和详情里的来源标题都显示中文；已经翻过的不再翻
        self.assertEqual(translate.run(self.store, FakeLLM(), events), 2)
        self.assertEqual(translate.run(self.store, FakeLLM(), events), 0)
        [s] = self.call("GET", "/v1/feed")[1]["skipped"]
        self.assertEqual(s["headline"], "译：UK invests in biosecurity lab")
        detail = self.call("GET", f"/v1/events/{s['id']}")[1]
        self.assertEqual((detail["headline"], detail["signals"][0]["title"]),
                         ("译：UK invests in biosecurity lab", "译：UK invests in biosecurity lab"))

    def test_feed_is_a_timeline_of_news(self):
        self.store.add([Signal(url="https://c/3", title="UK invests in biosecurity lab", source="news", heat=1.5,
                               published=NOW - timedelta(hours=2))], now=NOW)
        assign_events(self.store, self.store.recent(NOW - timedelta(days=3), NOW), NOW)
        [dessa] = self.call("GET", "/v1/feed")[1]["events"][:1]
        uk = next(e for e in group_events(self.store.recent(NOW - timedelta(days=3), NOW)) if e.lead.url == "https://c/3")
        ids = lambda **q: [e["id"] for e in self.call("GET", "/v1/feed", query=q)[1]["events"]]
        self.assertEqual(ids(), [dessa["id"], uk.id])  # 按见报时间从新到旧；还没写简报的前排事件先显示
        self.assertEqual(ids(before=to_iso(NOW - timedelta(minutes=90))), [uk.id])  # 往前翻页
        brief.write_briefs(self.store, [uk], "", FakeLLM(importance=2), {}, NOW)
        self.assertEqual(ids(), [dessa["id"]])  # 判定为边缘信息的不显示
        self.store.record_push(uk.id, NOW)
        self.assertEqual(ids(), [dessa["id"], uk.id])  # 推送过的一定在信息流里，方便划掉

    def test_follow_rating_and_detail(self):
        eid = self.call("GET", "/v1/feed")[1]["events"][0]["id"]
        self.call("POST", f"/v1/events/{eid}/follow", {"on": True})
        self.call("POST", f"/v1/events/{eid}/rating", {"would_miss": True})
        feed = self.call("GET", "/v1/feed")[1]
        self.assertEqual((feed["following"][0]["id"], feed["events"][0]["following"]), (eid, True))
        detail = self.call("GET", f"/v1/events/{eid}")[1]
        self.assertEqual((detail["would_miss"], detail["questions"][0], detail["thread"]), (True, "能本地跑吗？", None))


class ReportsTest(unittest.TestCase):
    def test_daily_then_weekly_pushed_once(self):
        store = Store(":memory:")
        store.add([sig("https://a/1", "Block unveils Dessa voice model", hours_ago=20)], now=NOW - timedelta(hours=20))
        assign_events(store, store.recent(NOW - timedelta(days=3), NOW), NOW)
        brief.write_briefs(store, group_events(store.recent(NOW - timedelta(days=3), NOW)), "", FakeLLM(), {}, NOW)
        llm = FakeLLM()
        self.assertEqual(reports.run(config(), store, llm, NOW.replace(day=23, hour=20)), [])  # 北京时间 4 点还不写
        self.assertEqual(reports.run(config(), store, llm, NOW), ["daily:2026-10-23"])  # 10月24日中午写昨天的日报
        self.assertEqual(reports.run(config(), store, llm, NOW), [])
        monday = NOW + timedelta(days=2)  # 10月26日周一中午：写上周的周报；九月还没开始采集，不写月报
        self.assertEqual(reports.run(config(), store, llm, monday), ["weekly:2026-10-19"])
        with mock.patch.dict("os.environ", {"FEED_URL": "u", "FEED_TOKEN": "t"}), \
                mock.patch("catchup.http.post_json") as post:
            self.assertEqual(reports.deliver({}, store, monday), 1)  # 只推周报，日报不推
            self.assertEqual(reports.deliver({}, store, monday), 0)
        self.assertEqual(post.call_args.args[1]["title"], "周报 · 10月19日–25日")
        self.assertEqual(post.call_args.args[1]["click"], "catchup://report/weekly/2026-10-19")
        week = app_api.report(config(), store, "weekly", None, monday)
        self.assertEqual((week["label"], week["highlights"][0]["events"][0]["headline"], week["prev"]),
                         ("10月19日–25日", "Block 发布语音模型 Dessa", None))
        self.assertIn("10月23日 周五的日报", llm.prompts[-1])  # 周报由日报汇总
        self.assertEqual(app_api.report(config(), store, "daily", "2026-10-23", monday)["sections"][0]["items"][0]["text"],
                         "只用语音训练")
        self.assertEqual(app_api.report(config(), store, "monthly", None, monday)["upcoming"], "11月1日")
