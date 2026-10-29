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

    def test_last_runs(self):
        self.store.record_run("x", NOW, 3, 1)
        self.store.record_run("x", NOW, 0, 0, "boom")
        self.assertEqual(self.store.last_runs()["x"]["error"], "boom")


if __name__ == "__main__":
    unittest.main()
