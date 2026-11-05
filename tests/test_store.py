import unittest
from datetime import datetime, timedelta, timezone

from catchup.models import Signal
from catchup.store import MIGRATIONS, Store
from catchup.util import to_iso

NOW = datetime(2026, 10, 24, 12, tzinfo=timezone.utc)


class StoreTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(":memory:")

    def test_migrated(self):
        version = self.store.con.execute("pragma user_version").fetchone()[0]
        self.assertEqual(version, len(MIGRATIONS))

    def test_add_dedups_and_counts_new(self):
        s = Signal(url="https://a", title="A", source="x", published=NOW, extra={"k": 1})
        self.assertEqual(self.store.add([s, s], now=NOW), 1)
        self.assertEqual(self.store.add([s], now=NOW), 0)

    def test_recent_window_and_missing_published(self):
        old = Signal(url="https://old", title="old", source="x", published=NOW - timedelta(days=10))
        undated = Signal(url="https://undated", title="u", source="x")  # 无发布时间时按抓取时间记
        self.store.add([old, undated], now=NOW)
        got = self.store.recent(NOW - timedelta(days=1), NOW)
        self.assertEqual([s.url for s in got], ["https://undated"])
        self.assertEqual(got[0].published, NOW)

    def test_board_keeps_latest_ranks_and_first_seen(self):
        a = Signal(url="https://a", title="A", source="t", channel="all", extra={"stars_today": 9})
        b = Signal(url="https://b", title="B", source="t", channel="all")
        self.store.update_board("t", [a, b, Signal(url="https://a", title="A", source="t", channel="py")], NOW)
        self.store.update_board("t", [b, a], NOW + timedelta(hours=1))  # py 榜这次没抓到，保留上次的
        got = self.store.board("t")
        self.assertEqual([(x["channel"], x["rank"], x["url"]) for x in got],
                         [("all", 1, "https://b"), ("all", 2, "https://a"), ("py", 1, "https://a")])
        self.assertEqual((got[1]["first_seen"], got[1]["extra"]), (to_iso(NOW), {"stars_today": 9}))
        self.store.update_board("t", [a], NOW + timedelta(days=3))  # 下榜两天以上再上榜，从这次算起
        [again] = [x for x in self.store.board("t") if x["channel"] == "all"]
        self.assertEqual((again["url"], again["first_seen"]), ("https://a", to_iso(NOW + timedelta(days=3))))

    def test_concurrent_open_migrates_once(self):
        # 回归：服务重启后多个请求同时打开数据库，executescript 曾留下半迁移状态
        import tempfile
        import threading
        with tempfile.TemporaryDirectory() as d:
            path, errors = f"{d}/c.db", []

            def open_store():
                try:
                    Store(path).con.close()
                except Exception as e:
                    errors.append(e)

            threads = [threading.Thread(target=open_store) for _ in range(6)]
            for t in threads:
                t.start()
            for t in threads:
                t.join()
            self.assertEqual(errors, [])
            store = Store(path)
            self.assertEqual(store.con.execute("pragma user_version").fetchone()[0], len(MIGRATIONS))
            self.assertIn("questions", [r[1] for r in store.con.execute("pragma table_info(briefs)")])

    def test_search_matches_names_across_spellings(self):
        rows = [("https://a", "Introducing Claude Opus 4.8"), ("https://b", "Claude\u2011Opus\u20114.8 benchmarks"),
                ("https://c", "Opus4.8 第一手体验"), ("https://d", "Claude Opus 4.7 is out")]
        self.store.add([Signal(url=u, title=t, source="x", published=NOW) for u, t in rows], now=NOW)
        _, found = self.store.search(["opus-4.8"])
        self.assertEqual(sorted(s.url for s in found), ["https://a", "https://b", "https://c"])
        _, found = self.store.search(["Claude Opus 4.8", "4.7"])  # 几个名字之间是“或”
        self.assertEqual(sorted(s.url for s in found), ["https://a", "https://b", "https://d"])
        self.assertEqual(self.store.search(["o3", " ", "\""]), ([], []))  # 不到三个字符的查不了

    def test_search_index_follows_writes(self):
        self.store.add([Signal(url="https://a", title="HF model card", source="x", published=NOW)], now=NOW)
        self.store.set_summaries({"https://a": "语音合成模型 Qwen3-TTS"})  # 后来补的摘要也进索引
        self.assertEqual([s.url for s in self.store.search(["Qwen3 TTS"])[1]], ["https://a"])
        for i, name in ((1, "Dessa"), (2, "Moshi")):
            e = self.store.new_event("k", NOW)
            self.assertEqual(e, i)
            self.store.save_brief(e, NOW, "m", 1, {"headline": f"Block 发布 {name}", "summary": "", "why": "",
                                                   "importance": 3, "entities": [f"{name} Voice"]})
        self.store.save_brief(1, NOW, "m", 2, {"headline": "重写", "summary": "", "why": "", "importance": 3,
                                               "entities": ["Dessa Voice"]})  # insert or replace 不留下旧的索引
        self.assertEqual(self.store.search(["dessa voice", "moshi voice"])[0], [1, 2])
        self.assertEqual(self.store.search(["Block 发布 Dessa"])[0], [])
        self.store.merge_events(2, [1])  # 合并会删掉两边的简报，索引也跟着删
        self.assertEqual(self.store.search(["dessa voice", "moshi voice"])[0], [])

    def test_find_events_counts_terms_per_event(self):
        hy4, dessa = self.store.new_event("a", NOW), self.store.new_event("b", NOW)
        for eid, headline, names in ((hy4, "腾讯发布混元 Hy4", ["Hy4"]), (dessa, "Block 发布语音模型", ["Dessa"])):
            self.store.save_brief(eid, NOW, "m", 1, {"headline": headline, "summary": "", "why": "", "importance": 3,
                                                     "entities": names})
        self.store.add([Signal(url="https://a", title="Block unveils Dessa voice model", source="x", published=NOW),
                        Signal(url="https://b", title="Unassigned Dessa voice notes", source="x", published=NOW)], now=NOW)
        self.store.assign(dessa, ["https://a"])
        # “腾讯”两个字在简报里逐条找；Hy4 对上简报里的名字；dessa-voice 对上英文报道，没归入事件的信号不算
        self.assertEqual(self.store.find_events(["腾讯", "Hy4", "dessa-voice", "x", "100%"]), {hy4: 2, dessa: 1})

    def test_migration_indexes_existing_rows(self):
        import sqlite3
        import tempfile
        from catchup.store import _statements
        with tempfile.TemporaryDirectory() as d:
            con = sqlite3.connect(f"{d}/c.db")
            for sql in MIGRATIONS[:-1]:
                for statement in _statements(sql):
                    con.execute(statement)
            con.execute(f"pragma user_version = {len(MIGRATIONS) - 1}")
            con.execute("insert into signals (url, source, title, published, fetched) values (?, ?, ?, ?, ?)",
                        ("https://old", "x", "Introducing Claude Opus 4.8", to_iso(NOW), to_iso(NOW)))
            con.execute("insert into briefs (event_id, created, headline, summary) values (7, ?, ?, ?)",
                        (to_iso(NOW), "Anthropic 发布 Claude Opus 4.8", ""))
            con.commit()
            con.close()
            store = Store(f"{d}/c.db")
            self.assertEqual(([7], ["https://old"]), (lambda r: (r[0], [s.url for s in r[1]]))(
                store.search(["Claude Opus 4.8"])))
            self.assertIsNone(store.briefs([7])[7]["entities"])  # 加查旧之前写的简报

    def test_recent_can_skip_stale_events(self):
        self.store.add([Signal(url=u, title=u, source="x", published=NOW) for u in ("https://a", "https://b")], now=NOW)
        old, new = self.store.new_event("a", NOW), self.store.new_event("b", NOW)
        self.store.assign(old, ["https://a"])
        self.store.assign(new, ["https://b"])
        self.store.save_novelty(old, "stale", NOW, "五月的旧闻", ref_url="https://x")
        self.store.save_novelty(new, "new", NOW)
        window = (NOW - timedelta(days=1), NOW)
        self.assertEqual(len(self.store.recent(*window)), 2)
        self.assertEqual([s.url for s in self.store.recent(*window, include_stale=False)], ["https://b"])
        self.store.merge_events(new, [old])  # 并掉的那件，结论跟着删；留下的保留自己的结论
        self.assertEqual({i: v["verdict"] for i, v in self.store.novelty([old, new]).items()}, {new: "new"})

    def test_last_runs(self):
        self.store.record_run("x", NOW, 3, 1)
        self.store.record_run("x", NOW, 0, 0, "boom")
        self.assertEqual(self.store.last_runs()["x"]["error"], "boom")


if __name__ == "__main__":
    unittest.main()
